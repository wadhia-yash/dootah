package dev.dootah.gradle

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which variant Dootah works on, decided the same way whatever produced the list. */
class VariantSelectionTest {

    private fun variant(name: String, buildType: String?) = DootahVariant(name, buildType)

    @Test fun `a module with one debug variant needs no answer`() {
        val variants = listOf(variant("debug", "debug"), variant("release", "release"))
        assertEquals("debug", selectDootahVariant(variants, requested = null).name)
    }

    @Test fun `flavours with a single debug variant still need no answer`() {
        val variants = listOf(
            variant("genericDebug", "debug"),
            variant("genericRelease", "release"),
            variant("fdroidRelease", "release"),
        )
        assertEquals("genericDebug", selectDootahVariant(variants, requested = null).name)
    }

    @Test fun `several debug variants are not chosen between`() {
        val variants = listOf(
            variant("genericDebug", "debug"),
            variant("fdroidDebug", "debug"),
            variant("githubPreviewDebug", "debug"),
            variant("genericRelease", "release"),
        )
        val failure = runCatching { selectDootahVariant(variants, requested = null) }
            .exceptionOrNull() as GradleException

        // Every candidate named, and no release variant offered as one.
        assertTrue(failure.message, failure.message!!.contains("genericDebug"))
        assertTrue(failure.message, failure.message!!.contains("fdroidDebug"))
        assertTrue(failure.message, failure.message!!.contains("githubPreviewDebug"))
        assertTrue(failure.message, !failure.message!!.contains("genericRelease"))
        assertTrue(failure.message, failure.message!!.contains(VARIANT_PROPERTY))
    }

    @Test fun `a named variant wins over the debug default`() {
        val variants = listOf(variant("genericDebug", "debug"), variant("fdroidDebug", "debug"))
        assertEquals("fdroidDebug", selectDootahVariant(variants, requested = "fdroidDebug").name)
    }

    @Test fun `a release variant may be named explicitly`() {
        val variants = listOf(variant("genericDebug", "debug"), variant("genericRelease", "release"))
        assertEquals("genericRelease", selectDootahVariant(variants, requested = "genericRelease").name)
    }

    @Test fun `a variant this module does not build is refused with the ones it does`() {
        val variants = listOf(variant("genericDebug", "debug"), variant("fdroidDebug", "debug"))
        val failure = runCatching { selectDootahVariant(variants, requested = "genericdebug") }
            .exceptionOrNull() as GradleException

        assertTrue(failure.message, failure.message!!.contains("\"genericdebug\""))
        assertTrue(failure.message, failure.message!!.contains("genericDebug"))
        assertTrue(failure.message, failure.message!!.contains("fdroidDebug"))
    }

    @Test fun `a module with no debug build type but one variant is taken anyway`() {
        val variants = listOf(variant("staging", "staging"))
        assertEquals("staging", selectDootahVariant(variants, requested = null).name)
    }

    @Test fun `no debug build type and several variants is a question, not a guess`() {
        val variants = listOf(variant("staging", "staging"), variant("production", "production"))
        val failure = runCatching { selectDootahVariant(variants, requested = null) }
            .exceptionOrNull() as GradleException

        assertTrue(failure.message, failure.message!!.contains("staging"))
        assertTrue(failure.message, failure.message!!.contains("production"))
    }
}
