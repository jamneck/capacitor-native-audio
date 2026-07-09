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
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class AudioMetadata {

    private static final String TAG = "AudioMetadata";
    private static final String PLAY_HISTORY_URL =
        "https://api.kudu.fm/api/collections/play_history/records?sort=-startedAt&perPage=12";
    private static final long HISTORY_REFRESH_INTERVAL_MS = 15_000L;
    /** Player.TIME_UNSET / C.TIME_UNSET — live offset unavailable. */
    private static final long TIME_UNSET = Long.MIN_VALUE + 1;

    /**
     * Supplies ExoPlayer/Media3 live offset (ms behind the live edge).
     * Return {@code TIME_UNSET} ({@code Long.MIN_VALUE + 1}) when unavailable.
     */
    public interface LiveOffsetProvider {
        long getLiveOffsetMs();
    }

    public LiveOffsetProvider liveOffsetProvider;

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

    // Written from the metadata-updater's executor thread (refreshHistoryIfStale,
    // called from makeUpdateRequest) — but updateMetadataByUrl() can also be
    // invoked directly (the public JS-facing path), and pluginOwner.executorService
    // is a pool, not single-threaded, so two requests can run concurrently.
    // All access to historyCache/historyFetchedAtMs goes through historyLock.
    private final Object historyLock = new Object();
    private final List<HistoryEntry> historyCache = new ArrayList<>();
    private long historyFetchedAtMs = 0;

    private static class HistoryEntry {

        final String title;
        final String artist;
        /** Unix seconds when this track started at the broadcast edge. */
        final double startedAt;

        HistoryEntry(String title, String artist, double startedAt) {
            this.title = title;
            this.artist = artist;
            this.startedAt = startedAt;
        }
    }

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
                updateMetadataByUrl(() -> updateHandler.postDelayed(this, updateInterval * 1000));
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

    public void updateMetadataByUrl(Runnable requeueCallback) {
        if (pluginOwner.executorService.isShutdown()) {
            return;
        }

        pluginOwner.executorService.submit(() -> {
            try {
                if (!makeUpdateRequest()) {
                    return;
                }

                if (updateCallback != null) {
                    // Updating the MediaController needs to be on the main thread
                    new Handler(Looper.getMainLooper()).post(() -> {
                        updateCallback.run();
                    });
                }

                if (onMetadataUpdateCallbackId != null) {
                    PluginCall call = pluginOwner
                        .getBridge()
                        .getSavedCall(onMetadataUpdateCallbackId);

                    if (call != null) {
                        call.resolve(updateFullResponse);
                    }
                }
            } catch (Exception ex) {
                Log.e(TAG, "There was an error running the metadata update", ex);
            }

            if (requeueCallback != null) {
                requeueCallback.run();
            }
        });
    }

    private boolean makeUpdateRequest() {
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

                updateFullResponse = json;
                albumTitle = json.getString("album_title");

                String resolvedArtist = json.getString("artist_name");
                String resolvedTitle = json.getString("song_title");

                // Buffer-depth correction: when not a live DJ broadcast, prefer the
                // play_history entry that started at or before the wall-clock time
                // represented by audio currently reaching the listener's ears.
                // mode may be absent on older servers — treat missing as non-live.
                if (!"live".equals(json.getString("mode"))) {
                    refreshHistoryIfStale();

                    if (liveOffsetProvider != null) {
                        long liveOffsetMs = liveOffsetProvider.getLiveOffsetMs();

                        // TIME_UNSET or any negative offset → fall back to /now-playing.
                        if (liveOffsetMs != TIME_UNSET && liveOffsetMs >= 0) {
                            long wallClockNowMs = System.currentTimeMillis() - liveOffsetMs;
                            HistoryEntry match = lookupHistory(wallClockNowMs);

                            if (match != null) {
                                resolvedTitle = match.title;
                                resolvedArtist = match.artist;
                                Log.i(
                                    TAG,
                                    String.format(
                                        "Buffer-depth correction: liveOffset=%dms wallClockNow=%d → %s – %s",
                                        liveOffsetMs,
                                        wallClockNowMs,
                                        match.artist,
                                        match.title
                                    )
                                );
                                // Keep the JS onMetadataUpdate payload consistent with the
                                // corrected lock-screen fields — only touch it when we
                                // actually override, so the uncorrected path stays a pure
                                // passthrough of the original /now-playing response.
                                try {
                                    updateFullResponse.put("artist_name", resolvedArtist);
                                    updateFullResponse.put("song_title", resolvedTitle);
                                } catch (Exception ignored) {
                                    // Non-fatal: native fields already hold the corrected values.
                                }
                            }
                        }
                    }
                }

                artistName = resolvedArtist;
                songTitle = resolvedTitle;
                artworkSource = json.getString("artwork_source");

                return true;
            }
        } catch (Exception ex) {
            Log.e(TAG, "An error occurred trying to get updated metadata", ex);
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }

        return false;
    }

    /**
     * Refresh play_history cache at most every {@link #HISTORY_REFRESH_INTERVAL_MS}.
     * On error, leaves the existing cache untouched.
     */
    private void refreshHistoryIfStale() {
        long now = System.currentTimeMillis();

        synchronized (historyLock) {
            if (now - historyFetchedAtMs < HISTORY_REFRESH_INTERVAL_MS) {
                return;
            }
            // Stamp immediately (inside the lock) so concurrent/overlapping
            // calls don't both refetch.
            historyFetchedAtMs = now;
        }

        HttpURLConnection urlConnection = null;

        try {
            URL url = new URL(PLAY_HISTORY_URL);
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestProperty("Accept", "application/json");

            InputStream errorStream = urlConnection.getErrorStream();

            if (errorStream != null) {
                Log.e(
                    TAG,
                    String.format(
                        "play_history returned status %s: %s",
                        urlConnection.getResponseCode(),
                        HttpRequestHandler.readStreamAsString(errorStream)
                    )
                );
                return;
            }

            JSObject json = new JSObject(
                HttpRequestHandler.readStreamAsString(urlConnection.getInputStream())
            );
            JSONArray items = json.getJSONArray("items");

            if (items == null) {
                return;
            }

            List<HistoryEntry> next = new ArrayList<>(items.length());

            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String title = item.optString("title", null);
                String artist = item.optString("artist", null);

                if (title == null || artist == null || !item.has("startedAt")) {
                    continue;
                }

                next.add(new HistoryEntry(title, artist, item.getDouble("startedAt")));
            }

            synchronized (historyLock) {
                historyCache.clear();
                historyCache.addAll(next);
            }
            Log.i(TAG, "play_history cache refreshed with " + next.size() + " entries");
        } catch (Exception ex) {
            Log.e(TAG, "Failed to refresh play_history; keeping existing cache", ex);
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    /**
     * Return the newest history entry that had already started by {@code wallClockNowMs}.
     * Cache is newest-first (API {@code sort=-startedAt}).
     */
    private HistoryEntry lookupHistory(long wallClockNowMs) {
        double target = wallClockNowMs / 1000.0;

        synchronized (historyLock) {
            for (HistoryEntry entry : historyCache) {
                if (entry.startedAt <= target) {
                    return entry;
                }
            }
        }

        return null;
    }
}
