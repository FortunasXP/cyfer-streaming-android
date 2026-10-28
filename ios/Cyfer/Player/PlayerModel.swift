import AVFoundation
import SwiftUI

/// Player state the chrome draws from. Engines write to it only through report(from:_:), on the main queue.
final class PlayerModel: ObservableObject {
    let url: URL
    let title: String
    let preference: EnginePreference

    @Published private(set) var engine: PlayerEngine?
    @Published private(set) var engineKind: EngineKind = .avfoundation
    @Published var loading = true
    @Published var buffering = false
    @Published var paused = false
    @Published var position: Double = 0
    @Published var duration: Double = 0
    @Published var bufferedAhead: Double = 0
    @Published var chapters: [Double] = []
    @Published var speed: Float = 1
    @Published var fill = false
    @Published var audioTracks: [MediaTrack] = []
    @Published var subtitleTracks: [MediaTrack] = []
    @Published var currentAudio: Int?
    @Published var currentSubtitle = 0
    @Published var subtitleDelay: Double = 0
    @Published var range: DynamicRange = .sdr
    @Published var audioCodec: String?
    @Published var errorMessage: String?
    @Published var note: String?

    private var noteToken = 0

    init(url: URL, title: String, preference: EnginePreference) {
        self.url = url
        self.title = title
        self.preference = preference
    }

    func start() {
        guard engine == nil else { return }
        try? AVAudioSession.sharedInstance().setActive(true)
        switch preference {
        case .mpv: startEngine(.mpv, at: 0)
        case .avfoundation: startEngine(.avfoundation, at: 0)
        case .auto: startEngine(Self.avfoundationMayOpen(url) ? .avfoundation : .mpv, at: 0)
        }
    }

    func stop() {
        engine?.shutdown()
        engine = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// Containers AVFoundation never opens go straight to mpv instead of failing first.
    static func avfoundationMayOpen(_ url: URL) -> Bool {
        !["mkv", "avi", "webm", "wmv", "flv", "ogm", "rmvb", "m2ts"].contains(url.pathExtension.lowercased())
    }

    private func startEngine(_ kind: EngineKind, at start: Double) {
        engine?.shutdown()
        let next: PlayerEngine = kind == .mpv ? MpvEngine(model: self) : AVEngine(model: self)
        engineKind = kind
        loading = true
        buffering = false
        audioTracks = []
        subtitleTracks = []
        currentAudio = nil
        currentSubtitle = 0
        subtitleDelay = 0
        range = .sdr
        audioCodec = nil
        chapters = []
        engine = next
        next.load(url, start: start)
        next.setFill(fill)
        if speed != 1 { next.setSpeed(speed) }
    }

    /// Engines call this from any thread; updates from an engine that has been replaced are dropped.
    func report(from source: PlayerEngine, _ change: @escaping (PlayerModel) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.engine === source else { return }
            change(self)
        }
    }

    /// AVFoundation first, mpv when it can't: the iOS twin of Android's ExoPlayer-first handoff.
    func engineFailed(_ kind: EngineKind, message: String) {
        if kind == .avfoundation && preference == .auto {
            show(note: "Switched to mpv · \(message)")
            startEngine(.mpv, at: position)
        } else {
            errorMessage = message
            loading = false
            buffering = false
        }
    }

    func show(note text: String) {
        noteToken += 1
        let token = noteToken
        note = text
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in
            guard let self, self.noteToken == token else { return }
            self.note = nil
        }
    }

    func togglePause() {
        if paused { engine?.play() } else { engine?.pause() }
        paused.toggle()
    }

    func seekRelative(_ seconds: Double) {
        let target = min(max(0, position + seconds), duration > 0 ? duration : .greatestFiniteMagnitude)
        position = target
        engine?.seek(to: target, exact: false)
    }

    func seek(to seconds: Double) {
        position = seconds
        engine?.seek(to: seconds, exact: true)
    }

    func setSpeed(_ value: Float) {
        speed = value
        engine?.setSpeed(value)
    }

    func toggleFill() {
        fill.toggle()
        engine?.setFill(fill)
    }

    func selectAudio(_ id: Int) {
        currentAudio = id
        engine?.selectAudio(id)
    }

    func selectSubtitle(_ id: Int) {
        currentSubtitle = id
        engine?.selectSubtitle(id)
    }

    func setSubtitleDelay(_ seconds: Double) {
        subtitleDelay = (seconds * 10).rounded() / 10
        engine?.setSubtitleDelay(subtitleDelay)
    }

    func startPictureInPicture() {
        engine?.startPictureInPicture()
    }
}
