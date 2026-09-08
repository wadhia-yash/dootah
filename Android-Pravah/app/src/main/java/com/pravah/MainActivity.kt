package com.pravah

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.pravah.demo.OfferDemoScreen
import com.pravah.ui.theme.PravahTheme

/**
 * Hosts the single Pravah validation screen.
 *
 * Deliberately empty of product logic: everything the experiment exercises lives
 * in [OfferDemoScreen] and in the Pravah library, so this Activity is a faithful
 * example of what integrating Pravah costs a host app.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PravahTheme {
                OfferDemoScreen()
            }
        }
    }
}
