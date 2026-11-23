package io.github.pranavragji.folio

import android.app.Application
import androidx.room.Room
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.github.pranavragji.folio.data.FolioDb
import io.github.pranavragji.folio.data.Prefs
import io.github.pranavragji.folio.library.Scanner
import io.github.pranavragji.folio.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Hand-wired singletons. One process, a handful of services: no DI framework needed. */
class FolioApp : Application(), SingletonImageLoader.Factory {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(Cache(File(cacheDir, "http"), 100L shl 20))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                chain.proceed(
                    if (req.header("User-Agent") != null) req
                    else req.newBuilder().header("User-Agent", "Folio/${BuildConfig.VERSION_NAME}").build()
                )
            }
            .build()
    }
    val db: FolioDb by lazy { Room.databaseBuilder(this, FolioDb::class.java, "folio.db").build() }
    val prefs by lazy { Prefs(this) }
    val scanner by lazy { Scanner(this) }
    val sync by lazy { SyncManager(this) }

    /** Books downloaded from sources or the cloud land here; Scanner always scans it. */
    val downloadsDir: File get() = File(getExternalFilesDir(null) ?: filesDir, "books").apply { mkdirs() }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .crossfade(true)
            .build()

    companion object {
        lateinit var instance: FolioApp
            private set
    }
}

val app: FolioApp get() = FolioApp.instance
