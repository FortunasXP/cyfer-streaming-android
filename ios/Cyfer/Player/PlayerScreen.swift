import SwiftUI
import UIKit

struct PlayRequest: Identifiable {
    let id = UUID()
    let url: URL
    let title: String
    let preference: EnginePreference
}

struct PlayerScreen: View {
    @StateObject private var model: PlayerModel
    @Environment(\.dismiss) private var dismiss

    @State private var controlsVisible = true
    @State private var locked = false
    @State private var unlockHint = false
    @State private var unlockToken = 0
    @State private var isSeeking = false
    @State private var seekPosition: Double = 0
    @State private var tracksOpen = false
    @State private var statsOn = false
    @State private var interaction = 0
    @State private var startBrightness: CGFloat?

    init(request: PlayRequest) {
        _model = StateObject(wrappedValue: PlayerModel(url: request.url, title: request.title, preference: request.preference))
    }

    private var chromeShown: Bool { controlsVisible && !model.loading && !locked }

    private struct HideKey: Equatable {
        let visible: Bool
        let seeking: Bool
        let loading: Bool
        let panel: Bool
        let tick: Int
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            EngineHost(engine: model.engine).ignoresSafeArea()

            // Disabled when locked: the lock overlay handles taps so it can offer Unlock, but nothing else.
            if !locked {
                GestureSurface(
                    onToggle: { controlsVisible.toggle() },
                    onSkip: { model.seekRelative($0) }
                )
                .ignoresSafeArea()
            }

            if statsOn && !chromeShown && !tracksOpen {
                StatsReadout(engine: model.engine)
                    .padding(24)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .allowsHitTesting(false)
            }

            if model.loading || model.buffering {
                LoadingRing(size: 56).allowsHitTesting(false)
            }

            PhoneChrome(
                model: model,
                isSeeking: $isSeeking,
                seekPosition: $seekPosition,
                statsOn: statsOn,
                onBack: { dismiss() },
                onToggleStats: { statsOn.toggle(); interaction += 1 },
                onLock: {
                    locked = true
                    controlsVisible = false
                },
                onOpenTracks: {
                    tracksOpen = true
                    controlsVisible = false
                },
                onInteract: { interaction += 1 }
            )
            .opacity(chromeShown ? 1 : 0)
            .allowsHitTesting(chromeShown)

            // While loading there's no chrome, but there's always a way out.
            if model.loading && !locked {
                GlassIconButton(size: 40, action: { dismiss() }) {
                    Image(systemName: "chevron.left").font(.system(size: 17, weight: .semibold))
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }

            VStack(spacing: 8) {
                if let note = model.note { NoteToast(text: note).transition(.opacity) }
                if let error = model.errorMessage {
                    PlaybackErrorPill(message: error) { model.errorMessage = nil }.transition(.opacity)
                }
            }
            .padding(.top, 64)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)

            if locked { lockOverlay }

            if tracksOpen {
                TracksPanel(model: model) { tracksOpen = false }
                    .transition(.move(edge: .trailing))
            }

            SystemVolumeView()
                .frame(width: 1, height: 1)
                .allowsHitTesting(false)
        }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .animation(.easeInOut(duration: 0.2), value: chromeShown)
        .animation(.easeInOut(duration: 0.25), value: tracksOpen)
        .animation(.easeInOut(duration: 0.2), value: model.note)
        .animation(.easeInOut(duration: 0.2), value: model.errorMessage)
        .task(id: HideKey(visible: controlsVisible, seeking: isSeeking, loading: model.loading, panel: tracksOpen, tick: interaction)) {
            guard controlsVisible, !isSeeking, !model.loading, !tracksOpen else { return }
            try? await Task.sleep(nanoseconds: 4_500_000_000)
            guard !Task.isCancelled else { return }
            controlsVisible = false
        }
        .onAppear {
            Orientation.lockLandscape()
            startBrightness = UIScreen.main.brightness
            UIApplication.shared.isIdleTimerDisabled = true
            model.start()
        }
        .onDisappear {
            model.stop()
            UIApplication.shared.isIdleTimerDisabled = false
            if let startBrightness { UIScreen.main.brightness = startBrightness }
            Orientation.unlock()
        }
    }

    /// Swallows every touch so a pocket or a grip can't reach the player; a tap offers Unlock for 2.5s.
    private var lockOverlay: some View {
        Color.clear
            .contentShape(Rectangle())
            .ignoresSafeArea()
            .onTapGesture { showUnlockHint() }
            .overlay {
                if unlockHint {
                    Button {
                        locked = false
                        unlockHint = false
                        controlsVisible = true
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "lock.open.fill").font(.system(size: 16, weight: .semibold))
                            Text("Tap to unlock").font(.system(size: 15, weight: .semibold))
                        }
                        .foregroundColor(.white)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 10)
                        .background(Glass(shape: Capsule(), tint: 0.18))
                    }
                    .buttonStyle(PressableStyle())
                    .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.2), value: unlockHint)
    }

    private func showUnlockHint() {
        unlockToken += 1
        let token = unlockToken
        unlockHint = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
            if unlockToken == token { unlockHint = false }
        }
    }
}
