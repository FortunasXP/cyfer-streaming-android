import UIKit

enum EngineKind: String {
    case avfoundation = "AVFoundation"
    case mpv = "mpv"
}

enum EnginePreference: String, CaseIterable, Identifiable {
    case auto = "Auto"
    case avfoundation = "AVFoundation"
    case mpv = "mpv"

    var id: String { rawValue }
}

struct MediaTrack: Identifiable, Equatable {
    let id: Int
    let title: String
    let detail: String?
}

enum DynamicRange: Equatable {
    case sdr, hdr10, hdr10Plus, hlg
    case dolbyVision(profile: Int?)
}

/// One playback backend. Both draw into their own view and report state through PlayerModel.report.
protocol PlayerEngine: AnyObject {
    var kind: EngineKind { get }
    var view: UIView { get }
    var supportsPictureInPicture: Bool { get }
    var supportsSubtitleDelay: Bool { get }

    func load(_ url: URL, start: Double)
    func play()
    func pause()
    func seek(to seconds: Double, exact: Bool)
    func setSpeed(_ speed: Float)
    func setFill(_ fill: Bool)
    func selectAudio(_ id: Int)
    /// 0 turns subtitles off.
    func selectSubtitle(_ id: Int)
    func setSubtitleDelay(_ seconds: Double)
    func startPictureInPicture()
    func stats() -> [(String, String)]
    func shutdown()
}
