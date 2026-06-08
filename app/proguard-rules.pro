# Cyfer Android — R8 / ProGuard rules.
#
# Most third-party libraries we use ship their own keep rules through
# `consumer-rules.pro`, so the project list is intentionally tiny:
# - Compose, Coil, Retrofit, OkHttp, kotlinx-serialization, AndroidX
#   WorkManager / DataStore: all self-document.
# - libtorrent4j has JNI; keep its native bridge.
# - libmpv-android-lib has JNI; keep the `is.xyz.mpv.MPV` surface.

# ── kotlinx-serialization ───────────────────────────────────────
# Keep @Serializable models reachable for reflection-free codegen.
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# ── libtorrent4j JNI ────────────────────────────────────────────
-keep class org.libtorrent4j.** { *; }
-keep class com.frostwire.jlibtorrent.** { *; }

# ── libmpv (community AAR) ──────────────────────────────────────
-keep class is.xyz.mpv.** { *; }

# ── NanoHTTPD reflection ────────────────────────────────────────
-keep class fi.iki.elonen.** { *; }

# ── BuildConfig — Settings → About reads VERSION_NAME ────────────
-keep class app.cyfer.streaming.android.BuildConfig { *; }
