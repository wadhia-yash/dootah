package com.dootah.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dootah.R
import dev.dootah.Bundlable

/**
 * The shape of a real toolbox, built out of real Compose rather than stubs.
 *
 * This exists to be *compiled*, not run, and it is the only place in the
 * repository where Dootah's output meets the actual Compose compiler. The
 * compiler plugin's own tests use hand-written Compose declarations, which
 * differ from the real ones in the ways that have actually broken builds: the
 * real ones arrive as external declarations Compose has to give a `$composer`
 * to, they carry defaults that are themselves composable, and a composable
 * lambda reaches the backend as a `ComposableFunction0` rather than as an
 * annotated `Function0`. Every one of those has produced a failure whose only
 * evidence was a stack trace inside the Compose compiler.
 *
 * Every construct here is one a real screen uses: a view model routed in as a
 * handle, a `MutableState` read inside the composition, a handler that takes a
 * value from the component it is attached to, several instances of one
 * component, a component nested two levels deep, drawable and string resources,
 * and a themed colour chosen by a value the bundle computes.
 */
class ToolboxRegressionModel {

    fun choose(option: String) = Unit

    fun setActive(active: Boolean) = Unit
}

@Bundlable
@Composable
fun ToolboxRegressionScreen(
    model: ToolboxRegressionModel,
    menuExpanded: MutableState<Boolean>,
    options: List<String>,
    onPick: () -> Unit,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        RegressionMenu(
            expanded = menuExpanded.value,
            onDismissRequest = { menuExpanded.value = false },
            onChoose = { option ->
                model.choose(option)
                menuExpanded.value = false
            },
            options = options,
        )
    }

    IconButton(
        onClick = { menuExpanded.value = true },
        modifier = Modifier.size(48.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.regression_brush),
            contentDescription = stringResource(R.string.regression_brush),
            tint = MaterialTheme.colorScheme.primary,
        )
    }

    IconButton(
        onClick = {
            onPick()
            model.setActive(false)
        },
        modifier = Modifier.size(48.dp),
    ) {
        Text(
            text = "pick",
            color = if (isActive) Color.Transparent else MaterialTheme.colorScheme.inversePrimary,
        )
    }

    Box {
        IconButton(onClick = { menuExpanded.value = true }) {
            Text("more")
        }
    }
}

@Composable
fun RegressionMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onChoose: (String) -> Unit,
    options: List<String>,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        options.forEach { option ->
            DropdownMenuItem(text = { Text(option) }, onClick = { onChoose(option) })
        }
    }
}
