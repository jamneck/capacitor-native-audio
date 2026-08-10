package us.mediagrid.capacitorjs.plugins.nativeaudio;

import static androidx.media3.common.Player.*;

import androidx.media3.common.Metadata;
import androidx.media3.extractor.metadata.id3.TextInformationFrame;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

public class PlayerEventListener implements Listener {

    private static final String TAG = "PlayerEventListener";

    private AudioPlayerPlugin plugin;
    private AudioSource audioSource;

    // Last title/artist actually taken from in-band ID3. Held separately from
    // audioSource.audioMetadata because the metadataUpdateUrl poll writes that
    // too -- comparing against it would make every poll look like a change and
    // re-fire the whole chain on the poll's cadence.
    private String lastId3Title;
    private String lastId3Artist;

    public PlayerEventListener(AudioPlayerPlugin plugin, AudioSource audioSource) {
        this.plugin = plugin;
        this.audioSource = audioSource;
        this.audioSource.setEventListener(this);
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        String status = "stopped";

        if (audioSource.isInitialized()) {
            if (
                audioSource.getPlayer().getPlaybackState() == STATE_READY &&
                !audioSource.getPlayer().getPlayWhenReady() &&
                !audioSource.isStopped()
            ) {
                status = "paused";
                audioSource.setIsPaused();
                audioSource.audioMetadata.stopUpdater();
            } else if (isPlaying || audioSource.isPlaying()) {
                status = "playing";
                audioSource.setIsPlaying();
            }
        }

        makeCall(
            audioSource.onPlaybackStatusChangeCallbackId,
            new JSObject().put("status", status)
        );
    }

    @Override
    public void onPlaybackStateChanged(@State int playbackState) {
        if (playbackState == STATE_READY) {
            makeCall(audioSource.onReadyCallbackId);
        }

        if (playbackState == STATE_ENDED) {
            audioSource.getPlayer().stop();
            audioSource.getPlayer().seekToDefaultPosition();
            audioSource.setIsStopped();
            audioSource.audioMetadata.stopUpdater();

            makeCall(audioSource.onEndCallbackId);
        }
    }

    @Override
    public void onMetadata(Metadata metadata) {
        String title = null;
        String artist = null;

        for (int i = 0; i < metadata.length(); i++) {
            Metadata.Entry entry = metadata.get(i);
            if (entry instanceof TextInformationFrame) {
                TextInformationFrame frame = (TextInformationFrame) entry;
                if ("TIT2".equals(frame.id) && !frame.values.isEmpty()) {
                    title = frame.values.get(0);
                }
                if ("TPE1".equals(frame.id) && !frame.values.isEmpty()) {
                    artist = frame.values.get(0);
                }
            }
        }

        if (title != null || artist != null) {
            // The stream tags EVERY segment, so the same song arrives every few
            // seconds. Only act on an actual change: replaceMediaItem() and a
            // bridge round-trip per segment would be pure churn, and the poll
            // still re-asserts artwork on its own cadence.
            String newTitle = title != null ? title : lastId3Title;
            String newArtist = artist != null ? artist : lastId3Artist;

            if (
                java.util.Objects.equals(newTitle, lastId3Title) &&
                java.util.Objects.equals(newArtist, lastId3Artist)
            ) {
                return;
            }

            lastId3Title = newTitle;
            lastId3Artist = newArtist;

            if (title != null) audioSource.audioMetadata.songTitle = title;
            if (artist != null) audioSource.audioMetadata.artistName = artist;
            audioSource.changeMetadata(audioSource.audioMetadata);

            // Fire onMetadataUpdate callback to JS
            JSObject data = new JSObject();
            data.put("title", audioSource.audioMetadata.songTitle);
            data.put("artist", audioSource.audioMetadata.artistName);
            data.put("song_title", audioSource.audioMetadata.songTitle);
            data.put("artist_name", audioSource.audioMetadata.artistName);
            makeCall(audioSource.audioMetadata.getOnMetadataUpdateCallbackId(), data);
        }
    }

    private void makeCall(String callbackId) {
        makeCall(callbackId, new JSObject());
    }

    private void makeCall(String callbackId, JSObject data) {
        if (callbackId == null) {
            return;
        }

        PluginCall call = plugin.getBridge().getSavedCall(callbackId);

        if (call == null) {
            return;
        }

        if (data.length() == 0) {
            call.resolve();
        } else {
            call.resolve(data);
        }
    }
}
