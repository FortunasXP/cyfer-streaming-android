# Cyfer for iOS (player spike)

The first iOS build: the Android phone player's chrome over two engines, AVFoundation first and
mpv (MPVKit) when AVFoundation can't open the file, the same way Android goes from ExoPlayer to mpv.
The launcher plays a pasted link or a video copied onto the phone. The shared Kotlin data layer comes later.

## Run it on an iPhone

On the Mac, once:

```bash
brew install xcodegen
git clone -b ios-spike https://github.com/FortunasXP/cyfer-streaming-android.git cyfer
```

Then each time:

```bash
cd cyfer/ios && git pull && xcodegen && open Cyfer.xcodeproj
```

1. In Xcode, select the **Cyfer** target, open **Signing & Capabilities** and pick your Apple ID team
   (Xcode › Settings › Accounts adds one; a free account works, its builds expire after 7 days).
   If Xcode says the bundle identifier is taken, change `app.cyfer.streaming.ios` to anything unique.
2. Plug the iPhone in, pick it as the run destination and press Run. The first build downloads
   MPVKit, which takes a few minutes.
3. On the phone, the first time: turn on **Settings › Privacy & Security › Developer Mode**, and trust
   the developer under **Settings › General › VPN & Device Management**.

The Run scheme builds Release, with Metal API validation off (MoltenVK trips it on HDR frames).

## Test files

Copy videos in from Finder (select the iPhone › Files › Cyfer) or the Files app; they show under
**On this iPhone**. Or paste a debrid link. The stats button (top right in the player) shows which engine
is playing and what it reports.

## CI

`.github/workflows/ios.yml` builds an unsigned Release build for a generic iPhone on every push to
`ios-spike` that touches `ios/`.
