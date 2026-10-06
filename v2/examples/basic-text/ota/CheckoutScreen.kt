package example.dootah

import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.BasicText

@Composable
fun CheckoutScreen(price: Int) {
    fun discount(p: Int): Int = p * 20 / 100
    val amount = discount(price)
    BasicText("Discount: $amount")
}
