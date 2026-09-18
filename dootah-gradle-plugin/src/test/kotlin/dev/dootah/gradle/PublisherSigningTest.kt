package dev.dootah.gradle

import dev.dootah.contract.SignedUpdate
import dev.dootah.gradle.internal.PublisherSigning
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class PublisherSigningTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `external PKCS8 key produces deterministic valid Ed25519 signature`() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val file = temporary.newFile("key.pem")
        file.writeText("-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(keys.private.encoded) + "\n-----END PRIVATE KEY-----\n")
        val update = SignedUpdate(1, "example.app", "8", 2, true, "https://e.test/bundle", "a".repeat(64), emptyList())
        val signature = PublisherSigning.sign(update, file)
        assertEquals(signature, PublisherSigning.sign(update, file))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(keys.public); verifier.update(update.signingBytes())
        assertTrue(verifier.verify(Base64.getDecoder().decode(signature)))
        file.delete()
    }
}
