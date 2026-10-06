package dev.dootah.consumer

import androidx.compose.material3.Text
import androidx.compose.runtime.*

// Real compiler inputs used by the bytecode and device regression proof.
@Composable fun CheckoutScreen() { Text("Native checkout") }
@Composable fun ReceiptScreen() { Text("Native receipt") }
@Composable fun Overloaded(value: String) { Text("String: $value") }
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
