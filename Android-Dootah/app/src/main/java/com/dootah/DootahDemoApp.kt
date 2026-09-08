package com.dootah

import android.app.Application

/**
 * Initialises Dootah once per process.
 *
 * Application.onCreate is the right place: the JavaScript sandbox is a
 * process-scoped resource, and initialising here means an Activity recreation
 * does not tear it down and rebuild it.
 */
class DootahDemoApp : Application() {

    override fun onCreate() {
        super.onCreate()

        Dootah.initialize(
            context = this,
            config = DootahConfig(
                manifestUrl = "https://wadhia-yash.github.io/dootah-manifest/manifest.json",
            ),
        )
    }
}
