package com.dootah.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dootah.R

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
 *
 * Nothing here is annotated. Dootah discovers both screens because of what they
 * are -- ordinary composables returning Unit -- which is the only way a real app
 * would ever be onboarded.
 */
class ToolboxRegressionModel {

    fun choose(option: String) = Unit

    fun setActive(active: Boolean) = Unit
}

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
            modifier = Modifier.background(
                color = if (isActive) Color.Transparent else MaterialTheme.colorScheme.inversePrimary,
                shape = CircleShape,
            ),
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

/**
 * The second shape a toolbox takes: a strip of controls around a local counter.
 *
 * Kept beside the first because the two exercise different parts of the walk. A
 * local `var` read from a handler makes the components that read it ineligible
 * while their neighbours stay eligible, a button is handed one of the screen's
 * own callbacks directly rather than a lambda, and a component that *is* a
 * boundary sits between two that are not.
 */
@Composable
fun ControlStripRegressionScreen(
    model: ToolboxRegressionModel,
    onClear: () -> Unit,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    var taps = 0

    // How a real screen holds state. A read of `expanded` compiles to a call to
    // an accessor the compiler generated beside it, so a component that reads
    // it contains no mention of it at all -- and one lifted out into an adapter
    // would read a local that does not exist yet, which the JVM backend refuses
    // with an assertion naming nothing Dootah did.
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text("History")

        ControlStripBody(model = model, onClear = onClear, isActive = isActive)

        RegressionBadge(label = if (expanded) "expanded" else "collapsed")

        Text("Taps: $taps")

        Button(onClick = { taps = taps + 1; expanded = !expanded }) {
            Text("Count")
        }

        Button(onClick = onClear) {
            Text("Clear all")
        }
    }
}

@Composable
fun ControlStripBody(
    model: ToolboxRegressionModel,
    onClear: () -> Unit,
    isActive: Boolean,
) {
    IconButton(
        onClick = onClear,
        enabled = isActive,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.regression_brush),
            contentDescription = stringResource(R.string.regression_brush),
            tint = if (isActive) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }

    Spacer(modifier = Modifier.size(4.dp))

    IconButton(onClick = { model.setActive(true) }, modifier = Modifier.size(48.dp)) {
        Icon(
            painter = if (isActive) {
                painterResource(R.drawable.regression_brush)
            } else {
                painterResource(R.drawable.regression_brush)
            },
            contentDescription = stringResource(R.string.regression_brush),
        )
    }
}

/** A component whose only argument is read through a delegated local. */
@Composable
fun RegressionBadge(label: String) {
    Text(label)
}
