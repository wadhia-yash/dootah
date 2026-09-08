@file:OptIn(ExperimentalJsExport::class)

import bridge.Native
import ui.Column
import ui.toJson

/**
 * The patch that ships bundled in the APK: "patch v1".
 *
 * Everything here is replaceable over the air -- the copy, the prices, the
 * discount rule and the button labels. To publish v2, edit this file, run
 * ./gradlew buildPatch, and upload the produced bundle with an incremented
 * patchVersion in the manifest.
 */

private const val UNIT_PRICE = 899

private const val BULK_DISCOUNT_THRESHOLD = 3
private const val BULK_DISCOUNT_PERCENT = 10

private var quantity = 1

/** Business logic owned entirely by the patch, not by the Android app. */
private fun totalPrice(): Int {

    val subtotal = quantity * UNIT_PRICE

    return if (quantity >= BULK_DISCOUNT_THRESHOLD) {
        subtotal - (subtotal * BULK_DISCOUNT_PERCENT / 100)
    } else {
        subtotal
    }
}

@JsExport
fun renderScreen(): String {

    return Column {

        Text("Weekend Offer")
        Text("Served by Pravah patch v1")

        Text("Unit price: Rs $UNIT_PRICE")
        Text("Quantity: $quantity")
        Text("Total: Rs ${totalPrice()}")

        if (quantity >= BULK_DISCOUNT_THRESHOLD) {
            Text("Bulk discount applied: $BULK_DISCOUNT_PERCENT% off")
        } else {
            Text("Add $BULK_DISCOUNT_THRESHOLD or more for a bulk discount")
        }

        Button(text = "Add item", action = "increment")
        Button(text = "Remove item", action = "decrement")
        Button(text = "Buy for Rs ${totalPrice()}", action = "buy")

    }.toJson()
}

@JsExport
fun handleAction(action: String): String {

    when (action) {

        "increment" -> quantity++

        "decrement" -> if (quantity > 1) quantity--

        "buy" -> {
            Native.log("Checkout started for Rs ${totalPrice()}")
            Native.toast("Paying Rs ${totalPrice()}")
        }
    }

    return renderScreen()
}

fun main() {}
