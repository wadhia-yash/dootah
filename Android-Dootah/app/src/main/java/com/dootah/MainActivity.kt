package com.dootah

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dootah.demo.OfferDemoScreen
import com.dootah.ui.theme.DootahTheme

/**
 * Hosts the single Dootah validation screen.
 *
 * Deliberately empty of product logic: everything the experiment exercises lives
 * in [OfferDemoScreen] and in the Dootah library, so this Activity is a faithful
 * example of what integrating Dootah costs a host app.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            DootahTheme {
                OfferDemoScreen()
            }
        }
    }
}
