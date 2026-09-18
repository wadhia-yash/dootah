package com.dootah.ota

import com.dootah.UpdateResult
import com.dootah.UpdateFailure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class UpdateCheckTest {
    @get:Rule val temp = TemporaryFolder()
    private fun request(channel: String = "production", version: Long = 1) = UpdateCheckRequest("example.app", "9", version, channel) { "install-123" }
    @Test fun `stable identity persists and differs across installations`() {
        val a = File(temp.root, "a"); val b = File(temp.root, "b")
        val first = InstallationIdentity.readOrCreate(a)
        repeat(10) { assertEquals(first, InstallationIdentity.readOrCreate(a)) }
        assertNotEquals(first, InstallationIdentity.readOrCreate(b))
    }
    @Test fun `corrupt persisted identity fails closed instead of rerolling`() {
        File(temp.root, "installation-id").writeText("bad")
        assertThrows(IllegalArgumentException::class.java) { InstallationIdentity.readOrCreate(temp.root) }
        assertEquals("bad", File(temp.root, "installation-id").readText())
    }
    @Test fun `request carries installed targeting and stable id`() {
        assertEquals("https://localhost/updates/check?appId=example.app&runtimeVersion=9&appVersion=1&channel=production&installationId=install-123", request().url("https://localhost/updates/check"))
    }
    @Test fun `channels and APK versions have separate lifecycle storage scopes`() {
        assertEquals(request().cacheScope, request().cacheScope)
        assertEquals(4, setOf(request().cacheScope, request("staging").cacheScope, request("development").cacheScope, request(version=2).cacheScope).size)
    }
    @Test fun `invalid configuration and transport fail closed`() {
        assertThrows(IllegalArgumentException::class.java) { request("unknown") }
        for (url in listOf("http://localhost/check", "https://host/check?channel=staging", "https://host/check#fragment"))
            assertThrows(IllegalArgumentException::class.java) { request().url(url) }
    }
    @Test fun `wrong or malformed channel response rejected`() {
        for (json in listOf("{}", "[]", "{\"channel\":\"staging\",\"manifest\":{}}", "{\"channel\":\"production\",\"manifest\":null}"))
            assertThrows(BundleManifestException::class.java) { request().manifest(json) }
    }
    private val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val verifier = ManifestVerifier("example.app", Base64.getEncoder().encodeToString(key.public.encoded.takeLast(32).toByteArray()))
    private val payload = "bundle".toByteArray()
    private fun signed(app: String = "example.app", runtime: String = "9"): BundleManifest {
        val m = testManifest(bundleVersion=72,runtimeVersion=runtime).copy(appId=app,sha256=sha256Hex(payload))
        val s=Signature.getInstance("Ed25519");s.initSign(key.private);s.update(m.signedUpdate().signingBytes())
        return m.copy(signature=Base64.getEncoder().encodeToString(s.sign()))
    }
    private class Store : UpdateStore {
        override var installedBundleVersion = 71
        override var isRemotelyDisabled = false
        var blocked = false
        override fun isBlocked(manifest: BundleManifest) = blocked
        override fun install(manifest: BundleManifest, payload: ByteArray, download: (String, Int) -> ByteArray) { installedBundleVersion = manifest.bundleVersion }
    }
    private fun response(m: BundleManifest) = "{\"channel\":\"production\",\"manifest\":${m.signedUpdate().manifestJson(m.signature)}}".toByteArray()
    private suspend fun check(bytes: ByteArray, state: Store, body: ByteArray = payload): UpdateResult = BundleUpdater(
        "https://localhost/check", { _, _ -> body }, state, "9", 65536, 8000000, verifier,
        request(), { url, _ -> assertTrue(url.contains("installationId=install-123")); bytes },
    ).checkForUpdate()
    @Test fun `no selection preserves active and kill switch`() = runBlocking {
        val store=Store();store.isRemotelyDisabled=true
        assertEquals(UpdateResult.NoUpdate(71), check(byteArrayOf(),store));assertTrue(store.isRemotelyDisabled)
    }
    @Test fun `selected manifest still requires signature`() = runBlocking {
        val store=Store();val result=check(response(signed().copy(signature=null)),store) as UpdateResult.Failed
        assertEquals(UpdateFailure.FAILED_VERIFICATION,result.reason);assertEquals(71,store.installedBundleVersion)
    }
    @Test fun `selected wrong app and runtime independently rejected`() = runBlocking {
        val store=Store();assertTrue(check(response(signed(app="other.app")),store) is UpdateResult.Failed)
        assertTrue(check(response(signed(runtime="other")),store) is UpdateResult.IncompatibleRuntime)
        assertEquals(71,store.installedBundleVersion)
    }
    @Test fun `selected valid release requires hash before activation`() = runBlocking {
        val store=Store();assertTrue(check(response(signed()),store, "corrupt".toByteArray()) is UpdateResult.Failed)
        assertEquals(71,store.installedBundleVersion)
        assertTrue(check(response(signed()),store) is UpdateResult.Updated);assertEquals(72,store.installedBundleVersion)
    }
    @Test fun `device pause and quarantine remain authoritative over selected server release`() = runBlocking {
        val store=Store();store.blocked=true
        assertEquals(UpdateResult.NoUpdate(71),check(response(signed()),store));assertEquals(71,store.installedBundleVersion)
    }
    @Test fun `older server selection never forces rollback`() = runBlocking {
        val store=Store();store.installedBundleVersion=73
        assertEquals(UpdateResult.NoUpdate(73),check(response(signed()),store));assertEquals(73,store.installedBundleVersion)
    }
}
