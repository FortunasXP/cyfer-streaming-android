import AVFoundation
import AVKit
import UIKit

final class PlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

/// AVFoundation: the system's own HDR10 / Dolby Vision / Atmos path, PiP and AirPlay.
/// It can't open MKV, which is what most releases are, so PlayerModel hands those to mpv.
final class AVEngine: NSObject, PlayerEngine {
    let kind = EngineKind.avfoundation
    var view: UIView { layerView }
    var supportsPictureInPicture: Bool { pip != nil }
    var supportsSubtitleDelay: Bool { false }

    private weak var model: PlayerModel?
    private let player = AVPlayer()
    private let layerView = PlayerLayerView()
    private var pip: AVPictureInPictureController?
    private var item: AVPlayerItem?
    private var observations: [NSKeyValueObservation] = []
    private var timeObserver: Any?
    private var failObserver: NSObjectProtocol?
    private var audioGroup: AVMediaSelectionGroup?
    private var legibleGroup: AVMediaSelectionGroup?
    private var videoFacts: [(String, String)] = []
    private var startAt: Double = 0
    private var rate: Float = 1
    private var started = false
    private var failed = false

    init(model: PlayerModel) {
        self.model = model
        super.init()
        layerView.backgroundColor = .black
        layerView.playerLayer.player = player
        layerView.playerLayer.videoGravity = .resizeAspect
        if AVPictureInPictureController.isPictureInPictureSupported() {
            pip = AVPictureInPictureController(playerLayer: layerView.playerLayer)
            pip?.canStartPictureInPictureAutomaticallyFromInline = true
        }
    }

    func load(_ url: URL, start: Double) {
        startAt = start
        let asset = AVURLAsset(url: url)
        Task { [weak self] in
            // Settles "can AVFoundation open this at all" before a player item has to fail.
            let playable = (try? await asset.load(.isPlayable)) ?? false
            DispatchQueue.main.async {
                guard let engine = self else { return }
                if playable { engine.attach(asset) } else { engine.fail("AVFoundation can't open this file") }
            }
        }
    }

    private func attach(_ asset: AVURLAsset) {
        guard model != nil else { return }
        let item = AVPlayerItem(asset: asset)
        self.item = item
        observations = [
            item.observe(\.status, options: [.new]) { [weak self] item, _ in
                DispatchQueue.main.async { self?.statusChanged(item) }
            },
            player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
                let status = player.timeControlStatus
                DispatchQueue.main.async { self?.timeControlChanged(status) }
            },
        ]
        failObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main
        ) { [weak self] note in
            let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            self?.fail(error?.localizedDescription ?? "Playback stopped")
        }
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main
        ) { [weak self] time in
            self?.tick(time)
        }
        player.replaceCurrentItem(with: item)
    }

    private func statusChanged(_ item: AVPlayerItem) {
        switch item.status {
        case .readyToPlay:
            guard !started else { return }
            started = true
            if startAt > 1 {
                player.seek(to: CMTime(seconds: startAt, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
            }
            player.defaultRate = rate
            player.playImmediately(atRate: rate)
            report { $0.loading = false }
            Task { [weak self] in await self?.inspect(item) }
        case .failed:
            fail(item.error?.localizedDescription ?? "AVFoundation couldn't play this")
        default:
            break
        }
    }

    private func timeControlChanged(_ status: AVPlayer.TimeControlStatus) {
        switch status {
        case .paused: report { $0.paused = true; $0.buffering = false }
        case .waitingToPlayAtSpecifiedRate: report { $0.paused = false; $0.buffering = true }
        case .playing: report { $0.paused = false; $0.buffering = false }
        @unknown default: break
        }
    }

    private func tick(_ time: CMTime) {
        guard let item else { return }
        let now = time.seconds.isFinite ? time.seconds : 0
        let length = item.duration.seconds.isFinite ? item.duration.seconds : 0
        var ahead = 0.0
        for value in item.loadedTimeRanges {
            let r = value.timeRangeValue
            if r.start.seconds <= now + 1, r.end.seconds > now { ahead = max(ahead, r.end.seconds - now) }
        }
        report { m in
            m.position = now
            m.duration = length
            m.bufferedAhead = ahead
        }
    }

    /// Tracks, codec and dynamic range once the item is ready.
    private func inspect(_ item: AVPlayerItem) async {
        let asset = item.asset
        let audio = (try? await asset.loadMediaSelectionGroup(for: .audible)) ?? nil
        let legible = (try? await asset.loadMediaSelectionGroup(for: .legible)) ?? nil

        var range = DynamicRange.sdr
        var facts: [(String, String)] = []
        var audioCodec: String?
        for track in item.tracks {
            guard let assetTrack = track.assetTrack else { continue }
            let descriptions = (try? await assetTrack.load(.formatDescriptions)) ?? []
            guard let desc = descriptions.first else { continue }
            let fourCC = Self.fourCC(CMFormatDescriptionGetMediaSubType(desc))
            if assetTrack.mediaType == .video {
                let transfer = CMFormatDescriptionGetExtension(desc, extensionKey: kCMFormatDescriptionExtension_TransferFunction) as? String
                if ["dvh1", "dvhe", "dva1", "dvav"].contains(fourCC) {
                    range = .dolbyVision(profile: nil)
                } else if transfer == (kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ as String) {
                    range = .hdr10
                } else if transfer == (kCMFormatDescriptionTransferFunction_ITU_R_2100_HLG as String) {
                    range = .hlg
                }
                facts.append(("Video codec", fourCC))
                if let transfer { facts.append(("Transfer", transfer)) }
            } else if assetTrack.mediaType == .audio {
                audioCodec = Self.audioCodecName(fourCC)
                facts.append(("Audio codec", fourCC))
            }
        }

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.audioGroup = audio
            self.legibleGroup = legible
            self.videoFacts = facts
            let audioTracks = (audio?.options ?? []).enumerated().map { index, option in
                MediaTrack(id: index + 1, title: option.displayName, detail: Self.optionCodec(option))
            }
            let subtitleTracks = (legible?.options ?? []).enumerated().map { index, option in
                MediaTrack(
                    id: index + 1,
                    title: option.displayName,
                    detail: option.hasMediaCharacteristic(.containsOnlyForcedSubtitles) ? "Forced" : nil
                )
            }
            let selection = item.currentMediaSelection
            let currentAudio = audio.flatMap { g in selection.selectedMediaOption(in: g).flatMap { g.options.firstIndex(of: $0) } }
            let currentSub = legible.flatMap { g in selection.selectedMediaOption(in: g).flatMap { g.options.firstIndex(of: $0) } }
            self.report { m in
                m.audioTracks = audioTracks
                m.subtitleTracks = subtitleTracks
                m.currentAudio = currentAudio.map { $0 + 1 }
                m.currentSubtitle = currentSub.map { $0 + 1 } ?? 0
                m.range = range
                m.audioCodec = audioCodec
            }
        }
    }

    private static func fourCC(_ code: FourCharCode) -> String {
        let bytes = [24, 16, 8, 0].map { UInt8((code >> $0) & 0xFF) }
        return String(bytes: bytes, encoding: .ascii)?.trimmingCharacters(in: .whitespaces) ?? "\(code)"
    }

    /// FFmpeg-style names, so audioCodecTag reads both engines the same way.
    private static func audioCodecName(_ fourCC: String) -> String {
        switch fourCC {
        case "ec-3": return "eac3"
        case "ac-3": return "ac3"
        case "mp4a", "aac ": return "aac"
        case "fLaC": return "flac"
        case "Opus": return "opus"
        case "lpcm": return "pcm"
        default: return fourCC
        }
    }

    private static func optionCodec(_ option: AVMediaSelectionOption) -> String? {
        guard let code = option.mediaSubTypes.first?.uint32Value else { return nil }
        return audioCodecTag(audioCodecName(fourCC(code)))?.label
    }

    private func fail(_ message: String) {
        guard !failed else { return }
        failed = true
        let kind = self.kind
        report { $0.engineFailed(kind, message: message) }
    }

    private func report(_ change: @escaping (PlayerModel) -> Void) {
        model?.report(from: self, change)
    }

    func play() {
        player.playImmediately(atRate: rate)
    }

    func pause() {
        player.pause()
    }

    func seek(to seconds: Double, exact: Bool) {
        let time = CMTime(seconds: seconds, preferredTimescale: 600)
        let tolerance = exact ? CMTime.zero : CMTime(seconds: 2, preferredTimescale: 600)
        player.seek(to: time, toleranceBefore: tolerance, toleranceAfter: tolerance)
    }

    func setSpeed(_ speed: Float) {
        rate = speed
        player.defaultRate = speed
        if player.timeControlStatus != .paused { player.rate = speed }
    }

    func setFill(_ fill: Bool) {
        layerView.playerLayer.videoGravity = fill ? .resizeAspectFill : .resizeAspect
    }

    func selectAudio(_ id: Int) {
        guard let group = audioGroup, group.options.indices.contains(id - 1) else { return }
        item?.select(group.options[id - 1], in: group)
    }

    func selectSubtitle(_ id: Int) {
        guard let group = legibleGroup else { return }
        if id == 0 {
            item?.select(nil, in: group)
        } else if group.options.indices.contains(id - 1) {
            item?.select(group.options[id - 1], in: group)
        }
    }

    func setSubtitleDelay(_ seconds: Double) {}

    func startPictureInPicture() {
        pip?.startPictureInPicture()
    }

    func stats() -> [(String, String)] {
        var rows: [(String, String)] = [("Engine", "AVFoundation")]
        if let item {
            let size = item.presentationSize
            if size.width > 0 { rows.append(("Resolution", "\(Int(size.width))×\(Int(size.height))")) }
            rows += videoFacts
            if let event = item.accessLog()?.events.last {
                if event.indicatedBitrate > 0 { rows.append(("Bitrate", Self.mbps(event.indicatedBitrate))) }
                if event.observedBitrate > 0 { rows.append(("Throughput", Self.mbps(event.observedBitrate))) }
                rows.append(("Dropped frames", "\(event.numberOfDroppedVideoFrames)"))
            }
        }
        rows.append(("HDR display", AVPlayer.eligibleForHDRPlayback ? "Yes" : "No"))
        return rows
    }

    private static func mbps(_ bitsPerSecond: Double) -> String {
        String(format: "%.1f Mb/s", bitsPerSecond / 1_000_000)
    }

    func shutdown() {
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        observations.forEach { $0.invalidate() }
        observations = []
        if let failObserver { NotificationCenter.default.removeObserver(failObserver) }
        failObserver = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        model = nil
    }
}
