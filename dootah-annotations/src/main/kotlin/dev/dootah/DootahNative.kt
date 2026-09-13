package dev.dootah

/**
 * Keeps a Composable function, or everything in a class or file, out of Dootah.
 *
 * Dootah discovers eligible Compose functions on its own, so the annotation an
 * app needs is the one that says *no*. Mark anything that must only ever run the
 * code compiled into the APK -- something safety critical, something whose
 * behaviour is audited against the binary, something being kept deliberately
 * simple.
 *
 * The function is then neither analysed nor rewritten: its body is left exactly
 * as written, and no bundle can ever address it.
 *
 * A whole package is better excluded in the build script, which does not require
 * touching the source at all:
 *
 *     dootah {
 *         exclude("com.example.payments.**")
 *     }
 */
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.CLASS,
    AnnotationTarget.FILE,
)
@Retention(AnnotationRetention.BINARY)
annotation class DootahNative
