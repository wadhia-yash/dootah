package com.pravah

import android.app.Application

/**
 * Initialises Pravah once per process.
 *
 * Application.onCreate is the right place: the JavaScript sandbox is a
 * process-scoped resource, and initialising here means an Activity recreation
 * does not tear it down and rebuild it.
 */
class PravahDemoApp : Application() {

    override fun onCreate() {
        super.onCreate()

        Pravah.initialize(
            context = this,
            config = PravahConfig(
                manifestUrl = "https://wadhia-yash.github.io/dootah-manifest/manifest.json",
            ),
        )
    }
}
