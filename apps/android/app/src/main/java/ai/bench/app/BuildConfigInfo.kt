package ai.bench.app

/** The installed version, read from the package rather than BuildConfig (which is off). */
object BuildConfigInfo {
    val versionName: String by lazy {
        runCatching {
            val app = BenchApp.instance
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull() ?: "dev"
    }
}
