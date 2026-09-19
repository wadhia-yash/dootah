package com.dootah.ota

import dev.dootah.contract.BundleImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * What reading the update state is allowed to cost.
 *
 * Every intercepted screen asks for Dootah's status when it finishes rendering,
 * and the status carries the installed bundle version, which comes from the
 * persisted record. Reading that record verifies the publisher's signature over
 * every proof in it -- the active update, the last known good, the candidate,
 * each retained version.
 *
 * An app with forty screens was therefore verifying hundreds of Ed25519
 * signatures on its main thread during its first composition. It looked like a
 * thirty-second blank screen, and the system eventually killed it for not
 * responding. The signature of a file that has not changed does not need
 * checking twice.
 *
 * So the property here is about work rather than about results: an unchanged
 * record is verified once, and a changed one is verified again.
 */
class StateReadCostTest {

    @get:Rule val temporary = TemporaryFolder()

    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private val verifier = ManifestVerifier(
        "example.app",
        Base64.getEncoder().encodeToString(keys.public.encoded.takeLast(32).toByteArray()),
    )

    private var verifications = 0

    private fun store() = ImageUpdateStore(
        directory = temporary.root,
        validateImage = { },
        authenticate = { manifest -> verifications++; verifier.verify(manifest) },
    )

    private fun bytes(version: Int) =
        (BundleImages.header(emptyList()) + "var release=$version;").toByteArray()

    private fun signed(version: Int): BundleManifest {
        val manifest = testManifest(bundleVersion = version, runtimeVersion = DOOTAH_RUNTIME_VERSION)
            .copy(appId = "example.app", sha256 = sha256Hex(bytes(version)), images = emptyList())
        val signer = Signature.getInstance("Ed25519").apply {
            initSign(keys.private); update(manifest.signedUpdate().signingBytes())
        }
        return manifest.copy(signature = Base64.getEncoder().encodeToString(signer.sign()))
    }

    private fun activate(store: ImageUpdateStore, version: Int) {
        store.stage(signed(version), bytes(version)) { _, _ -> error("Offline") }
        store.activateCandidate()
    }

    @Test
    fun `an unchanged record is verified once however often it is read`() {

        val store = store()
        activate(store, version = 2)

        store.state()
        val afterFirstRead = verifications

        repeat(50) { store.state() }

        assertEquals(
            "reading an unchanged record fifty more times must verify nothing again",
            afterFirstRead,
            verifications,
        )
    }

    @Test
    fun `the installed version is readable without verifying again`() {

        val store = store()
        activate(store, version = 2)

        assertEquals(2, store.version)
        val afterFirstRead = verifications

        repeat(50) { store.version }

        assertEquals(afterFirstRead, verifications)
    }

    @Test
    fun `a record this store changes is read again`() {

        val store = store()
        activate(store, version = 2)
        assertEquals(2, store.version)
        val afterFirstRead = verifications

        activate(store, version = 3)

        assertEquals("a newly activated update must be visible", 3, store.version)
        assertTrue(
            "a changed record must not be answered from the previous read",
            verifications > afterFirstRead,
        )
    }

    @Test
    fun `a record replaced underneath the store is not answered from a stale read`() {

        val store = store()
        activate(store, version = 2)
        assertEquals(2, store.version)

        // What a second process of the same app, or an operator command, leaves
        // behind: the same file, rewritten by someone this instance never saw.
        val record = java.io.File(temporary.root, "active.properties")
        val other = store()
        other.stage(signed(5), bytes(5)) { _, _ -> error("Offline") }
        other.activateCandidate()
        record.setLastModified(record.lastModified() + 2_000)

        assertEquals("the record on disk is what counts", 5, store.version)
    }
}
