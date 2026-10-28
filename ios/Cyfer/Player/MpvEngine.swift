import Libmpv
import UIKit

final class MetalLayer: CAMetalLayer {
    // MoltenVK sets the drawable to 1x1 to force a present, which flickers and can stick (mpv#13651).
    override var drawableSize: CGSize {
        get { super.drawableSize }
        set {
            if Int(newValue.width) > 1 && Int(newValue.height) > 1 { super.drawableSize = newValue }
        }
    }

    // EDR only switches on when this is set from the main thread.
    override var wantsExtendedDynamicRangeContent: Bool {
        get { super.wantsExtendedDynamicRangeContent }
        set {
            if Thread.isMainThread {
                super.wantsExtendedDynamicRangeContent = newValue
            } else {
                DispatchQueue.main.sync { super.wantsExtendedDynamicRangeContent = newValue }
            }
        }
    }
}

final class MpvView: UIView {
    let metalLayer = MetalLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        metalLayer.contentsScale = UIScreen.main.nativeScale
        metalLayer.framebufferOnly = true
        metalLayer.backgroundColor = UIColor.black.cgColor
        layer.addSublayer(metalLayer)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        metalLayer.frame = bounds
        CATransaction.commit()
    }
}

/// libmpv through MoltenVK: the backup for everything AVFoundation can't open (MKV above all).
final class MpvEngine: PlayerEngine {
    let kind = EngineKind.mpv
    var view: UIView { mpvView }
    var supportsPictureInPicture: Bool { false }
    var supportsSubtitleDelay: Bool { true }

    private weak var model: PlayerModel?
    private let mpvView = MpvView()
    private var mpv: OpaquePointer?
    private let queue = DispatchQueue(label: "cyfer.mpv", qos: .userInitiated)
    private var failed = false
    private var backgroundObservers: [NSObjectProtocol] = []

    init(model: PlayerModel) {
        self.model = model
        guard let handle = mpv_create() else { return }
        mpv = handle

        option("vo", "gpu-next")
        option("gpu-api", "vulkan")
        option("gpu-context", "moltenvk")
        option("hwdec", "videotoolbox")
        // HDR straight to the EDR display; has to be set before the first frame.
        option("target-colorspace-hint", "yes")
        option("video-rotate", "no")
        option("keep-open", "yes")
        option("cache", "yes")
        option("demuxer-max-bytes", "150MiB")
        option("demuxer-max-back-bytes", "50MiB")
        option("subs-fallback", "yes")
        var wid = Int64(Int(bitPattern: Unmanaged.passUnretained(mpvView.metalLayer).toOpaque()))
        mpv_set_option(handle, "wid", MPV_FORMAT_INT64, &wid)
        mpv_request_log_messages(handle, "warn")
        check(mpv_initialize(handle))

        mpv_observe_property(handle, 0, "time-pos", MPV_FORMAT_DOUBLE)
        mpv_observe_property(handle, 0, "duration", MPV_FORMAT_DOUBLE)
        mpv_observe_property(handle, 0, "pause", MPV_FORMAT_FLAG)
        mpv_observe_property(handle, 0, "paused-for-cache", MPV_FORMAT_FLAG)
        mpv_observe_property(handle, 0, "demuxer-cache-duration", MPV_FORMAT_DOUBLE)
        mpv_observe_property(handle, 0, "track-list", MPV_FORMAT_NONE)
        mpv_observe_property(handle, 0, "chapter-list", MPV_FORMAT_NONE)
        mpv_observe_property(handle, 0, "video-params/gamma", MPV_FORMAT_NONE)
        mpv_observe_property(handle, 0, "audio-codec-name", MPV_FORMAT_NONE)

        mpv_set_wakeup_callback(handle, { context in
            guard let context else { return }
            Unmanaged<MpvEngine>.fromOpaque(context).takeUnretainedValue().readEvents()
        }, Unmanaged.passUnretained(self).toOpaque())

        let center = NotificationCenter.default
        backgroundObservers = [
            // Without a surface in the background mpv comes back to a black picture.
            center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
                self?.pause()
                self?.property("vid", "no")
            },
            center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
                self?.property("vid", "auto")
            },
        ]
    }

    func load(_ url: URL, start: Double) {
        guard let handle = mpv else {
            fail("mpv didn't start")
            return
        }
        if start > 1 { property("start", String(format: "%.3f", start)) }
        command(handle, ["loadfile", url.isFileURL ? url.path : url.absoluteString, "replace"])
    }

    // MARK: Events

    private func readEvents() {
        queue.async { [weak self] in
            guard let engine = self else { return }
            while let handle = engine.mpv {
                guard let event = mpv_wait_event(handle, 0)?.pointee, event.event_id != MPV_EVENT_NONE else { break }
                engine.handle(event)
            }
        }
    }

    private func handle(_ event: mpv_event) {
        switch event.event_id {
        case MPV_EVENT_PROPERTY_CHANGE:
            guard let property = event.data?.assumingMemoryBound(to: mpv_event_property.self).pointee else { return }
            propertyChanged(String(cString: property.name), property)
        case MPV_EVENT_FILE_LOADED:
            report { $0.loading = false }
            refreshTracks()
            refreshVideo()
            refreshChapters()
        case MPV_EVENT_END_FILE:
            guard let end = event.data?.assumingMemoryBound(to: mpv_event_end_file.self).pointee else { return }
            if end.error < 0 { fail("mpv: \(String(cString: mpv_error_string(end.error)))") }
        case MPV_EVENT_LOG_MESSAGE:
            guard let log = event.data?.assumingMemoryBound(to: mpv_event_log_message.self).pointee else { return }
            print("[mpv/\(String(cString: log.prefix))] \(String(cString: log.text))", terminator: "")
        default:
            break
        }
    }

    private func propertyChanged(_ name: String, _ property: mpv_event_property) {
        switch name {
        case "time-pos":
            if let v = double(property) { report { $0.position = v } }
        case "duration":
            if let v = double(property) { report { $0.duration = v } }
        case "pause":
            if let v = flag(property) { report { $0.paused = v } }
        case "paused-for-cache":
            if let v = flag(property) { report { $0.buffering = v } }
        case "demuxer-cache-duration":
            let v = double(property) ?? 0
            report { $0.bufferedAhead = v }
        case "track-list":
            refreshTracks()
        case "chapter-list":
            refreshChapters()
        case "video-params/gamma":
            refreshVideo()
        case "audio-codec-name":
            let codec = string("audio-codec-name")
            report { $0.audioCodec = codec }
        default:
            break
        }
    }

    private func refreshTracks() {
        let count = Int(string("track-list/count") ?? "") ?? 0
        var audio: [MediaTrack] = []
        var subs: [MediaTrack] = []
        var currentAudio: Int?
        var currentSub = 0
        for i in 0..<count {
            let base = "track-list/\(i)/"
            guard let type = string(base + "type"), let id = Int(string(base + "id") ?? "") else { continue }
            let lang = string(base + "lang")
            let title = string(base + "title")
            let selected = string(base + "selected") == "yes"
            switch type {
            case "audio":
                let codec = string(base + "codec")
                let channels = Int(string(base + "demux-channel-count") ?? "") ?? 0
                let detail = [
                    audioCodecTag(codec)?.label ?? codec?.uppercased(),
                    channelLayout(channels).nilIfEmpty,
                ].compactMap { $0 }.joined(separator: " · ")
                audio.append(MediaTrack(
                    id: id,
                    title: audioTrackTitle(id: id, lang: lang, title: title, channels: channels),
                    detail: detail.nilIfEmpty
                ))
                if selected { currentAudio = id }
            case "sub":
                let forced = string(base + "forced") == "yes"
                let external = string(base + "external") == "yes"
                let hearingImpaired = string(base + "hearing-impaired") == "yes"
                let detail = [forced ? "Forced" : nil, external ? "Added" : nil].compactMap { $0 }.joined(separator: " · ")
                subs.append(MediaTrack(
                    id: id,
                    title: subtitleTrackTitle(id: id, lang: lang, title: title, forced: forced, hearingImpaired: hearingImpaired),
                    detail: detail.nilIfEmpty
                ))
                if selected { currentSub = id }
            default:
                break
            }
        }
        report { m in
            m.audioTracks = audio
            m.subtitleTracks = subs
            m.currentAudio = currentAudio
            m.currentSubtitle = currentSub
        }
    }

    private func refreshVideo() {
        let gamma = string("video-params/gamma")
        let range: DynamicRange
        switch gamma {
        case "pq": range = .hdr10
        case "hlg": range = .hlg
        default: range = .sdr
        }
        report { $0.range = range }
    }

    private func refreshChapters() {
        let count = Int(string("chapter-list/count") ?? "") ?? 0
        let times = (0..<count).compactMap { Double(string("chapter-list/\($0)/time") ?? "") }
        report { $0.chapters = times }
    }

    // MARK: Controls

    func play() { property("pause", "no") }

    func pause() { property("pause", "yes") }

    func seek(to seconds: Double, exact: Bool) {
        guard let handle = mpv else { return }
        command(handle, ["seek", String(format: "%.3f", seconds), exact ? "absolute+exact" : "absolute+keyframes"])
    }

    func setSpeed(_ speed: Float) { property("speed", String(format: "%.2f", speed)) }

    func setFill(_ fill: Bool) { property("panscan", fill ? "1.0" : "0.0") }

    func selectAudio(_ id: Int) { property("aid", "\(id)") }

    func selectSubtitle(_ id: Int) { property("sid", id == 0 ? "no" : "\(id)") }

    func setSubtitleDelay(_ seconds: Double) { property("sub-delay", String(format: "%.2f", seconds)) }

    func startPictureInPicture() {}

    func stats() -> [(String, String)] {
        var rows: [(String, String)] = [("Engine", "mpv")]
        func add(_ label: String, _ name: String) {
            if let v = string(name), !v.isEmpty { rows.append((label, v)) }
        }
        if let w = string("video-params/w"), let h = string("video-params/h") { rows.append(("Resolution", "\(w)×\(h)")) }
        add("Video codec", "video-format")
        add("Decoder", "hwdec-current")
        add("Pixel format", "video-params/pixelformat")
        add("Primaries", "video-params/primaries")
        add("Transfer", "video-params/gamma")
        add("Peak", "video-params/sig-peak")
        add("DV profile", "current-tracks/video/dolby-vision-profile")
        add("Display fps", "estimated-display-fps")
        add("Dropped frames", "frame-drop-count")
        add("Decoder drops", "decoder-frame-drop-count")
        add("Audio codec", "audio-codec-name")
        add("Audio channels", "audio-params/channel-count")
        add("Cache", "demuxer-cache-duration")
        return rows
    }

    func shutdown() {
        backgroundObservers.forEach { NotificationCenter.default.removeObserver($0) }
        backgroundObservers = []
        model = nil
        guard let handle = mpv else { return }
        mpv_set_wakeup_callback(handle, nil, nil)
        // Torn down off the main thread: mpv's VO uninit can sync back to main through MetalLayer.
        let view = mpvView
        queue.async { [self] in
            mpv = nil
            mpv_terminate_destroy(handle)
            _ = view
        }
    }

    // MARK: libmpv helpers

    private func fail(_ message: String) {
        guard !failed else { return }
        failed = true
        let kind = self.kind
        report { $0.engineFailed(kind, message: message) }
    }

    private func report(_ change: @escaping (PlayerModel) -> Void) {
        model?.report(from: self, change)
    }

    private func option(_ name: String, _ value: String) {
        guard let handle = mpv else { return }
        check(mpv_set_option_string(handle, name, value))
    }

    private func property(_ name: String, _ value: String) {
        guard let handle = mpv else { return }
        check(mpv_set_property_string(handle, name, value))
    }

    private func string(_ name: String) -> String? {
        guard let handle = mpv, let c = mpv_get_property_string(handle, name) else { return nil }
        defer { mpv_free(c) }
        return String(cString: c)
    }

    private func double(_ property: mpv_event_property) -> Double? {
        guard property.format == MPV_FORMAT_DOUBLE, let data = property.data else { return nil }
        return data.assumingMemoryBound(to: Double.self).pointee
    }

    private func flag(_ property: mpv_event_property) -> Bool? {
        guard property.format == MPV_FORMAT_FLAG, let data = property.data else { return nil }
        return data.assumingMemoryBound(to: Int32.self).pointee != 0
    }

    private func command(_ handle: OpaquePointer, _ args: [String]) {
        var cArgs: [UnsafePointer<CChar>?] = args.map { UnsafePointer<CChar>(strdup($0)) }
        cArgs.append(nil)
        defer { cArgs.forEach { free(UnsafeMutablePointer(mutating: $0)) } }
        check(mpv_command(handle, &cArgs))
    }

    private func check(_ status: Int32) {
        if status < 0 { print("[mpv] API error: \(String(cString: mpv_error_string(status)))") }
    }
}
