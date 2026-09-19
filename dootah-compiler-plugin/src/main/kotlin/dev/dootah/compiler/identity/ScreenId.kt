package dev.dootah.compiler.identity

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable

/**
 * The identity a bundle and the installed app match on.
 *
 * The fully qualified function name, which is stable across recompiles and body
 * edits and changes when the function is renamed or moved -- which is correct,
 * because then it is a different screen.
 *
 * With one qualification. A fully qualified name identifies a function only when
 * the function is visible by it. Two files in one package may each declare
 * `private fun StatItem(…)`, and Kotlin is perfectly happy: they are different
 * functions that cannot see each other. Dootah was not. It gave them one
 * identity, so the app registered one screen's components under the other's
 * name and a bundle describing one was offered to both. The installed contract
 * check refused the mismatched one and the runtime fell back to native -- but
 * only because their components happened to differ. Two private screens alike
 * enough to pass that check would have drawn each other.
 *
 * So a declaration not visible by its qualified name is qualified further, by
 * the file that declares it. Both passes read the same file for the same
 * declaration and take the last path segment of it, so both arrive at the same
 * answer from paths that need not be spelled the same way.
 *
 * `#` because it cannot appear in a Kotlin qualified name, so a further
 * qualified identity can never collide with a plain one.
 */
internal fun screenIdOf(
    qualifiedName: String,
    visibleByName: Boolean,
    filePath: String?,
): String {

    if (visibleByName) return qualifiedName

    val file = filePath?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        ?: return qualifiedName

    return "$qualifiedName#$file"
}

internal fun IrSimpleFunction.dootahScreenId(): String = screenIdOf(
    qualifiedName = fqNameWhenAvailable?.asString() ?: name.asString(),
    visibleByName = visibility == DescriptorVisibilities.PUBLIC,
    filePath = fileOrNull?.fileEntry?.name,
)
