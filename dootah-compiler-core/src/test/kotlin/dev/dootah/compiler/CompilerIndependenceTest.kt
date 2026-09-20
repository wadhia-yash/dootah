package dev.dootah.compiler

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class CompilerIndependenceTest {
    @Test fun `semantic core has no compiler ABI references`() {
        File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.forEach {
            assertFalse("Compiler API leaked into $it", it.readText().contains("org.jetbrains.kotlin"))
        }
        assertFalse(System.getProperty("java.class.path").contains("kotlin-compiler-embeddable"))
    }
}
