package dev.dootah.consumer

import androidx.compose.material3.Text
import androidx.compose.runtime.*

// Real compiler inputs used by the bytecode and device regression proof.
@Composable fun CheckoutScreen(price: Int) {
    fun discount(p: Int): Int = p * 10 / 100
    val amount = discount(price)
    androidx.compose.foundation.text.BasicText("Discount: $amount")
}
@Composable fun ReceiptScreen() {
    val ready = true
    val title = "Receipt via Kotlin OTA"
    if (ready) {
        androidx.compose.foundation.text.BasicText(title)
    } else {
        androidx.compose.foundation.text.BasicText("Receipt unavailable")
    }
}
@Composable fun Overloaded(value: String) { androidx.compose.foundation.text.BasicText("String overload via Kotlin OTA") }
@Composable fun Overloaded(value: Int) { Text("Int: $value") }
@Composable fun NullableDefault(value: String? = null) { Text(value ?: "Default") }
@Composable fun String.ExtensionScreen() { Text(this) }
@Composable fun <T> GenericScreen(value: T) { Text("Generic: $value") }
@Composable fun UnsupportedScreen(value: List<String>) { Text(value.joinToString()) }
@Composable private fun PrivateScreen() { Text("Private") }
@Composable internal fun InternalScreen() { PrivateScreen() }
@Composable fun StatefulScreen() {
    var count by remember { mutableIntStateOf(0) }
    androidx.compose.material3.Button(onClick = { count++ }) { Text("Count: $count") }
}
class MemberScreens {
    @Composable fun Member(value: String = "Member") { Text(value) }
}
interface GenericMember<T> { @Composable fun Show(value: T) }
class StringMember : GenericMember<String> {
    @Composable override fun Show(value: String) { Text(value) }
}
@Composable fun ValueReturning(): String = "Native value"
fun Ordinary(value: Int) = value + 1
