import AVFoundation
import SwiftUI
import UIKit

@main
struct CyferApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
    }

    var body: some Scene {
        WindowGroup {
            LaunchView()
                .preferredColorScheme(.dark)
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        Orientation.allowed
    }
}

/// The player is landscape-only, like the Android one; everything else turns freely.
enum Orientation {
    static var allowed: UIInterfaceOrientationMask = .all

    static func lockLandscape() { apply(.landscape) }

    static func unlock() { apply(.all, prefer: .portrait) }

    private static func apply(_ mask: UIInterfaceOrientationMask, prefer: UIInterfaceOrientationMask? = nil) {
        allowed = mask
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        scene?.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        scene?.requestGeometryUpdate(.iOS(interfaceOrientations: prefer ?? mask)) { _ in }
    }
}
