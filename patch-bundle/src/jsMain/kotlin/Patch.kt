@file:OptIn(ExperimentalJsExport::class)

import ui.*
import bridge.Native

private var quantity = 1

private fun unitPrice(): Int {
    return 599
}

private fun totalPrice(): Int {
    return quantity * unitPrice()
}

@JsExport
fun renderScreen(): String {

    return Column {

        Text("🚀 Pravah OTA Works!")

        Text("Quantity: $quantity")

        Text("Unit price: ₹${unitPrice()}")

        Text("Total: ₹${totalPrice()}")

        if (quantity >= 3) {
            Text("🎉 Bulk discount unlocked!")
        }

        Button(
            text = "Add Item",
            action = "increment"
        )

        Button(
            text = "Remove Item",
            action = "decrement"
        )

        Button(
            text = "Pay ₹${totalPrice()}",
            action = "pay"
        )

    }.toJson()
}

@JsExport
fun handleAction(action: String): String {

    when (action) {

        "increment" -> {
            quantity++
        }

        "decrement" -> {
            if (quantity > 1) {
                quantity--
            }
        }

        "pay" -> {

            Native.log(
                "Checkout for ₹${totalPrice()}"
            )

            Native.toast(
                "Paying ₹${totalPrice()}"
            )
        }
    }

    return renderScreen()
}

fun main() {}