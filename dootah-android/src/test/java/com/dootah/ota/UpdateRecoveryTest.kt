package com.dootah.ota

import com.dootah.UpdateResult
import dev.dootah.contract.BundleImages
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class UpdateRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val verifier = ManifestVerifier("example.app", Base64.getEncoder().encodeToString(keys.public.encoded.takeLast(32).toByteArray()))
    private var interrupt = false
    private fun store() = ImageUpdateStore(temporary.root, {}, authenticate = verifier::verify,
        beforeStateCommit = { if (interrupt) throw IOException("Interrupted rename") })
    private fun bytes(version: Int) = (BundleImages.header(emptyList()) + "var release=$version;").toByteArray()
    private fun signed(version: Int, payload: ByteArray = bytes(version), images: List<BundleImage> = emptyList()): BundleManifest {
        val manifest = testManifest(bundleVersion = version, runtimeVersion = "9").copy(
            appId = "example.app", sha256 = sha256Hex(payload), images = images)
        val signer = Signature.getInstance("Ed25519").apply { initSign(keys.private); update(manifest.signedUpdate().signingBytes()) }
        return manifest.copy(signature = Base64.getEncoder().encodeToString(signer.sign()))
    }
    private fun stage(disk: ImageUpdateStore, version: Int) = disk.stage(signed(version), bytes(version)) { _, _ -> error("Offline") }
    private fun good(disk: ImageUpdateStore, version: Int = 1) {
        stage(disk, version)
        val active = disk.prepareForLoad()!!
        val health = RemoteHealth { disk.confirmHealthy(active.identity) }
        health.initialized(); health.executed()!!()
    }
    private fun bad(disk: ImageUpdateStore, version: Int = 2): ImageUpdateStore.Update {
        stage(disk, version); return disk.prepareForLoad()!!
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected verification rejection") } catch (_: BundleVerificationException) { }
    }

    @Test fun `handled initialization failure selects good and persists quarantine atomically`() {
        val disk = store(); good(disk); val failed = bad(disk)
        assertTrue(disk.failUnconfirmed(failed.identity, "initialization"))
        val state = store().state()
        assertEquals(1, state.active!!.version); assertEquals(state.active, state.lastKnownGood)
        assertTrue(state.confirmedHealthy); assertNull(state.attempt)
        assertEquals(listOf(ImageUpdateStore.Failure(failed.hash, 2, "initialization")), state.quarantine)
        assertEquals(bytes(1).decodeToString(), store().readBundle())
    }
    @Test fun `execution and readiness failure recover without allowing stale health receipt`() {
        for ((i, reason) in listOf("execution", "readiness").withIndex()) {
            val disk = store(); good(disk, 10 + i); val failed = bad(disk, 20 + i)
            val health = RemoteHealth { disk.confirmHealthy(failed.identity) }.apply { initialized() }
            val receipt = health.executed()!!
            health.failed(); assertTrue(disk.failUnconfirmed(failed.identity, reason)); receipt()
            assertEquals(10 + i, disk.state().active!!.version)
            assertEquals(reason, disk.state().quarantine.last().reason)
        }
    }
    @Test fun `rollback retains bundle and images as a consistent verified pair without copying`() {
        val disk = store(); val image = "image A".toByteArray(); val hash = sha256Hex(image)
        val payload = (BundleImages.header(listOf(hash)) + "good A").toByteArray()
        disk.stage(signed(1, payload, listOf(BundleImage(hash, "https://example.test/a", hash))), payload) { _, _ -> image }
        disk.confirmHealthy(disk.prepareForLoad()!!.identity)
        val failed = bad(disk)
        val files = temporary.root.walkTopDown().filter { it.isFile && it.name != "active.properties" }.associate { it.path to it.readBytes().toList() }
        disk.failUnconfirmed(failed.identity, "execution")
        assertEquals(setOf(hash), disk.state().active!!.images)
        assertEquals(payload.decodeToString(), store().readBundle())
        assertArrayEquals(image, store().readImage(hash))
        val remaining = temporary.root.walkTopDown().filter { it.isFile && it.name != "active.properties" }
            .associate { it.path to it.readBytes().toList() }
        assertEquals(files.filterKeys { !it.endsWith("/${failed.hash}.js") }, remaining)
        assertFalse(temporary.root.resolve("bundles/${failed.hash}.js").exists())
    }
    @Test fun `unfinished attempt triggers recovery on first restart and never retries on later launches`() {
        val disk = store(); good(disk); val failed = bad(disk)
        assertEquals(failed.identity, disk.state().attempt)
        repeat(5) {
            val restarted = store()
            assertEquals(1, restarted.prepareForLoad()!!.version)
            assertEquals("unfinished_attempt", restarted.state().quarantine.single().reason)
            assertNull(restarted.state().attempt)
        }
    }
    @Test fun `same process reloading is not an unfinished previous process`() {
        val disk = store(); good(disk); bad(disk)
        repeat(3) { assertEquals(2, disk.prepareForLoad()!!.version) }
        assertTrue(disk.state().quarantine.isEmpty())
        assertEquals(1, store().prepareForLoad()!!.version)
    }
    @Test fun `activation and attempt commit together before initialization`() {
        val disk = store(); good(disk); stage(disk, 2)
        interrupt = true
        try { disk.prepareForLoad(); fail() } catch (_: IOException) { } finally { interrupt = false }
        assertEquals(1, store().state().active!!.version)
        assertEquals(2, store().state().candidate!!.version)
        assertNull(store().state().attempt)
        val activated = store().prepareForLoad()!!
        assertEquals(activated.identity, store().state().attempt)
        assertEquals(1, store().prepareForLoad()!!.version)
    }
    @Test fun `healthy updates never roll back or acquire quarantine`() {
        val disk = store(); good(disk)
        val identity = disk.state().active!!.identity
        assertFalse(disk.failUnconfirmed(identity, "execution"))
        repeat(4) { assertEquals(1, store().prepareForLoad()!!.version) }
        assertTrue(store().state().quarantine.isEmpty()); assertNull(store().state().attempt)
        good(disk, 2)
        assertEquals(2, store().prepareForLoad()!!.version)
    }
    @Test fun `no known good selects native fallback and remains quarantined offline`() {
        val disk = store(); val failed = bad(disk)
        disk.failUnconfirmed(failed.identity, "initialization")
        repeat(3) {
            assertNull(store().prepareForLoad())
            assertNull(store().state().lastKnownGood)
            assertTrue(store().isBlocked(signed(2)))
        }
    }
    @Test fun `missing or corrupt known good bytes select APK instead of unsafe recovery`() {
        val disk = store(); good(disk); val failed = bad(disk)
        temporary.root.resolve("bundles/${sha256Hex(bytes(1))}.js").writeText("corrupt")
        disk.failUnconfirmed(failed.identity, "execution")
        assertNull(store().prepareForLoad()); assertEquals(1, store().state().quarantine.size)
    }
    @Test fun `interrupted rollback preserves previous record and next launch completes recovery`() {
        val disk = store(); good(disk); val failed = bad(disk)
        val file = temporary.root.resolve("active.properties"); val before = file.readBytes()
        interrupt = true
        try { disk.failUnconfirmed(failed.identity, "execution"); fail() } catch (_: IOException) { } finally { interrupt = false }
        assertArrayEquals(before, file.readBytes())
        assertEquals(1, store().state().lastKnownGood!!.version)
        assertEquals(1, store().prepareForLoad()!!.version)
        assertEquals(2, store().state().quarantine.single().version)
    }
    @Test fun `identical or higher version republished bytes remain blocked before downloads`() = runBlocking {
        val disk = store(); good(disk); val failed = bad(disk)
        disk.failUnconfirmed(failed.identity, "execution")
        for (version in listOf(2, 99)) {
            val manifest = signed(version, bytes(2))
            val restarted = store()
            assertTrue(restarted.isBlocked(manifest))
            rejected { restarted.stage(manifest, bytes(2)) { _, _ -> error("Must not download") } }
            var downloads = 0
            val adapter = object : UpdateStore {
                override val installedBundleVersion get() = restarted.version
                override var isRemotelyDisabled = false
                override fun isBlocked(manifest: BundleManifest) = restarted.isBlocked(manifest)
                override fun install(manifest: BundleManifest, payload: ByteArray, download: (String, Int) -> ByteArray) = error("Must not install")
            }
            val updater = BundleUpdater("https://example.test/manifest", { url, _ ->
                assertTrue(url.endsWith("manifest")); downloads++
                manifest.signedUpdate().manifestJson(manifest.signature).toByteArray()
            }, adapter, "9", 65536, 8000000, verifier)
            assertTrue(updater.checkForUpdate() is UpdateResult.NoUpdate)
            assertEquals(1, downloads)
        }
    }
    @Test fun `quarantine full fails closed without forgetting old failures`() {
        val disk = store(); good(disk)
        for (version in 2..ImageUpdateStore.MAX_QUARANTINE + 1) {
            val failed = bad(disk, version); disk.failUnconfirmed(failed.identity, "execution")
        }
        assertEquals(ImageUpdateStore.MAX_QUARANTINE, store().state().quarantine.size)
        assertTrue(store().isBlocked(signed(2)))
        rejected { stage(store(), 100) }
        assertEquals(1, store().prepareForLoad()!!.version)
    }
    @Test fun `bad signatures and hashes cannot change lifecycle or quarantine`() {
        val disk = store(); good(disk); val before = disk.state()
        rejected { disk.stage(signed(2).copy(bundleVersion = 3), bytes(2)) { _, _ -> error("download") } }
        rejected { disk.stage(signed(2), bytes(3)) { _, _ -> error("download") } }
        assertEquals(before, disk.state())
    }
    @Test fun `new candidate and stale failure cannot displace a healthy successor`() {
        val disk = store(); good(disk); val old = bad(disk)
        stage(disk, 3)
        assertEquals(2, disk.state().active!!.version); assertEquals(1, disk.state().lastKnownGood!!.version)
        disk.failUnconfirmed(old.identity, "execution")
        assertEquals(3, disk.state().candidate!!.version)
        val successor = disk.prepareForLoad()!!
        disk.confirmHealthy(successor.identity)
        assertFalse(disk.failUnconfirmed(old.identity, "readiness"))
        assertEquals(3, store().prepareForLoad()!!.version)
    }
}
