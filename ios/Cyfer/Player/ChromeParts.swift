import AVFoundation
import MediaPlayer
import SwiftUI

/// Android's white glass, over a blur.
struct Glass<S: Shape>: View {
    let shape: S
    var tint: Double = 0.12

    var body: some View {
        ZStack {
            shape.fill(.ultraThinMaterial)
            shape.fill(Color.white.opacity(tint))
        }
    }
}

struct PressableStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.6 : 1)
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
    }
}

struct GlassIconButton<Content: View>: View {
    let size: CGFloat
    var enabled = true
    let action: () -> Void
    @ViewBuilder let content: () -> Content

    var body: some View {
        Button(action: action) {
            content()
                .frame(width: size, height: size)
                .background(Glass(shape: Circle(), tint: enabled ? 0.12 : 0.05))
                .contentShape(Circle())
        }
        .buttonStyle(PressableStyle())
        .foregroundColor(.white)
        .disabled(!enabled)
    }
}

struct TransportButton<Content: View>: View {
    let size: CGFloat
    let filled: Bool
    let action: () -> Void
    @ViewBuilder let content: () -> Content

    var body: some View {
        Button(action: action) {
            content()
                .frame(width: size, height: size)
                .background(Circle().fill(Color.white.opacity(filled ? 0.18 : 0)))
                .contentShape(Circle())
        }
        .buttonStyle(PressableStyle())
        .foregroundColor(.white)
    }
}

struct ChromePill: View {
    let icon: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) { PillLabel(icon: icon, label: label) }
            .buttonStyle(PressableStyle())
    }
}

struct PillLabel: View {
    let icon: String
    let label: String

    var body: some View {
        HStack(spacing: 7) {
            Image(systemName: icon).font(.system(size: 14, weight: .semibold))
            Text(label).font(.system(size: 13, weight: .semibold)).lineLimit(1)
        }
        .foregroundColor(.white)
        .padding(.leading, 11)
        .padding(.trailing, 14)
        .frame(height: 36)
        .background(Glass(shape: Capsule(), tint: 0.14))
        .contentShape(Capsule())
    }
}

func speedText(_ v: Float) -> String {
    if v == 1 { return "1×" }
    var s = String(format: "%.2f", v)
    while s.hasSuffix("0") { s.removeLast() }
    if s.hasSuffix(".") { s.removeLast() }
    return s + "×"
}

/// The speed pill opens the system menu rather than Android's dropdown.
struct SpeedPill: View {
    let speed: Float
    let onSpeed: (Float) -> Void
    private let speeds: [Float] = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]

    var body: some View {
        Menu {
            ForEach(speeds, id: \.self) { v in
                Button {
                    onSpeed(v)
                } label: {
                    if v == speed {
                        Label(speedText(v), systemImage: "checkmark")
                    } else {
                        Text(speedText(v))
                    }
                }
            }
        } label: {
            PillLabel(icon: "speedometer", label: speedText(speed))
        }
    }
}

/// Port of ApplePillScrubber: thin track, dim buffered fill, a dot riding the played edge.
struct PillScrubber: View {
    let value: Double
    let duration: Double
    let bufferedFraction: Double
    let marks: [Double]
    @Binding var isSeeking: Bool
    @Binding var seekPosition: Double
    let onCommit: (Double) -> Void

    var body: some View {
        GeometryReader { geo in
            let width = max(geo.size.width, 1)
            let fraction = duration > 0 ? min(max(value / duration, 0), 1) : 0
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.22)).frame(height: 3)
                Capsule().fill(Color.white.opacity(0.45)).frame(width: width * min(max(bufferedFraction, 0), 1), height: 3)
                Capsule().fill(Color.white).frame(width: width * fraction, height: 3)
                ForEach(marks, id: \.self) { mark in
                    Rectangle().fill(Color.black.opacity(0.55)).frame(width: 2, height: 3).offset(x: width * mark - 1)
                }
                Circle().fill(Color.white).frame(width: 10, height: 10).offset(x: width * fraction - 5)
                // No preview frames yet: just the time, riding the thumb.
                Text(formatTime(value))
                    .font(.system(size: 14, weight: .bold))
                    .monospacedDigit()
                    .foregroundColor(.white)
                    .frame(width: 64)
                    .padding(.vertical, 4)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Color.black.opacity(0.7)))
                    .offset(x: (width - 64) * fraction, y: -34)
                    .opacity(isSeeking ? 1 : 0)
                    .animation(.easeOut(duration: 0.12), value: isSeeking)
            }
            .frame(width: width, height: 22)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { g in
                        isSeeking = true
                        seekPosition = Double(min(max(g.location.x / width, 0), 1)) * duration
                    }
                    .onEnded { g in
                        let target = Double(min(max(g.location.x / width, 0), 1)) * duration
                        seekPosition = target
                        isSeeking = false
                        onCommit(target)
                    }
            )
        }
        .frame(height: 22)
    }
}

struct LoadingRing: View {
    var size: CGFloat = 56
    @State private var spinning = false

    var body: some View {
        ZStack {
            Circle().stroke(Color.white.opacity(0.16), lineWidth: 3)
            Circle()
                .trim(from: 0, to: 96.0 / 360.0)
                .stroke(Color.white, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .rotationEffect(.degrees(spinning ? 360 : 0))
        }
        .frame(width: size, height: size)
        .onAppear {
            withAnimation(.linear(duration: 1.1).repeatForever(autoreverses: false)) { spinning = true }
        }
    }
}

struct PlaybackErrorPill: View {
    let message: String
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Circle().fill(Color.cyferRed).frame(width: 8, height: 8)
            Text(message).font(.system(size: 13, weight: .medium)).foregroundColor(.white)
            Button(action: onDismiss) {
                Image(systemName: "xmark").font(.system(size: 12, weight: .bold)).foregroundColor(.white)
                    .frame(width: 26, height: 26)
                    .background(Circle().fill(Color.white.opacity(0.12)))
            }
        }
        .padding(.leading, 14)
        .padding(.trailing, 6)
        .padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 18).fill(Color.black.opacity(0.88)))
        .padding(.horizontal, 24)
    }
}

/// The StreamHealthToast look, for engine handoff notes.
struct NoteToast: View {
    let text: String

    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(Color.cyferCyan).frame(width: 8, height: 8)
            Text(text).font(.system(size: 13, weight: .semibold)).foregroundColor(.white)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 20).fill(Color.black.opacity(0.78)))
    }
}

struct StatsReadout: View {
    let engine: PlayerEngine?

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            let rows = engine?.stats() ?? []
            VStack(alignment: .leading, spacing: 3) {
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Text(row.0).foregroundColor(.white.opacity(0.6)).frame(width: 112, alignment: .leading)
                        Text(row.1).foregroundColor(.white)
                    }
                }
            }
            .font(.system(size: 11, weight: .medium, design: .monospaced))
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color.black.opacity(0.6)))
        }
    }
}

/// Hosts whichever engine is current; swaps the view on a handoff.
struct EngineHost: UIViewRepresentable {
    let engine: PlayerEngine?

    func makeUIView(context: Context) -> UIView {
        let container = UIView()
        container.backgroundColor = .black
        return container
    }

    func updateUIView(_ container: UIView, context: Context) {
        guard let view = engine?.view else {
            container.subviews.forEach { $0.removeFromSuperview() }
            return
        }
        guard view.superview !== container else { return }
        container.subviews.forEach { $0.removeFromSuperview() }
        view.frame = container.bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        container.addSubview(view)
    }
}

/// In the hierarchy so the volume swipe can drive it and the system volume HUD stays away.
struct SystemVolumeView: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView {
        let view = MPVolumeView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
        view.alpha = 0.01
        SystemVolume.view = view
        return view
    }

    func updateUIView(_ uiView: MPVolumeView, context: Context) {}
}

enum SystemVolume {
    static weak var view: MPVolumeView?

    static var current: Float { AVAudioSession.sharedInstance().outputVolume }

    static func set(_ value: Float) {
        guard let slider = view?.subviews.compactMap({ $0 as? UISlider }).first else { return }
        slider.setValue(value, animated: false)
        slider.sendActions(for: .valueChanged)
    }
}
