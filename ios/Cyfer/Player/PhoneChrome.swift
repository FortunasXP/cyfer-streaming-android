import AVFoundation
import SwiftUI

/// The phone's playback chrome, as on Android: title and utilities up top, transport in the middle, labelled tools below.
struct PhoneChrome: View {
    @ObservedObject var model: PlayerModel
    @Binding var isSeeking: Bool
    @Binding var seekPosition: Double
    let statsOn: Bool
    let onBack: () -> Void
    let onToggleStats: () -> Void
    let onLock: () -> Void
    let onOpenTracks: () -> Void
    let onInteract: () -> Void

    private var shownPosition: Double { isSeeking ? seekPosition : model.position }

    var body: some View {
        ZStack {
            VStack(spacing: 0) {
                LinearGradient(colors: [Color.black.opacity(0.6), .clear], startPoint: .top, endPoint: .bottom)
                    .frame(height: 120)
                Spacer(minLength: 0)
                LinearGradient(colors: [.clear, Color.black.opacity(0.78)], startPoint: .top, endPoint: .bottom)
                    .frame(height: 190)
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)

            VStack(spacing: 0) {
                topBar
                Spacer(minLength: 0)
                bottomBar
            }

            transport
        }
    }

    // MARK: Top: title, then the utilities

    private var topBar: some View {
        HStack(spacing: 12) {
            GlassIconButton(size: 40, action: onBack) {
                Image(systemName: "chevron.left").font(.system(size: 17, weight: .semibold))
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(model.title.isEmpty ? "Playing" : model.title)
                    .font(.system(size: 17, weight: .bold))
                    .foregroundColor(.white)
                    .lineLimit(1)
                badges
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // Cyan while on; the readout itself shows once the controls fade.
            GlassIconButton(size: 40, action: onToggleStats) {
                Image(systemName: "chart.xyaxis.line")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(statsOn ? .cyferCyan : .white)
            }
            // The fill mode as words: an icon can't say which of the two you're in.
            GlassIconButton(size: 40, action: { model.toggleFill(); onInteract() }) {
                Text(model.fill ? "FILL" : "FIT").font(.system(size: 10, weight: .heavy)).tracking(0.3)
            }
            if model.engine?.supportsPictureInPicture == true {
                GlassIconButton(size: 40, action: model.startPictureInPicture) {
                    Image(systemName: "pip.enter").font(.system(size: 16, weight: .semibold))
                }
            }
            GlassIconButton(size: 40, action: onLock) {
                Image(systemName: "lock.fill").font(.system(size: 15, weight: .semibold))
            }
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 12)
    }

    @ViewBuilder private var badges: some View {
        let hdr = activeHdrTag(model.range)
        let audio = audioCodecTag(model.audioCodec)
        if hdr != nil || audio != nil {
            HStack(spacing: 8) {
                if let hdr { TechLogoBadge(tag: hdr, height: 14) }
                if let audio { TechLogoBadge(tag: audio, height: 14) }
            }
        }
    }

    // MARK: Middle: transport

    private var transport: some View {
        HStack(spacing: 56) {
            TransportButton(size: 56, filled: false, action: { model.seekRelative(-10); onInteract() }) {
                Image(systemName: "gobackward.10").font(.system(size: 28, weight: .medium))
            }
            TransportButton(size: 72, filled: true, action: { model.togglePause(); onInteract() }) {
                Image(systemName: model.paused ? "play.fill" : "pause.fill").font(.system(size: 32, weight: .semibold))
            }
            TransportButton(size: 56, filled: false, action: { model.seekRelative(10); onInteract() }) {
                Image(systemName: "goforward.10").font(.system(size: 28, weight: .medium))
            }
        }
    }

    // MARK: Bottom: scrubber and tools

    private var bottomBar: some View {
        VStack(spacing: 10) {
            HStack(spacing: 12) {
                Text(formatTime(shownPosition))
                    .font(.system(size: 12, weight: .semibold))
                    .monospacedDigit()
                    .foregroundColor(.white)
                    .frame(minWidth: 46, alignment: .leading)
                PillScrubber(
                    value: shownPosition,
                    duration: model.duration,
                    bufferedFraction: model.duration > 0 ? (model.position + model.bufferedAhead) / model.duration : 0,
                    marks: chapterMarks,
                    isSeeking: $isSeeking,
                    seekPosition: $seekPosition,
                    onCommit: { target in
                        model.seek(to: target)
                        onInteract()
                    }
                )
                Text("-" + formatTime(max(0, model.duration - shownPosition)))
                    .font(.system(size: 12, weight: .semibold))
                    .monospacedDigit()
                    .foregroundColor(.white.opacity(0.8))
                    .frame(minWidth: 52, alignment: .trailing)
            }
            HStack(spacing: 8) {
                ChromePill(icon: "captions.bubble", label: "Audio & Subtitles", action: onOpenTracks)
                SpeedPill(speed: model.speed) { v in
                    model.setSpeed(v)
                    onInteract()
                }
                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 14)
    }

    private var chapterMarks: [Double] {
        guard model.duration > 0 else { return [] }
        return model.chapters.map { $0 / model.duration }.filter { $0 > 0.01 && $0 < 0.99 }
    }
}

/// Only what is really on screen: HDR the panel can't show isn't badged.
func activeHdrTag(_ range: DynamicRange) -> TechTag? {
    guard AVPlayer.eligibleForHDRPlayback else { return nil }
    switch range {
    case .sdr: return nil
    case .hdr10: return .hdr10
    case .hdr10Plus: return .hdr10Plus
    case .hlg: return .hlg
    case .dolbyVision: return .dolbyVision
    }
}
