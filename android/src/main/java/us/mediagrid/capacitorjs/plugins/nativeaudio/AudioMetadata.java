package us.mediagrid.capacitorjs.plugins.nativeaudio;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import com.getcapacitor.plugin.util.HttpRequestHandler;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class AudioMetadata {

    private static final String TAG = "AudioMetadata";

    public String albumTitle;
    public String artistName;
    public String songTitle;
    public String artworkSource;
    public String updateUrl;
    public Integer updateInterval = 15;

    private String onMetadataUpdateCallbackId;

    private Handler updateHandler = null;
    private Runnable updateRunner = null;
    private Runnable updateCallback = null;
    private JSObject updateFullResponse;

    private AudioPlayerPlugin pluginOwner;

    AudioMetadata(
        String albumTitle,
        String artistName,
        String songTitle,
        String artworkSource,
        String updateUrl,
        Integer updateInterval
    ) {
        this.albumTitle = albumTitle;
        this.artistName = artistName;
        this.songTitle = songTitle;
        this.artworkSource = artworkSource;
        this.updateUrl = updateUrl;

        if (updateInterval != null) {
            this.updateInterval = updateInterval;
        }
    }

    public void update(AudioMetadata metadata) {
        albumTitle = metadata.albumTitle;
        artistName = metadata.artistName;
        songTitle = metadata.songTitle;
        artworkSource = metadata.artworkSource;
    }

    public AudioMetadata setPluginOwner(AudioPlayerPlugin plugin) {
        pluginOwner = plugin;

        return this;
    }

    public AudioMetadata setUpdateCallBack(Runnable callback) {
        updateCallback = callback;

        return this;
    }

    public void setOnMetadataUpdate(String callbackId) {
        onMetadataUpdateCallbackId = callbackId;
    }

    public String getOnMetadataUpdateCallbackId() {
        return onMetadataUpdateCallbackId;
    }

    public void startUpdater() {
        if (!hasUpdateUrl() || updateHandler != null) {
            return;
        }

        Log.i(TAG, "Starting metadata updater...");

        updateHandler = new Handler(Looper.getMainLooper());
        updateRunner = new Runnable() {
            @Override
            public void run() {
                updateMetadataByUrl(this);
            }
        };

        updateHandler.post(updateRunner);
    }

    public void stopUpdater() {
        if (updateHandler == null) {
            return;
        }

        Log.i(TAG, "Stopping metadata updater...");

        updateHandler.removeCallbacks(updateRunner);
        updateHandler = null;
        updateRunner = null;
    }

    public boolean hasUpdateUrl() {
        return updateUrl != null && updateUrl != "";
    }

    /**
     * Fetch updateUrl once and apply the result. `poller` is the updater's
     * runner, rescheduled after every attempt, or null for a one-shot update.
     */
    public void updateMetadataByUrl(Runnable poller) {
        if (pluginOwner.executorService.isShutdown()) {
            return;
        }

        pluginOwner.executorService.submit(() -> {
            JSObject json = makeUpdateRequest();

            // Everything after the request runs on the main thread, which also
            // owns startUpdater/stopUpdater, so the check below cannot race them.
            new Handler(Looper.getMainLooper()).post(() -> {
                // stopUpdater() while the request was in flight -- or a stop and
                // a restart -- orphans this poller. Rescheduling it threw on the
                // null handler, or after a restart left two pollers running.
                if (poller != null && poller != updateRunner) {
                    return;
                }

                if (json != null) {
                    try {
                        applyUpdate(json);
                    } catch (Exception ex) {
                        Log.e(TAG, "There was an error running the metadata update", ex);
                    }
                }

                // A failed request is retried on the next interval. It used to
                // return before this point, which ended the poll for the session.
                if (poller != null) {
                    updateHandler.postDelayed(poller, updateInterval * 1000);
                }
            });
        });
    }

    private void applyUpdate(JSObject json) {
        updateFullResponse = json;
        albumTitle = json.getString("album_title");
        artistName = json.getString("artist_name");
        songTitle = json.getString("song_title");
        artworkSource = json.getString("artwork_source");

        if (updateCallback != null) {
            updateCallback.run();
        }

        if (onMetadataUpdateCallbackId != null) {
            PluginCall call = pluginOwner.getBridge().getSavedCall(onMetadataUpdateCallbackId);

            if (call != null) {
                call.resolve(updateFullResponse);
            }
        }
    }

    private JSObject makeUpdateRequest() {
        Log.i(TAG, "Getting metadata from URL " + updateUrl);
        HttpURLConnection urlConnection = null;

        try {
            URL url = new URL(updateUrl);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestProperty("Accept", "application/json");

            InputStream errorStream = urlConnection.getErrorStream();

            if (errorStream != null) {
                Log.e(
                    TAG,
                    String.format(
                        "The metadata update server returned a status of %s with the message %s",
                        urlConnection.getResponseCode(),
                        HttpRequestHandler.readStreamAsString(errorStream)
                    )
                );
            } else {
                JSObject json = new JSObject(
                    HttpRequestHandler.readStreamAsString(urlConnection.getInputStream())
                );

                Log.i(TAG, json.toString());

                return json;
            }
        } catch (Exception ex) {
            Log.e(TAG, "An error occurred trying to get updated metadata", ex);
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }

        return null;
    }
}
