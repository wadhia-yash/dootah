package dev.dootah.contract

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class SignedUpdateTest {
    @Test fun `canonical encoding matches independent fixed vector`() {
        val update = SignedUpdate(1, "example.app", "9", 2, true, "https://e.test/bundle", "a".repeat(64), emptyList())
        val hash = MessageDigest.getInstance("SHA-256").digest(update.signingBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("0f2b2556c2023552defefabe9303834f183cfd50e4b605c245d88c6f659aec3a", hash)
    }
    @Test fun `length prefixes prevent field boundary ambiguity`() {
        val update = SignedUpdate(1, "ab", "c", 2, true, "https://e.test/bundle", "a".repeat(64), emptyList())
        assertFalse(update.signingBytes().contentEquals(update.copy(appId = "a", runtimeVersion = "bc").signingBytes()))
    }
}
