package com.dootah.ota

import com.dootah.UpdateEventKind
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
import java.util.Properties

class ManualRollbackTest {
    @get:Rule val temporary = TemporaryFolder()
    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val verifier = ManifestVerifier("example.app", Base64.getEncoder().encodeToString(keys.public.encoded.takeLast(32).toByteArray()))
    private var interrupt = false
    private var time = 1000L
    private fun store() = ImageUpdateStore(temporary.root, {}, authenticate = {
        verifier.verify(it)
        if (it.runtimeVersion != "9" || !it.enabled) throw BundleVerificationException("Incompatible target")
    }, beforeStateCommit = { if (interrupt) throw IOException("Interrupted rename") }, now = { time++ })
    private fun bytes(v: Int, images: List<String> = emptyList()) = (BundleImages.header(images) + "var release=$v;").toByteArray()
    private fun signed(v: Int, payload: ByteArray = bytes(v), images: List<BundleImage> = emptyList(), runtime: String = "9"): BundleManifest {
        val m = testManifest(bundleVersion = v, runtimeVersion = runtime).copy(appId = "example.app", sha256 = sha256Hex(payload), images = images)
        val signer = Signature.getInstance("Ed25519").apply { initSign(keys.private); update(m.signedUpdate().signingBytes()) }
        return m.copy(signature = Base64.getEncoder().encodeToString(signer.sign()))
    }
    private fun stage(disk: ImageUpdateStore, v: Int) = disk.stage(signed(v), bytes(v)) { _, _ -> error("Offline") }
    private fun healthy(disk: ImageUpdateStore, v: Int) {
        stage(disk, v); disk.confirmHealthy(disk.prepareForLoad()!!.identity)
    }
    private fun abc(disk: ImageUpdateStore) { (61..63).forEach { healthy(disk, it) } }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid request accepted") } catch (e: Exception) { assertTrue(e is IllegalArgumentException || e is BundleVerificationException || e is IOException) }
    }
    private val record get() = temporary.root.resolve("active.properties")
    private fun editRecord(edit: (Properties) -> Unit) {
        val p = Properties().apply { record.inputStream().use { load(it) } }; edit(p)
        record.outputStream().use { p.store(it, null) }
    }

    @Test fun `three healthy releases record candidate active healthy timestamps and exact identities`() {
        val disk = store(); abc(disk)
        val events = store().state().history
        assertEquals(9, events.size)
        (61..63).forEachIndexed { i, v ->
            val group = events.subList(i * 3, i * 3 + 3)
            assertEquals(listOf(UpdateEventKind.CANDIDATE, UpdateEventKind.ACTIVE, UpdateEventKind.HEALTHY), group.map { it.kind })
            assertTrue(group.all { it.bundleVersion == v && it.contentHash == sha256Hex(bytes(v)) })
            assertEquals(1, group.map { it.identity }.distinct().size)
        }
        assertTrue(events.zipWithNext().all { (a,b) -> a.timestampMillis < b.timestampMillis })
        assertEquals(listOf(63,62,61), disk.state().retained.map { it.version })
    }
    @Test fun `manual C to A selects retained signed healthy A and persists operator reason`() {
        val disk = store(); abc(disk)
        val c = disk.state().active!!
        val target = disk.rollbackTo(61, "Operator chose earlier reading hint")
        val state = store().state()
        assertEquals(target, state.active); assertEquals(target, state.lastKnownGood)
        assertTrue(state.confirmedHealthy); assertTrue(state.paused); assertNull(state.candidate); assertNull(state.attempt)
        val event = state.history.last()
        assertEquals(UpdateEventKind.MANUAL_ROLLBACK, event.kind)
        assertEquals(61, event.bundleVersion); assertEquals(c.identity, event.previousIdentity)
        assertEquals(63, event.previousVersion); assertEquals("Operator chose earlier reading hint", event.reason)
    }
    @Test fun `manual rollback reads retained bundle and assets offline without copies`() {
        val disk = store(); val image = "A image".toByteArray(); val hash = sha256Hex(image)
        val payload = bytes(61, listOf(hash))
        disk.stage(signed(61,payload,listOf(BundleImage(hash,"https://example.test/image",hash))),payload) { _, _ -> image }
        disk.confirmHealthy(disk.prepareForLoad()!!.identity)
        healthy(disk,62);healthy(disk,63)
        val before = temporary.root.walkTopDown().filter { it.isFile && it != record }.associate { it.path to it.readBytes().toList() }
        store().rollbackTo(61,"offline restore")
        repeat(3) {
            val restarted = store();assertEquals(61,restarted.prepareForLoad()!!.version)
            assertEquals(payload.decodeToString(),restarted.readBundle());assertArrayEquals(image,restarted.readImage(hash))
        }
        assertEquals(before,temporary.root.walkTopDown().filter { it.isFile && it != record }.associate { it.path to it.readBytes().toList() })
    }
    @Test fun `unknown target and unconfirmed candidate are rejected without changing state`() {
        val disk = store();abc(disk);stage(disk,64)
        val before = record.readBytes()
        rejected { disk.rollbackTo(999,"unknown") };rejected { disk.rollbackTo(64,"not healthy") }
        assertArrayEquals(before,record.readBytes())
    }
    @Test fun `quarantined retained target cannot be manually activated`() {
        val disk = store();abc(disk)
        editRecord { p -> p["quarantine.count"]="1";p["quarantine.0.hash"]=sha256Hex(bytes(61));p["quarantine.0.version"]="61";p["quarantine.0.reason"]="execution" }
        val before=record.readBytes();rejected { disk.rollbackTo(61,"unsafe") }
        assertArrayEquals(before,record.readBytes());assertEquals(63,disk.state().active!!.version)
    }
    @Test fun `unsigned retained proof is rejected`() {
        val disk=store();abc(disk);editRecord { it.remove("retained.2.manifest") }
        val before=record.readBytes();rejected { disk.rollbackTo(61,"unsigned") };assertArrayEquals(before,record.readBytes())
    }
    @Test fun `invalid retained signature is rejected`() {
        val disk=store();abc(disk)
        val m=signed(61).copy(signature=Base64.getEncoder().encodeToString(ByteArray(64)))
        editRecord { it["retained.2.manifest"]=Base64.getEncoder().encodeToString(m.signedUpdate().manifestJson(m.signature).toByteArray()) }
        val before=record.readBytes();rejected { disk.rollbackTo(61,"invalid signature") };assertArrayEquals(before,record.readBytes())
    }
    @Test fun `trusted signed incompatible runtime target is rejected`() {
        val disk=store();abc(disk);val m=signed(61,runtime="8")
        editRecord { it["retained.2.manifest"]=Base64.getEncoder().encodeToString(m.signedUpdate().manifestJson(m.signature).toByteArray()) }
        val before=record.readBytes();rejected { disk.rollbackTo(61,"incompatible") };assertArrayEquals(before,record.readBytes())
    }
    @Test fun `corrupt retained bundle and missing assets are rejected before state change`() {
        val disk=store();val image="image".toByteArray();val hash=sha256Hex(image);val payload=bytes(61,listOf(hash))
        disk.stage(signed(61,payload,listOf(BundleImage(hash,"https://example.test/image",hash))),payload) { _, _ -> image }
        disk.confirmHealthy(disk.prepareForLoad()!!.identity);healthy(disk,62)
        val before=record.readBytes();val file=temporary.root.resolve("bundles/${sha256Hex(payload)}.js")
        file.writeText("corrupt");rejected { disk.rollbackTo(61,"bad bytes") };assertArrayEquals(before,record.readBytes())
        file.writeBytes(payload);temporary.root.resolve("images/$hash").delete()
        rejected { disk.rollbackTo(61,"missing image") };assertArrayEquals(before,record.readBytes())
    }
    @Test fun `automatic failure quarantine and rollback reasons are distinct from manual rollback`() {
        val disk=store();healthy(disk,61);healthy(disk,62);stage(disk,63)
        val bad=disk.prepareForLoad()!!;disk.failUnconfirmed(bad.identity,"execution")
        disk.rollbackTo(61,"operator choice")
        val events=store().state().history
        assertTrue(events.any { it.kind==UpdateEventKind.FAILED_QUARANTINED && it.bundleVersion==63 && it.reason=="execution" })
        assertTrue(events.any { it.kind==UpdateEventKind.AUTOMATIC_ROLLBACK && it.bundleVersion==62 && it.previousVersion==63 && it.reason=="execution" })
        assertEquals(UpdateEventKind.MANUAL_ROLLBACK,events.last().kind)
    }
    @Test fun `history and retained releases and files stay bounded`() {
        val disk=store();(1..20).forEach { healthy(disk,it) }
        assertEquals(ImageUpdateStore.MAX_HISTORY,store().state().history.size)
        assertEquals(listOf(20,19,18),store().state().retained.map { it.version })
        assertEquals(3,temporary.root.resolve("bundles").listFiles()!!.size)
        rejected { disk.rollbackTo(17,"expired") }
        disk.rollbackTo(18,"oldest retained");disk.cleanup()
        assertEquals(18,store().prepareForLoad()!!.version)
        assertEquals(3,temporary.root.resolve("bundles").listFiles()!!.size)
    }
    @Test fun `cleanup protects candidate active LKG shared assets and manual target`() {
        val disk=store();abc(disk);stage(disk,64);disk.prepareForLoad();stage(disk,65)
        disk.cleanup()
        for (v in 61..65) assertTrue(temporary.root.resolve("bundles/${sha256Hex(bytes(v))}.js").exists())
        disk.rollbackTo(61,"retain A");disk.cleanup()
        assertTrue(temporary.root.resolve("bundles/${sha256Hex(bytes(61))}.js").exists())
        assertFalse(temporary.root.resolve("bundles/${sha256Hex(bytes(64))}.js").exists())
        assertFalse(temporary.root.resolve("bundles/${sha256Hex(bytes(65))}.js").exists())
        assertEquals(61,store().state().lastKnownGood!!.version)
    }
    @Test fun `cleanup does not delete a partial download during a concurrent health commit`() {
        val disk=store();healthy(disk,61);stage(disk,62);val active=disk.prepareForLoad()!!
        val a="image A".toByteArray();val b="image B".toByteArray();val ah=sha256Hex(a);val bh=sha256Hex(b)
        val payload=bytes(63,listOf(ah,bh))
        val images=listOf(BundleImage(ah,"https://example.test/a",ah),BundleImage(bh,"https://example.test/b",bh))
        disk.stage(signed(63,payload,images),payload) { url,_ ->
            if(url.endsWith("a")) a else { disk.confirmHealthy(active.identity);assertTrue(temporary.root.resolve("images/$ah").exists());b }
        }
        assertArrayEquals(a,disk.readImage(ah));assertArrayEquals(b,disk.readImage(bh))
        assertEquals(63,disk.state().candidate!!.version)
    }
    @Test fun `interrupted manual rollback preserves prior active history and recovery files`() {
        val disk=store();abc(disk);val before=record.readBytes();interrupt=true
        try { disk.rollbackTo(61,"interrupt");fail() } catch (_:IOException) {} finally { interrupt=false }
        assertArrayEquals(before,record.readBytes());assertEquals(63,store().prepareForLoad()!!.version)
        (61..63).forEach { assertTrue(temporary.root.resolve("bundles/${sha256Hex(bytes(it))}.js").exists()) }
        store().rollbackTo(61,"retry");assertEquals(61,store().prepareForLoad()!!.version)
    }
    @Test fun `newer server cannot undo pause and explicit resume allows normal checks`() = runBlocking {
        val disk=store();abc(disk);disk.rollbackTo(61,"operator hold")
        val manifest=signed(63);var downloads=0
        val adapter=object:UpdateStore {
            override val installedBundleVersion get()=disk.version
            override var isRemotelyDisabled=false
            override fun isBlocked(manifest:BundleManifest)=disk.isBlocked(manifest)
            override fun install(manifest:BundleManifest,payload:ByteArray,download:(String,Int)->ByteArray)=disk.stage(manifest,payload,download)
        }
        val updater=BundleUpdater("https://example.test/manifest",{url,_->
            if(url.endsWith("manifest")) manifest.signedUpdate().manifestJson(manifest.signature).toByteArray()
            else { downloads++;bytes(63) }
        },adapter,"9",65536,8000000,verifier)
        repeat(3) { assertTrue(updater.checkForUpdate() is UpdateResult.NoUpdate) }
        assertEquals(0,downloads);assertTrue(store().state().paused);assertEquals(61,store().prepareForLoad()!!.version)
        rejected { stage(store(),64) }
        disk.resumeUpdates();assertFalse(store().state().paused)
        assertEquals(UpdateEventKind.UPDATES_RESUMED,store().state().history.last().kind)
        assertTrue(updater.checkForUpdate() is UpdateResult.Updated);assertEquals(1,downloads)
    }
    @Test fun `rollback reason is bounded and round trips special characters safely`() {
        val disk=store();abc(disk);rejected { disk.rollbackTo(61,"x".repeat(201)) }
        disk.rollbackTo(61,"Operator = requested\nprevious \\ version ☀")
        assertEquals("Operator = requested\nprevious \\ version ☀",store().state().history.last().reason)
    }
    @Test fun `schema three migration retains its confirmed LKG without inventing history`() {
        val disk=store();healthy(disk,61)
        editRecord { p ->
            p["stateSchema"]="3"
            p.keys.toList().filter { it.toString().startsWith("retained.") || it.toString().startsWith("history.") || it=="paused" }.forEach { p.remove(it) }
        }
        assertEquals(listOf(61),store().state().retained.map { it.version })
        assertTrue(store().state().history.isEmpty());store().rollbackTo(61,"migrated")
        assertEquals(61,store().prepareForLoad()!!.version)
    }
    @Test fun `cleanup keeps shared image until the last retained reference expires`() {
        val disk=store();val image="shared".toByteArray();val hash=sha256Hex(image)
        for(v in 1..2) {
            val payload=bytes(v,listOf(hash))
            disk.stage(signed(v,payload,listOf(BundleImage(hash,"https://example.test/shared",hash))),payload) { _,_->image }
            disk.confirmHealthy(disk.prepareForLoad()!!.identity)
        }
        healthy(disk,3);healthy(disk,4)
        assertArrayEquals(image,store().readImage(hash))
        assertFalse(temporary.root.resolve("bundles/${sha256Hex(bytes(1,listOf(hash)))}.js").exists())
        healthy(disk,5)
        assertFalse(temporary.root.resolve("images/$hash").exists())
        assertEquals(3,temporary.root.resolve("bundles").listFiles()!!.size)
    }
    @Test fun `wrong app retained proof cannot be selected`() {
        val disk=store();abc(disk)
        val m=signed(61).copy(appId="another.app")
        val signer=Signature.getInstance("Ed25519").apply { initSign(keys.private);update(m.signedUpdate().signingBytes()) }
        val signature=Base64.getEncoder().encodeToString(signer.sign())
        editRecord { it["retained.2.manifest"]=Base64.getEncoder().encodeToString(m.signedUpdate().manifestJson(signature).toByteArray()) }
        val before=record.readBytes();rejected { disk.rollbackTo(61,"wrong app") };assertArrayEquals(before,record.readBytes())
    }

}
