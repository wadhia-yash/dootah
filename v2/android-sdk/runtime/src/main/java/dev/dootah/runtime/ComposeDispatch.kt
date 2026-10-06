package dev.dootah.runtime

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView

@Composable
@ReadOnlyComposable
fun dispatchValue(): RenderValue? {
    val view = LocalView.current
    return (view.context.applicationContext as? DootahApplication)?.dispatchValue
}

@Composable
fun renderOverride(title: String, value: RenderValue) {
    BasicText(title, Modifier.dootahFrame(value))
}

@Composable
@ReadOnlyComposable
fun portableText(id: String, types: String, input: Array<Any?>, value: RenderValue): String? {
    val app = LocalView.current.context.applicationContext as DootahApplication
    return app.lookup(value, id, if (types.isEmpty()) emptyList() else types.split(','), input.toList())
}
