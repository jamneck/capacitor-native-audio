package us.mediagrid.capacitorjs.plugins.nativeaudio;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Metadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

public class AudioPlayerService extends MediaSessionService {

    private static final String TAG = "AudioPlayerService";
    public static final String PLAYBACK_CHANNEL_ID = "playback_channel";
    private MediaSession mediaSession = null;

    @Override
    public void onCreate() {
        Log.i(TAG, "Service being created");
        super.onCreate();

        String packageName = getApplicationContext().getPackageName();
        Intent sessionActivityIntent = getPackageManager().getLaunchIntentForPackage(packageName);

        PendingIntent sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        ExoPlayer player = new ExoPlayer.Builder(this)
            .setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build();
        player.setPlayWhenReady(false);

        // In-band timed metadata (ID3 TIT2/TPE1 in the HLS segments) is a
        // PLAYER-LOCAL event: ExoPlayer's metadata renderer emits it at the
        // sample's presentation time, so it is locked to the audio you hear.
        //
        // It never crosses the MediaSession boundary. `useForNotification`
        // sources hold a MediaController proxy rather than this ExoPlayer
        // (AudioPlayerPlugin -> setPlayer(audioMediaController)), and a
        // MediaController is only sent the subset of Player.Listener callbacks
        // the session protocol defines -- onMetadata is not one of them. So the
        // PlayerEventListener attached over there could never fire, and Android
        // was left with only the metadataUpdateUrl poll: the LIVE EDGE, which
        // runs ahead of the audio by the buffer depth. iOS was unaffected
        // because AVPlayerItemMetadataOutput binds straight to the item.
        //
        // Forward it here, from the player that actually decodes it, into the
        // same listener the rest of the plugin already uses.
        player.addListener(
            new Player.Listener() {
                @Override
                public void onMetadata(Metadata metadata) {
                    forwardMetadataToNotificationSource(metadata);
                }
            }
        );

        mediaSession = new MediaSession.Builder(this, player)
            .setCallback(new MediaSessionCallback(this))
            .setSessionActivity(sessionActivityPendingIntent)
            .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service starting");

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        Log.i(TAG, "Task removed");

        AudioSources audioSources = getAudioSourcesFromMediaSession();

        if (audioSources != null) {
            Log.i(TAG, "Destroying all non-notification audio sources");
            audioSources.destroyAllNonNotificationSources();
        }

        Player player = mediaSession.getPlayer();

        // Make sure the service is not in foreground
        if (player.getPlayWhenReady()) {
            player.pause();
        }

        stopSelf();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service being destroyed");

        AudioSources audioSources = getAudioSourcesFromMediaSession();

        if (audioSources != null) {
            Log.i(TAG, "Destroying all non-notification audio sources");
            audioSources.destroyAllNonNotificationSources();
        }

        mediaSession.getPlayer().release();
        mediaSession.release();
        mediaSession = null;

        super.onDestroy();
    }

    /**
     * Hand an in-band metadata event to the notification source's listener, which
     * owns the TIT2/TPE1 parsing and the JS callback. Silently a no-op until the
     * plugin has published its AudioSources binder into the session extras --
     * metadata can arrive before, and dropping those early events is correct: the
     * stream tags every segment, so the next one is ~4s away.
     */
    private void forwardMetadataToNotificationSource(Metadata metadata) {
        if (mediaSession == null) {
            return;
        }

        AudioSources audioSources = getAudioSourcesFromMediaSession();

        if (audioSources == null) {
            return;
        }

        AudioSource notificationSource = audioSources.forNotification();

        if (notificationSource == null) {
            return;
        }

        PlayerEventListener listener = notificationSource.getEventListener();

        if (listener == null) {
            return;
        }

        listener.onMetadata(metadata);
    }

    @OptIn(markerClass = UnstableApi.class)
    private AudioSources getAudioSourcesFromMediaSession() {
        IBinder sourcesBinder = mediaSession.getSessionExtras().getBinder("audioSources");

        if (sourcesBinder != null) {
            return (AudioSources) sourcesBinder;
        }

        return null;
    }
}
