package com.dootah.ota

import com.dootah.UpdateResult
import androidx.compose.runtime.mutableStateOf
import com.dootah.ui.BundleUiNode
import com.dootah.ui.DootahContent
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

class UpdateLifecycleTest {
    @Test fun `same payload release replaces the composition health receipt`() {
        equalUiTransition(samePayload = true)
    }

    @Test fun `changed bundle with an unchanged screen replaces the composition health receipt`() {
        equalUiTransition(samePayload = false)
    }

    private fun equalUiTransition(samePayload: Boolean) {
        val disk = store()
        stage(disk, 31)
        val first = disk.prepareForLoad()!!
        val firstHealth = RemoteHealth { disk.confirmHealthy(first.identity) }.apply { initialized() }
        // DootahScreenState uses Compose's structural state policy; the renderer
        // takes its native-ready receipt from the value retained by that state.
        val content = mutableStateOf<DootahContent>(
            DootahContent.Bundle(BundleUiNode.Text("Unchanged screen", emptyList()))
                .also { it.nativeReady = firstHealth.executed() },
        )
        (content.value as DootahContent.Bundle).nativeReady!!()
        assertEquals(31, disk.state().lastKnownGood!!.version)

        val payload = bytes(if (samePayload) 31 else 32)
        disk.stage(signed(32, payload), payload) { _, _ -> error("No images") }
        firstHealth.failed()
        val second = disk.prepareForLoad()!!
        val secondHealth = RemoteHealth { disk.confirmHealthy(second.identity) }.apply { initialized() }
        content.value = DootahContent.Bundle(
            BundleUiNode.Text("Unchanged screen", emptyList()),
        ).also { it.nativeReady = secondHealth.executed() }

        assertFalse(disk.state().confirmedHealthy) // Execution alone is insufficient.
        // The successful native composition invokes only its current receipt.
        (content.value as DootahContent.Bundle).nativeReady!!()
        assertTrue("The new release must receive native readiness even for equal UI", disk.state().confirmedHealthy)
        assertEquals(32, disk.state().lastKnownGood!!.version)
        val restarted = store()
        assertEquals(32, restarted.prepareForLoad()!!.version)
        assertTrue(restarted.state().quarantine.isEmpty())
    }

    @get:Rule val temporary = TemporaryFolder()
    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val verifier = ManifestVerifier("example.app", Base64.getEncoder().encodeToString(keys.public.encoded.takeLast(32).toByteArray()))
    private var interrupt = false
    private fun store() = ImageUpdateStore(temporary.root, {}, authenticate = verifier::verify,
        beforeStateCommit = { if (interrupt) throw IOException("Process interrupted before rename") })
    private fun bytes(version: Int) = (BundleImages.header(emptyList()) + "var release=$version;").toByteArray()
    private fun signed(version: Int, payload: ByteArray = bytes(version), images: List<BundleImage> = emptyList()): BundleManifest {
        val manifest = testManifest(bundleVersion = version, runtimeVersion = "9").copy(
            appId = "example.app", sha256 = sha256Hex(payload), images = images)
        val signer = Signature.getInstance("Ed25519").apply { initSign(keys.private); update(manifest.signedUpdate().signingBytes()) }
        return manifest.copy(signature = Base64.getEncoder().encodeToString(signer.sign()))
    }
    private fun stage(store: ImageUpdateStore, version: Int) = store.stage(signed(version), bytes(version)) { _, _ -> error("Offline") }
    private fun healthy(store: ImageUpdateStore, version: Int) {
        stage(store, version)
        val active = store.activateCandidate()!!
        RemoteHealth { store.confirmHealthy(active.identity) }.apply { initialized(); executed()!!() }
    }
    private fun interrupted(block: () -> Unit) {
        interrupt = true
        try { block(); fail("Expected interrupted write") } catch (_: IOException) { } finally { interrupt = false }
    }

    @Test fun `verified updater download becomes candidate without replacing active or recovery`() = runBlocking {
        val disk = store(); healthy(disk, 31)
        val updateStore = object : UpdateStore {
            override val installedBundleVersion get() = disk.version
            override var isRemotelyDisabled = false
            override fun install(manifest: BundleManifest, payload: ByteArray, download: (String, Int) -> ByteArray) = disk.stage(manifest, payload, download)
        }
        val manifest = signed(32)
        val updater = BundleUpdater("https://example.test/manifest", { url, _ ->
            if (url.endsWith("manifest")) manifest.signedUpdate().manifestJson(manifest.signature).toByteArray() else bytes(32)
        }, updateStore, "9", 65536, 8000000, verifier)
        assertTrue(updater.checkForUpdate() is UpdateResult.Updated)
        assertEquals(32, disk.state().candidate!!.version)
        assertEquals(31, disk.state().active!!.version)
        assertEquals(31, disk.state().lastKnownGood!!.version)
        assertEquals(bytes(31).decodeToString(), disk.readBundle())
    }
    @Test fun `activation remains unconfirmed until native ready after initialization and execution`() {
        val disk = store(); healthy(disk, 31); stage(disk, 32)
        val active = disk.activateCandidate()!!
        assertNull(disk.state().candidate)
        assertEquals(32, active.version); assertFalse(disk.state().confirmedHealthy)
        assertEquals(31, disk.state().lastKnownGood!!.version)
        val health = RemoteHealth { disk.confirmHealthy(active.identity) }
        assertNull(health.executed())
        health.initialized()
        val ready = health.executed()!!
        assertEquals(31, disk.state().lastKnownGood!!.version)
        ready(); ready()
        assertTrue(disk.state().confirmedHealthy)
        assertEquals(32, disk.state().lastKnownGood!!.version)
    }
    @Test fun `failed initialization cannot replace known good`() {
        val disk = store(); healthy(disk, 32); stage(disk, 33)
        val active = disk.activateCandidate()!!
        val health = RemoteHealth { disk.confirmHealthy(active.identity) }
        health.failed()
        assertNull(health.executed())
        assertEquals(32, store().state().lastKnownGood!!.version)
        assertFalse(store().state().confirmedHealthy)
    }
    @Test fun `failed execution or native requirements block even previously issued render receipts`() {
        val disk = store(); healthy(disk, 32); stage(disk, 33)
        val active = disk.activateCandidate()!!
        val health = RemoteHealth { disk.confirmHealthy(active.identity) }
        health.initialized()
        val ready = health.executed()!!
        health.failed(); ready()
        assertEquals(32, store().state().lastKnownGood!!.version)
        assertFalse(store().state().confirmedHealthy)
    }
    @Test fun `successful parsing without native composition never marks update healthy`() {
        val disk = store(); healthy(disk, 32); stage(disk, 33)
        val active = disk.activateCandidate()!!
        RemoteHealth { disk.confirmHealthy(active.identity) }.apply { initialized(); executed() }
        assertEquals(32, store().state().lastKnownGood!!.version)
        assertFalse(store().state().confirmedHealthy)
    }
    @Test fun `restart preserves candidate active recovery and confirmation separately`() {
        val disk = store(); healthy(disk, 31); stage(disk, 32)
        assertEquals(disk.state(), store().state())
        store().activateCandidate()
        assertEquals(32, store().state().active!!.version)
        assertEquals(31, store().state().lastKnownGood!!.version)
        assertFalse(store().state().confirmedHealthy)
        stage(store(), 33)
        assertEquals(33, store().state().candidate!!.version)
        assertEquals(32, store().state().active!!.version)
        assertEquals(31, store().state().lastKnownGood!!.version)
    }
    @Test fun `interruption at each state commit leaves exact previous valid record`() {
        val disk = store(); healthy(disk, 31)
        val record = temporary.root.resolve("active.properties")
        var before = record.readBytes()
        interrupted { stage(disk, 32) }
        assertArrayEquals(before, record.readBytes())
        assertEquals(31, store().state().lastKnownGood!!.version)
        stage(disk, 32); before = record.readBytes()
        interrupted { disk.activateCandidate() }
        assertArrayEquals(before, record.readBytes())
        assertEquals(32, store().state().candidate!!.version)
        val active = disk.activateCandidate()!!; before = record.readBytes()
        interrupted { disk.confirmHealthy(active.identity) }
        assertArrayEquals(before, record.readBytes())
        assertEquals(31, store().state().lastKnownGood!!.version)
        assertFalse(store().state().confirmedHealthy)
        // A killed process may leave an unfinished temporary file; only the committed record is read.
        temporary.root.resolve("pending-interrupted.tmp").writeText("stateSchema=2\nhealthy=true\n")
        assertArrayEquals(before, record.readBytes())
        assertEquals(31, store().state().lastKnownGood!!.version)
    }
    @Test fun `stale confirmation cannot bless a different active update`() {
        val disk = store(); healthy(disk, 31); stage(disk, 32)
        val old = disk.activateCandidate()!!
        stage(disk, 33); disk.activateCandidate()
        assertFalse(disk.confirmHealthy(old.identity))
        assertEquals(31, disk.state().lastKnownGood!!.version)
    }
    @Test fun `assets remain attached to each update and missing candidate asset blocks activation`() {
        val disk = store(); healthy(disk, 31)
        val image = "image content".toByteArray(); val hash = sha256Hex(image)
        val payload = (BundleImages.header(listOf(hash)) + "var release=32;").toByteArray()
        disk.stage(signed(32, payload, listOf(BundleImage(hash, "https://example.test/image", hash))), payload) { _, _ -> image }
        temporary.root.resolve("images/$hash").delete()
        try { disk.activateCandidate(); fail("Missing asset accepted") } catch (_: IOException) { }
        assertEquals(31, disk.state().active!!.version)
        assertEquals(31, disk.state().lastKnownGood!!.version)
        assertEquals(bytes(31).decodeToString(), disk.readBundle())
        temporary.root.resolve("images/$hash").writeBytes(image)
        val active = disk.activateCandidate()!!
        assertEquals(setOf(hash), active.images)
        disk.confirmHealthy(active.identity)
        assertEquals(payload.decodeToString(), store().readBundle())
        assertArrayEquals(image, store().readImage(hash))
        assertEquals(1, temporary.root.resolve("images").listFiles()!!.size)
        assertEquals(2, temporary.root.resolve("bundles").listFiles()!!.size)
    }
    @Test fun `legacy signed cache verifies and migrates as unconfirmed active`() {
        val disk = store(); stage(disk, 31); disk.activateCandidate()
        val record = temporary.root.resolve("active.properties")
        record.writeText(record.readLines().filterNot { it.startsWith("stateSchema=") || it.startsWith("healthy=") }.joinToString("\n"))
        assertEquals(bytes(31).decodeToString(), store().readBundle())
        assertFalse(store().state().confirmedHealthy)
        assertNull(store().state().lastKnownGood)
        store().confirmHealthy(store().state().active!!.identity)
        assertTrue(store().state().confirmedHealthy)
        record.writeText(record.readText().replace("lkg.version=31", "lkg.version=99"))
        try { store().state(); fail("Altered recovery proof accepted") } catch (_: BundleVerificationException) { }
    }
    @Test fun `offline restart verifies both active and retained known good after unconfirmed failure`() {
        val disk = store(); healthy(disk, 32); stage(disk, 33); disk.activateCandidate()
        val restarted = store()
        assertEquals(bytes(33).decodeToString(), restarted.readBundle())
        assertEquals(bytes(32).decodeToString(), restarted.readBundle(restarted.state().lastKnownGood!!))
        assertFalse(restarted.state().confirmedHealthy)
    }
    @Test fun `first failed update has no invented known good and native fallback remains available`() = runBlocking {
        assertFalse(store().exists)
        assertNull(store().state().lastKnownGood)
        val disk = store(); stage(disk, 31); disk.activateCandidate()
        val health = RemoteHealth { fail("Failed update must not promote") }
        health.failed(); assertNull(health.executed())
        assertNull(store().state().lastKnownGood)
        assertFalse(store().state().confirmedHealthy)
        val fallback = com.dootah.Dootah.renderScreen("native-screen", "") as com.dootah.BundleLoadResult.Unavailable
        assertEquals(com.dootah.FallbackReason.RUNTIME_UNAVAILABLE, fallback.reason)
    }
}
