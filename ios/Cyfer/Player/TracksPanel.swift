import SwiftUI

/// Port of the phone's TracksPanel: a panel over the right of the picture; tapping the picture closes it.
struct TracksPanel: View {
    @ObservedObject var model: PlayerModel
    let onDismiss: () -> Void

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .trailing) {
                Color.black.opacity(0.45)
                    .ignoresSafeArea()
                    .contentShape(Rectangle())
                    .onTapGesture(perform: onDismiss)
                panel
                    .frame(width: min(470, geo.size.width * 0.62))
                    .frame(maxHeight: .infinity)
                    .background(Color.cyferPanel.ignoresSafeArea())
                    .contentShape(Rectangle())
                    .onTapGesture {}
            }
        }
    }

    private var panel: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                Text("Audio & Subtitles")
                    .font(.system(size: 18, weight: .heavy))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                GlassIconButton(size: 32, action: onDismiss) {
                    Image(systemName: "xmark").font(.system(size: 13, weight: .bold))
                }
            }
            .padding(.bottom, 12)

            HStack(alignment: .top, spacing: 14) {
                VStack(alignment: .leading, spacing: 0) {
                    ColumnLabel(text: "AUDIO")
                    if model.audioTracks.isEmpty {
                        Text("No other audio tracks")
                            .font(.system(size: 13))
                            .foregroundColor(.white.opacity(0.5))
                            .padding(.leading, 10)
                    }
                    ScrollView {
                        VStack(spacing: 2) {
                            ForEach(model.audioTracks) { t in
                                PanelRow(label: t.title, sub: t.detail, selected: t.id == model.currentAudio) {
                                    model.selectAudio(t.id)
                                }
                            }
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                VStack(alignment: .leading, spacing: 0) {
                    ColumnLabel(text: "SUBTITLES")
                    ScrollView {
                        VStack(spacing: 2) {
                            PanelRow(label: "Off", sub: nil, selected: model.currentSubtitle <= 0) {
                                model.selectSubtitle(0)
                            }
                            ForEach(model.subtitleTracks) { t in
                                PanelRow(label: t.title, sub: t.detail, selected: t.id == model.currentSubtitle) {
                                    model.selectSubtitle(t.id)
                                }
                            }
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: .infinity, alignment: .top)

            Rectangle().fill(Color.white.opacity(0.08)).frame(height: 1).padding(.vertical, 10)

            HStack(spacing: 8) {
                Spacer(minLength: 0)
                SyncStepper(
                    delay: model.subtitleDelay,
                    enabled: model.currentSubtitle > 0 && model.engine?.supportsSubtitleDelay == true,
                    onChange: model.setSubtitleDelay
                )
            }
        }
        .padding(.horizontal, 22)
        .padding(.top, 18)
        .padding(.bottom, 14)
    }
}

private struct ColumnLabel: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 11, weight: .heavy))
            .tracking(1.4)
            .foregroundColor(.white.opacity(0.5))
            .padding(.leading, 10)
            .padding(.bottom, 8)
    }
}

private struct PanelRow: View {
    let label: String
    let sub: String?
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: "checkmark")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(.white)
                    .opacity(selected ? 1 : 0)
                    .frame(width: 18)
                VStack(alignment: .leading, spacing: 1) {
                    Text(label)
                        .font(.system(size: 14, weight: selected ? .bold : .medium))
                        .foregroundColor(selected ? .white : .white.opacity(0.8))
                        .lineLimit(1)
                    if let sub, !sub.isEmpty {
                        Text(sub)
                            .font(.system(size: 11))
                            .foregroundColor(.white.opacity(0.5))
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 9)
            .background(RoundedRectangle(cornerRadius: 10).fill(Color.white.opacity(selected ? 0.1 : 0)))
            .contentShape(RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(PressableStyle())
    }
}

private struct SyncStepper: View {
    let delay: Double
    let enabled: Bool
    let onChange: (Double) -> Void

    var body: some View {
        HStack(spacing: 0) {
            step("−") { onChange(delay - 0.1) }
            Text(String(format: "Sync %+.1fs", delay).replacingOccurrences(of: "+0.0", with: "0.0"))
                .font(.system(size: 12, weight: .semibold))
                .monospacedDigit()
                .foregroundColor(.white)
                .padding(.horizontal, 2)
            step("+") { onChange(delay + 0.1) }
        }
        .padding(.horizontal, 2)
        .frame(height: 36)
        .background(Glass(shape: Capsule(), tint: 0.14))
        .opacity(enabled ? 1 : 0.4)
        .disabled(!enabled)
    }

    private func step(_ symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(symbol)
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(.white)
                .frame(width: 32, height: 32)
                .contentShape(Circle())
        }
        .buttonStyle(PressableStyle())
    }
}
