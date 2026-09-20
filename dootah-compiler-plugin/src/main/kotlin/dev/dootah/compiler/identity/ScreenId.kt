package dev.dootah.compiler.identity

import dev.dootah.compiler.compat.*
import dev.dootah.contract.AdapterId
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
 *
 * And a qualified name does not identify a *public* function either, because
 * Kotlin lets a package declare the same name more than once as long as the
 * parameters differ. Seal declares two `FormatPage` composables in one package,
 * in two files: one takes a `VideoInfo` and one takes a `SelectionState`. Under
 * their shared name the app intercepted both and recorded one, the extraction
 * pass described both and kept the other, and the two halves of the check were
 * about two different screens. On a device it is worse than a failed check --
 * both functions answer to the name, so a bundle for either would be drawn in
 * place of the other.
 *
 * So the identity is the declaration, named the way [AdapterId] names the
 * composables a screen places: the qualified name and the parameters declared
 * with it. That is the same rule Kotlin itself resolves overloads by, it is
 * computed from the declaration rather than from any call, and both passes read
 * it off the same declaration.
 */
internal fun screenIdOf(
    qualifiedName: String,
    parameterNames: List<String>,
    visibleByName: Boolean,
    filePath: String?,
): String {

    val declaration = AdapterId.of(qualifiedName, parameterNames)

    if (visibleByName) return declaration

    val file = filePath?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        ?: return declaration

    return "$declaration#$file"
}

internal fun IrSimpleFunction.dootahScreenId(): String = screenIdOf(
    qualifiedName = fqNameWhenAvailable?.asString() ?: name.asString(),
    parameterNames = screenParameterNames(),
    visibleByName = visibility == DescriptorVisibilities.PUBLIC,
    filePath = fileOrNull?.fileEntry?.name,
)

/**
 * The parameters the source declared, without any the compiler added.
 *
 * Dootah runs before the Compose plugin, so `$composer` and its siblings are not
 * here yet -- but a `$this` or a default-argument marker can be, and the other
 * pass reads source, where none of them appear.
 */
private fun IrSimpleFunction.screenParameterNames(): List<String> =
    parameters
        .filter { parameter -> parameter.kind == IrParameterKind.Regular }
        .map { parameter -> parameter.name.asString() }
        .filterNot { name -> name.startsWith("$") }
