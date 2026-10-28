import SwiftUI
import UniformTypeIdentifiers

/// Test launcher until the shared data layer lands: play a link or a file copied onto the phone.
struct LaunchView: View {
    @State private var link = ""
    @State private var preference: EnginePreference = .auto
    @State private var files: [URL] = []
    @State private var recents: [String] = UserDefaults.standard.stringArray(forKey: Self.recentsKey) ?? []
    @State private var playing: PlayRequest?
    @Environment(\.scenePhase) private var scenePhase

    private static let recentsKey = "recentLinks"
    private static let videoExtensions: Set<String> = ["mkv", "mp4", "m4v", "mov", "ts", "m2ts", "webm", "avi", "m3u8"]

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 10) {
                        TextField("Stream link", text: $link)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                            .submitLabel(.go)
                            .onSubmit { play(link) }
                        PasteButton(payloadType: String.self) { strings in
                            DispatchQueue.main.async { link = strings.first?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "" }
                        }
                        .labelStyle(.iconOnly)
                        .buttonBorderShape(.capsule)
                    }
                    Picker("Engine", selection: $preference) {
                        ForEach(EnginePreference.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    Button {
                        play(link)
                    } label: {
                        Label("Play", systemImage: "play.fill")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(.black)
                            .frame(maxWidth: .infinity)
                            .frame(height: 44)
                            .background(Capsule().fill(Color.white))
                    }
                    .buttonStyle(PressableStyle())
                    .disabled(Self.url(from: link) == nil)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                } header: {
                    Text("Play a link")
                } footer: {
                    Text("Auto tries AVFoundation first and hands over to mpv when it can't open the file, the way the Android app goes from ExoPlayer to mpv.")
                }

                Section {
                    if files.isEmpty {
                        Text("Nothing here yet").foregroundColor(.secondary)
                    }
                    ForEach(files, id: \.self) { file in
                        Button {
                            playing = PlayRequest(url: file, title: file.deletingPathExtension().lastPathComponent, preference: preference)
                        } label: {
                            Label(file.lastPathComponent, systemImage: "film")
                                .foregroundColor(.white)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                    }
                } header: {
                    Text("On this iPhone")
                } footer: {
                    Text("Copy videos in from Finder (select the iPhone, then Files › Cyfer) or the Files app.")
                }

                if !recents.isEmpty {
                    Section("Recent") {
                        ForEach(recents, id: \.self) { recent in
                            Button {
                                play(recent)
                            } label: {
                                Text(recent).foregroundColor(.white).lineLimit(1).truncationMode(.middle)
                            }
                        }
                        .onDelete { offsets in
                            recents.remove(atOffsets: offsets)
                            UserDefaults.standard.set(recents, forKey: Self.recentsKey)
                        }
                    }
                }
            }
            .navigationTitle("Cyfer")
        }
        .fullScreenCover(item: $playing) { request in
            PlayerScreen(request: request)
        }
        .onAppear(perform: refreshFiles)
        .onChange(of: scenePhase) { phase in
            if phase == .active { refreshFiles() }
        }
    }

    private func play(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = Self.url(from: trimmed) else { return }
        recents.removeAll { $0 == trimmed }
        recents.insert(trimmed, at: 0)
        recents = Array(recents.prefix(10))
        UserDefaults.standard.set(recents, forKey: Self.recentsKey)
        let name = url.deletingPathExtension().lastPathComponent.removingPercentEncoding ?? url.lastPathComponent
        playing = PlayRequest(url: url, title: name.isEmpty ? (url.host ?? "Stream") : name, preference: preference)
    }

    private static func url(from text: String) -> URL? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed), let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme) else { return nil }
        return url
    }

    private func refreshFiles() {
        guard let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let found = (try? FileManager.default.contentsOfDirectory(at: documents, includingPropertiesForKeys: nil)) ?? []
        files = found
            .filter { Self.videoExtensions.contains($0.pathExtension.lowercased()) }
            .sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
    }
}
