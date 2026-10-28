import SwiftUI
import UIKit

/// Port of PlayerGestureSurface. Each half: tap toggles the controls, double tap skips 10s
/// (repeat taps add up), a vertical drag sets brightness on the left and volume on the right.
struct GestureSurface: View {
    let onToggle: () -> Void
    let onSkip: (Double) -> Void

    @State private var hud: Hud?
    @State private var dragStart: Float?
    @State private var dragIsVertical: Bool?

    enum Side { case back, forward }

    struct Hud: Equatable {
        enum Kind { case brightness, volume, skipBack, skipForward }
        let kind: Kind
        let value: Float
        let amount: Int
        let token: UUID
    }

    var body: some View {
        GeometryReader { geo in
            HStack(spacing: 0) {
                zone(.back, height: geo.size.height)
                zone(.forward, height: geo.size.height)
            }
            .overlay {
                if let hud {
                    GestureHud(hud: hud, size: geo.size)
                        .allowsHitTesting(false)
                        .transition(.opacity)
                }
            }
        }
        .task(id: hud?.token) {
            guard let current = hud else { return }
            let skip = current.kind == .skipBack || current.kind == .skipForward
            try? await Task.sleep(nanoseconds: skip ? 800_000_000 : 700_000_000)
            guard !Task.isCancelled, hud?.token == current.token else { return }
            withAnimation(.easeOut(duration: 0.2)) { hud = nil }
        }
    }

    private func zone(_ side: Side, height: CGFloat) -> some View {
        Color.clear
            .contentShape(Rectangle())
            .gesture(
                TapGesture(count: 2).onEnded { skip(side) }
                    .exclusively(before: TapGesture().onEnded { onToggle() })
            )
            .simultaneousGesture(
                DragGesture(minimumDistance: 12)
                    .onChanged { value in drag(side, value, height: height) }
                    .onEnded { _ in
                        dragStart = nil
                        dragIsVertical = nil
                    }
            )
    }

    private func skip(_ side: Side) {
        let kind: Hud.Kind = side == .back ? .skipBack : .skipForward
        let amount = (hud?.kind == kind ? hud?.amount ?? 0 : 0) + 10
        onSkip(side == .back ? -10 : 10)
        hud = Hud(kind: kind, value: 0, amount: amount, token: UUID())
    }

    private func drag(_ side: Side, _ value: DragGesture.Value, height: CGFloat) {
        if dragIsVertical == nil {
            dragIsVertical = abs(value.translation.height) > abs(value.translation.width)
        }
        guard dragIsVertical == true else { return }
        // A full-height swipe sweeps the whole range; up is more.
        let delta = Float(-value.translation.height / max(height, 1))
        switch side {
        case .back:
            let start = dragStart ?? Float(UIScreen.main.brightness)
            dragStart = start
            let next = min(max(start + delta, 0.01), 1)
            UIScreen.main.brightness = CGFloat(next)
            hud = Hud(kind: .brightness, value: next, amount: 0, token: UUID())
        case .forward:
            let start = dragStart ?? SystemVolume.current
            dragStart = start
            let next = min(max(start + delta, 0), 1)
            SystemVolume.set(next)
            hud = Hud(kind: .volume, value: next, amount: 0, token: UUID())
        }
    }
}

private struct GestureHud: View {
    let hud: GestureSurface.Hud
    let size: CGSize

    var body: some View {
        if hud.kind == .skipBack || hud.kind == .skipForward {
            skipBubble(back: hud.kind == .skipBack)
        } else {
            levelBar(volume: hud.kind == .volume)
        }
    }

    /// A soft half-disc on the tapped side, YouTube-style.
    private func skipBubble(back: Bool) -> some View {
        let w = size.width * 0.42
        return ZStack {
            Capsule().fill(Color.white.opacity(0.12))
            VStack(spacing: 4) {
                Image(systemName: back ? "backward.fill" : "forward.fill").font(.system(size: 26, weight: .semibold))
                Text("\(back ? "−" : "+")\(hud.amount) seconds").font(.system(size: 14, weight: .bold))
            }
            .foregroundColor(.white)
            .offset(x: back ? w * 0.12 : -w * 0.12)
        }
        .frame(width: w, height: size.height * 1.2)
        .offset(x: back ? -w * 0.25 : w * 0.25)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: back ? .leading : .trailing)
    }

    /// Brightness on the left edge, volume on the right: the side your thumb is on.
    private func levelBar(volume: Bool) -> some View {
        let icon: String
        if volume {
            icon = hud.value <= 0.001 ? "speaker.slash.fill" : "speaker.wave.2.fill"
        } else {
            icon = hud.value < 0.5 ? "sun.min.fill" : "sun.max.fill"
        }
        let barHeight: CGFloat = 120
        return VStack(spacing: 10) {
            Image(systemName: icon).font(.system(size: 15, weight: .semibold))
            ZStack(alignment: .bottom) {
                Capsule().fill(Color.white.opacity(0.25))
                Capsule().fill(Color.white).frame(height: barHeight * CGFloat(min(max(hud.value, 0), 1)))
            }
            .frame(width: 6, height: barHeight)
            Text("\(min(max(Int(hud.value * 100), 0), 100))%").font(.system(size: 11, weight: .bold))
        }
        .foregroundColor(.white)
        .frame(width: 44, height: 200)
        .background(RoundedRectangle(cornerRadius: 22).fill(Color.black.opacity(0.55)))
        .padding(.horizontal, 28)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: volume ? .trailing : .leading)
    }
}
