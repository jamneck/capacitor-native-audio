import Capacitor
import Foundation

public class AudioMetadata {
    var albumTitle: String
    var artistName: String
    var songTitle: String
    var artworkSource: String
    var updateUrl: String
    var updateInterval: Int = 15

    /// Returns the wall-clock date of audio currently reaching the listener
    /// (from AVPlayerItem.currentDate() / HLS EXT-X-PROGRAM-DATE-TIME).
    public var currentDateProvider: (() -> Date?)?

    private var onMetadataUpdateCallbackId: String = ""

    private var updateHandler: DispatchSourceTimer!
    private var updateCallback: (() -> Void)!
    private var updateFullResponse: [String: Any] = [:]

    private var pluginOwner: AudioPlayerPlugin?

    /// Recent play_history rows for buffer-depth correction (newest-first).
    /// Written from the play_history URLSession completion queue, read from
    /// the now-playing URLSession completion queue — both background queues,
    /// so all access is serialized through historyQueue.
    private var historyCache: [(title: String, artist: String, startedAt: Double)] = []
    private var historyFetchedAt: Date = .distantPast
    private let historyQueue = DispatchQueue(label: "fm.kudu.audiometadata.history")

    private static let playHistoryURLString =
        "https://api.kudu.fm/api/collections/play_history/records?sort=-startedAt&perPage=12"
    private static let historyRefreshInterval: TimeInterval = 15

    public init(
        albumTitle: String,
        artistName: String,
        songTitle: String,
        artworkSource: String,
        updateUrl: String,
        updateInterval: Int
    ) {
        self.albumTitle = albumTitle
        self.artistName = artistName
        self.songTitle = songTitle
        self.artworkSource = artworkSource
        self.updateUrl = updateUrl

        if updateInterval != -1 {
            self.updateInterval = updateInterval
        }
    }

    public func update(metadata: AudioMetadata) {
        self.albumTitle = metadata.albumTitle
        self.artistName = metadata.artistName
        self.songTitle = metadata.songTitle
        self.artworkSource = metadata.artworkSource
    }

    public func setPluginOwner(pluginOwner: AudioPlayerPlugin) -> Self {
        self.pluginOwner = pluginOwner

        return self
    }

    public func setUpdateCallback(callback: @escaping () -> Void) -> Self {
        self.updateCallback = callback

        return self
    }

    public func setOnMetadataUpdate(callbackId: String) {
        onMetadataUpdateCallbackId = callbackId
    }

    public func getOnMetadataUpdateCallbackId() -> String {
        return onMetadataUpdateCallbackId
    }

    public func startUpdater() {
        if !hasUpdateUrl() || updateHandler != nil {
            return
        }

        print("Starting metadata updater...")

        updateHandler = DispatchSource.makeTimerSource(
            queue: DispatchQueue.global(qos: .background)
        )
        updateHandler.schedule(
            deadline: .now(),
            repeating: .seconds(updateInterval),
            leeway: .milliseconds(250)
        )
        updateHandler.setEventHandler {
            self.makeUpdateRequest()
        }

        updateHandler.activate()
    }

    public func stopUpdater() {
        if updateHandler == nil {
            return
        }

        print("Stopping metadata updater...")

        updateHandler.cancel()
        updateHandler = nil
    }

    public func hasUpdateUrl() -> Bool {
        return !updateUrl.isEmpty
    }

    public func updateMetadataByUrl() {
        makeUpdateRequest()
    }

    private func makeUpdateRequest() {
        guard
            let updateUrl = URL.init(string: self.updateUrl)
        else {
            print("Update metadata URL is invalid")
            return
        }

        var updateRequest = URLRequest(url: updateUrl)
        updateRequest.addValue("application/json", forHTTPHeaderField: "Accept")

        print("Getting metadata from URL \(self.updateUrl)")

        URLSession.shared.dataTask(with: updateRequest) { (data, response, error) in
            if let error = error {
                print(error)
                return
            }

            guard let httpResponse = response as? HTTPURLResponse else {
                print("The metadata update server response is invalid")
                return
            }

            guard (200...299).contains(httpResponse.statusCode) else {
                print(
                    "The metadata update server returned a non-2xx status code: \(httpResponse.statusCode)"
                )
                return
            }

            do {
                guard
                    let json = (try JSONSerialization.jsonObject(with: data!)) as? [String: Any]
                else {
                    print("The metadata update data could not be parsed as JSON")
                    return
                }

                print(json)

                var artistName = json["artist_name"] as? String ?? ""
                var songTitle = json["song_title"] as? String ?? ""

                // Buffer-depth correction: map player wall-clock (from HLS
                // PROGRAM-DATE-TIME) onto play_history so metadata matches
                // what the listener is actually hearing. Skip for live DJ mode.
                if json["mode"] as? String != "live", let now = self.currentDateProvider?() {
                    self.refreshHistoryIfStale()
                    if let match = self.lookupHistory(wallClockNow: now) {
                        songTitle = match.title
                        artistName = match.artist
                    }
                }

                self.updateFullResponse = json
                self.albumTitle = json["album_title"] as? String ?? ""
                self.artistName = artistName
                self.songTitle = songTitle
                self.artworkSource = json["artwork_source"] as? String ?? ""
            } catch {
                print(
                    "An error occurred trying to get updated metadata: \(error.localizedDescription)"
                )
            }

            if self.onMetadataUpdateCallbackId != "" {
                DispatchQueue.main.async {
                    self.pluginOwner?.bridge?.savedCall(withID: self.onMetadataUpdateCallbackId)?
                        .resolve(self.updateFullResponse)
                }

            }

            if self.updateCallback != nil {
                DispatchQueue.main.async {
                    self.updateCallback()
                }
            }
        }.resume()
    }

    /// Refresh play_history cache at most every ~15s. Sets fetchedAt before the
    /// async call to avoid overlapping fetches. On error, leaves cache untouched.
    private func refreshHistoryIfStale() {
        let isStale: Bool = historyQueue.sync {
            if Date().timeIntervalSince(historyFetchedAt) < Self.historyRefreshInterval {
                return false
            }
            historyFetchedAt = Date() // stamped inside the lock so overlapping calls don't both refetch
            return true
        }
        if !isStale { return }

        guard let url = URL(string: Self.playHistoryURLString) else {
            return
        }

        var request = URLRequest(url: url)
        request.addValue("application/json", forHTTPHeaderField: "Accept")

        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let self else { return }

            if let error = error {
                print("play_history fetch error: \(error)")
                return
            }

            guard let httpResponse = response as? HTTPURLResponse,
                  (200...299).contains(httpResponse.statusCode),
                  let data = data
            else {
                return
            }

            do {
                guard
                    let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                    let items = json["items"] as? [[String: Any]]
                else {
                    return
                }

                var parsed: [(title: String, artist: String, startedAt: Double)] = []
                parsed.reserveCapacity(items.count)

                for item in items {
                    let title = item["title"] as? String ?? ""
                    let artist = item["artist"] as? String ?? ""
                    guard let startedAt = Self.parseStartedAt(item["startedAt"]) else {
                        continue
                    }
                    parsed.append((title: title, artist: artist, startedAt: startedAt))
                }

                self.historyQueue.sync { self.historyCache = parsed }
            } catch {
                // Leave existing cache alone on parse failure.
                print("play_history parse error: \(error.localizedDescription)")
            }
        }.resume()
    }

    /// First (newest-first) history entry with startedAt <= wallClockNow, or nil.
    private func lookupHistory(wallClockNow: Date) -> (title: String, artist: String)? {
        let target = wallClockNow.timeIntervalSince1970
        return historyQueue.sync {
            for entry in historyCache {
                if entry.startedAt <= target {
                    return (title: entry.title, artist: entry.artist)
                }
            }
            return nil
        }
    }

    /// PocketBase may encode startedAt as Int or Double (NSNumber under JSONSerialization).
    private static func parseStartedAt(_ value: Any?) -> Double? {
        if let number = value as? NSNumber {
            return number.doubleValue
        }
        if let double = value as? Double {
            return double
        }
        if let int = value as? Int {
            return Double(int)
        }
        return nil
    }
}
