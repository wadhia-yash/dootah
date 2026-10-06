package dev.dootah.consumer

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.dootah.runtime.DootahActivity

class MainActivity : DootahActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.Button(onClick = {
                        val ticket = android.widget.EditText(this@MainActivity)
                        ticket.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                        android.app.AlertDialog.Builder(this@MainActivity).setTitle("Enroll Cloud telemetry")
                            .setView(ticket).setPositiveButton("Enroll") { _, _ ->
                                dootah.enrollTelemetry(ticket.text.toString()) { ok ->
                                    android.widget.Toast.makeText(this@MainActivity, if (ok) "Enrolled" else "Enrollment failed", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }.setNegativeButton("Cancel", null).show()
                    }) { Text("Enroll telemetry") }
                    CheckoutScreen(100)
                    ReceiptScreen()
                    Overloaded("native")
                    Overloaded(7)
                    UnsupportedScreen(listOf("Unsupported stays native"))
                    NullableDefault()
                    "Extension native".ExtensionScreen()
                    MemberScreens().Member()
                    MemberScreens().Member("Member explicit")
                    val member: GenericMember<String> = StringMember()
                    member.Show("Bridge native")
                    GenericScreen("Generic native")
                    InternalScreen()
                    StatefulScreen()
                }
            }
        }
    }
}
