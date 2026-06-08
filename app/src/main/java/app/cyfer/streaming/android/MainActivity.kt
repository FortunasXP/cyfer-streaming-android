package app.cyfer.streaming.android

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.cyfer.streaming.android.navigation.CyferApp
import app.cyfer.streaming.android.ui.theme.CyferTheme
import coil.Coil
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register an SVG-capable Coil loader so the bundled tech-badge
        // logos (assets/logos/*.svg) render anywhere AsyncImage is used.
        //
        // The default Coil disk cache is ~2% of free disk and memory cache
        // is 25% of available RAM. On a media app loading hundreds of TMDb
        // posters + Kitsu thumbnails at once the eviction churn is very
        // noticeable on first open. We bump both to a flat 256 MB on disk
        // and 25% RAM so a row of 20 thumbnails stays warm between visits.
        Coil.setImageLoader(
            ImageLoader.Builder(applicationContext)
                .components { add(SvgDecoder.Factory()) }
                .memoryCache {
                    MemoryCache.Builder(applicationContext)
                        .maxSizePercent(0.25)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(applicationContext.cacheDir.resolve("coil_image_cache"))
                        .maxSizeBytes(256L * 1024 * 1024)
                        .build()
                }
                .respectCacheHeaders(false)        // TMDb sends short max-age — override
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(true)
                .build(),
        )

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.BLACK)
        )
        // Restore any pinned downloads in the background — re-adds their
        // magnets to the engine + starts the live-progress poller.
        app.cyfer.streaming.android.data.downloads.DownloadsCoordinator.ensureStarted(applicationContext)

        // Ensure notification channels exist, then schedule the periodic
        // episode-air worker. ExistingPeriodicWorkPolicy.KEEP means we
        // won't trample an in-flight schedule on app restart.
        app.cyfer.streaming.android.data.notifications.NotificationChannels.ensure(applicationContext)
        app.cyfer.streaming.android.data.notifications.EpisodeNotificationWorker.schedule(applicationContext)

        setContent {
            CyferTheme {
                CyferApp()
            }
        }
    }

    // Auto-PiP on Home press was triggering PiP for the *main app* in
    // some cases (e.g. the activity was already foregrounded by the home
    // button while PlayerActiveState was racing the DisposableEffect on
    // Back press). We now require an explicit tap on the PiP button in
    // the player chrome — matches YouTube's "Background play"-style
    // explicit flow and avoids surprise mini-windows from Home/Recents.
}
