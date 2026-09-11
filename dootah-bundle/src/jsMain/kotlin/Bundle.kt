@file:OptIn(ExperimentalJsExport::class)

import protocol.Command
import protocol.envelope
import protocol.screenIdsJson
import protocol.unknownScreen
import ui.Column

/**
 * A bundle written by hand against the Dootah runtime protocol.
 *
 * Not how an app is built -- the compiler generates bundles from ordinary
 * `@Bundlable` Compose code, and no developer should ever write this file. It is
 * kept as a conformance fixture: it exercises the protocol independently of the
 * compiler, so a protocol change that the generator happens to accommodate still
 * has to be honoured here. It also drives Dootah's own validation app.
 *
 * Runtime "2": screens are addressed by id, calls carry the caller's arguments,
 * and native work is requested by returning commands rather than by calling out
 * mid-render.
 */

private const val SCREEN_ID = "com.dootah.demo.OfferDemoScreen"

private const val UNIT_PRICE = 899

private const val BULK_DISCOUNT_THRESHOLD = 3
private const val BULK_DISCOUNT_PERCENT = 10

private var quantity = 1

/** Business logic owned entirely by the bundle, not by the Android app. */
private fun totalPrice(): Int {

    val subtotal = quantity * UNIT_PRICE

    return if (quantity >= BULK_DISCOUNT_THRESHOLD) {
        subtotal - (subtotal * BULK_DISCOUNT_PERCENT / 100)
    } else {
        subtotal
    }
}

@JsExport
fun screenIds(): String = screenIdsJson(listOf(SCREEN_ID))

@JsExport
fun renderScreen(screenId: String, @Suppress("UNUSED_PARAMETER") argumentsJson: String): String {

    if (screenId != SCREEN_ID) return unknownScreen(screenId)

    return envelope(offerScreen(), emptyList())
}

@JsExport
fun handleAction(
    screenId: String,
    action: String,
    @Suppress("UNUSED_PARAMETER") argumentsJson: String,
): String {

    if (screenId != SCREEN_ID) return unknownScreen(screenId)

    val commands = mutableListOf<Command>()

    when (action) {

        "increment" -> quantity++

        "decrement" -> if (quantity > 1) quantity--

        "buy" -> {
            commands.add(Command.Log("Checkout started for Rs ${totalPrice()}"))
            commands.add(Command.Toast("Paying Rs ${totalPrice()}"))
        }
    }

    return envelope(offerScreen(), commands)
}

private fun offerScreen() = Column {

    Text("Weekend Offer")
    Text("Served by a hand-written Dootah bundle")

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
}

fun main() {}
