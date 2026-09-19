package com.dootah.ota

import dev.dootah.contract.BundleImages
import com.dootah.UpdateHistoryEvent
import com.dootah.UpdateEventKind
import java.io.File
import java.io.FileOutputStream
import java.util.Properties

/** Immutable payloads plus one atomic candidate/active/last-known-good record. */
internal class ImageUpdateStore(
    private val directory: File,
    private val validateImage: (ByteArray) -> Unit,
    private val report: (String) -> Unit = {},
    private val authenticate: ((BundleManifest) -> Unit)? = null,
    private val beforeStateCommit: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val activeFile get() = File(directory, "active.properties")
    private fun imageFile(hash: String) = File(directory, "images/$hash")
    private fun bundleFile(hash: String) = File(directory, "bundles/$hash.js")

    data class Update(val version: Int, val hash: String, val images: Set<String>, val proof: String?) {
        val identity: String get() = sha256Hex("$version:$hash:${proof.orEmpty()}".toByteArray())
    }
    data class State(
        val candidate: Update? = null,
        val active: Update? = null,
        val lastKnownGood: Update? = null,
        val confirmedHealthy: Boolean = false,
        val attempt: String? = null,
        val quarantine: List<Failure> = emptyList(),
        val retained: List<Update> = emptyList(),
        val history: List<UpdateHistoryEvent> = emptyList(),
        val paused: Boolean = false,
    )
    data class Failure(val hash: String, val version: Int, val reason: String)
    // An in-process reload is not another process launch. Never clears the durable marker.
    private var attemptedHere: String? = null
    // Downloads run outside the metadata lock. Cleanup must pin their partial files.
    private val downloading = mutableMapOf<String, Int>()

    val exists: Boolean get() = activeFile.isFile
    val version: Int get() = state().let { it.candidate ?: it.active }?.version ?: ASSET_BUNDLE_VERSION

    fun stage(manifest: BundleManifest, payload: ByteArray, download: (String, Int) -> ByteArray) {
        authenticate?.invoke(manifest)
        checkAllowed(manifest)
        if (sha256Hex(payload) != manifest.sha256) {
            throw BundleVerificationException("Bundle hash mismatch")
        }
        val required = try { BundleImages.required(payload.decodeToString()) }
        catch (e: IllegalArgumentException) { throw BundleVerificationException(e.message ?: "Invalid dependencies", e) }
        if (required != manifest.images.map { it.id }.toSet()) {
            throw BundleVerificationException("Manifest images do not match bundle dependencies")
        }
        val pins = required + manifest.sha256
        synchronized(this) { pins.forEach { downloading[it] = (downloading[it] ?: 0) + 1 } }
        try {
            manifest.images.forEach { image ->
                if (image.id != image.sha256 || !Regex("[0-9a-f]{64}").matches(image.id)) {
                    throw BundleVerificationException("Invalid image identity")
                }
                val file = imageFile(image.id)
                val cached = file.takeIf { it.isFile }?.readBytes()
                if (cached == null || !valid(cached, image.sha256)) {
                    report("downloading image ${image.id}")
                    val bytes = download(image.url, MAX_IMAGE_BYTES)
                    verify(bytes, image.sha256)
                    atomicWrite(file, bytes)
                    report("image hash and decode verified: ${image.id}")
                } else {
                    report("reusing verified cached image: ${image.id}")
                }
            }
            // Recheck every dependency before switching the only mutable pointer.
            required.forEach { readImage(it) }
            atomicWrite(bundleFile(manifest.sha256), payload)
            val proof = if (authenticate != null) java.util.Base64.getEncoder().encodeToString(
                manifest.signedUpdate().manifestJson(manifest.signature).toByteArray()
            ) else null
            synchronized(this) {
                checkAllowed(manifest)
                val candidate = Update(manifest.bundleVersion, manifest.sha256, required, proof)
                save(state().copy(candidate = candidate).event(UpdateEventKind.CANDIDATE, candidate))
            }
            report("CANDIDATE bundle ${manifest.bundleVersion} with ${required.size} verified images")
        } finally {
            synchronized(this) {
                pins.forEach { hash ->
                    val count = downloading.getValue(hash) - 1
                    if (count == 0) downloading.remove(hash) else downloading[hash] = count
                }
                cleanup()
            }
        }
    }

    /** Called under the engine lock, immediately before loading a new isolate. */
    @Synchronized fun activateCandidate(): Update? {
        val previous = state()
        val candidate = previous.candidate ?: return previous.active
        readBundle(candidate) // Recheck complete payloads and signed proof at the transition.
        check(!blocked(previous, candidate.hash)) { "Update quarantined or quarantine full" }
        save(previous.copy(candidate = null, active = candidate, confirmedHealthy = false, attempt = null)
            .event(UpdateEventKind.ACTIVE, candidate))
        report("ACTIVE bundle ${candidate.version}; last-known-good=${previous.lastKnownGood?.version}")
        return candidate
    }

    @Synchronized fun confirmHealthy(identity: String): Boolean {
        val previous = state()
        val active = previous.active ?: return false
        if (active.identity != identity || previous.confirmedHealthy) return false
        readBundle(active)
        save(previous.copy(lastKnownGood = active, confirmedHealthy = true, attempt = null,
            retained = (listOf(active) + previous.retained.filter { it.identity != active.identity }).take(MAX_RETAINED))
            .event(UpdateEventKind.HEALTHY, active))
        report("LAST_KNOWN_GOOD bundle ${active.version}; remote native composition ready")
        return true
    }

    /** One unconfirmed attempt. Selection and its marker commit before any JS executes. */
    @Synchronized fun prepareForLoad(): Update? {
        var current = state()
        cleanup()
        if (current.attempt != null && current.attempt != attemptedHere) {
            failUnconfirmed(current.attempt, "unfinished_attempt")
            current = state()
        }
        val selected = current.candidate ?: current.active ?: return null
        if (current.candidate != null) {
            check(!blocked(current, selected.hash)) { "Update quarantined or quarantine full" }
            readBundle(selected)
            current = current.copy(candidate = null, active = selected, confirmedHealthy = false, attempt = null)
                .event(UpdateEventKind.ACTIVE, selected)
        }
        if (!current.confirmedHealthy) {
            current = current.copy(attempt = selected.identity)
            save(current)
            attemptedHere = selected.identity
            report("ACTIVE bundle ${selected.version}; attempt=1; last-known-good=${current.lastKnownGood?.version}")
        }
        return selected
    }

    /** Quarantine and recovery selection are a single atomic metadata transition. */
    @Synchronized fun failUnconfirmed(identity: String, reason: String): Boolean {
        val previous = state()
        val failed = previous.active ?: return false
        if (failed.identity != identity || previous.confirmedHealthy) return false
        require(reason in setOf("unfinished_attempt", "initialization", "execution", "readiness", "payload"))
        val quarantine = if (previous.quarantine.any { it.hash == failed.hash }) previous.quarantine
            else previous.quarantine + Failure(failed.hash, failed.version, reason)
        require(quarantine.size <= MAX_QUARANTINE) { "Quarantine capacity exceeded" }
        // Never select corrupt/missing recovery bytes, and never copy them.
        val recovery = previous.lastKnownGood?.takeIf { update ->
            quarantine.none { it.hash == update.hash } &&
                try { readBundle(update); true } catch (_: Exception) { false }
        }
        save(previous.copy(
            active = recovery, confirmedHealthy = recovery != null, attempt = null,
            lastKnownGood = recovery,
            candidate = previous.candidate?.takeIf { candidate -> quarantine.none { it.hash == candidate.hash } },
            quarantine = quarantine,
            retained = previous.retained.filter { update -> quarantine.none { it.hash == update.hash } },
        ).event(UpdateEventKind.FAILED_QUARANTINED, failed, reason)
            .event(UpdateEventKind.AUTOMATIC_ROLLBACK, recovery, reason, failed))
        report("ROLLBACK bundle ${failed.version} -> ${recovery?.version ?: "APK"}; reason=$reason; QUARANTINED ${failed.hash}")
        return true
    }

    /** Only native-confirmed retained descriptors qualify; raw paths are never accepted. */
    @Synchronized fun rollbackTo(version: Int, reason: String): Update {
        require(reason.isNotBlank() && reason.length <= 200) { "Rollback reason must be 1–200 characters" }
        val previous = state()
        val target = previous.retained.filter { it.version == version }.singleOrNull()
            ?: throw IllegalArgumentException("Unknown or ambiguous retained healthy version: $version")
        require(previous.quarantine.none { it.hash == target.hash }) { "Target is quarantined" }
        readBundle(target)
        save(previous.copy(candidate = null, active = target, lastKnownGood = target,
            confirmedHealthy = true, attempt = null, paused = true)
            .event(UpdateEventKind.MANUAL_ROLLBACK, target, reason, previous.active))
        report("MANUAL_ROLLBACK ${previous.active?.version} -> ${target.version}; updates paused")
        return target
    }

    @Synchronized fun resumeUpdates() {
        val previous = state()
        if (previous.paused) save(previous.copy(paused = false)
            .event(UpdateEventKind.UPDATES_RESUMED, previous.active, "operator_resume"))
    }

    private fun State.event(kind: UpdateEventKind, update: Update?, reason: String = "", from: Update? = null) =
        copy(history = (history + UpdateHistoryEvent(now(), kind, update?.version, update?.identity,
            update?.hash, reason, from?.version, from?.identity)).takeLast(MAX_HISTORY))

    /** Called after commits/startup and failed staging. Never deletes metadata or live files. */
    @Synchronized fun cleanup() {
        try {
            val current = state()
            directory.listFiles()?.filter { it.isFile && it.name.startsWith("pending-") && it.name.endsWith(".tmp") }
                ?.forEach { it.delete() }
            val live = current.retained + listOfNotNull(current.candidate, current.active, current.lastKnownGood)
            val bundles = live.map { it.hash }.toSet() + downloading.keys
            val images = live.flatMap { it.images }.toSet() + downloading.keys
            File(directory, "bundles").listFiles()?.filter { it.isFile && (downloading.isEmpty() || !it.name.startsWith("pending-")) && it.name.removeSuffix(".js") !in bundles }
                ?.forEach { if (!it.delete()) report("Cleanup deferred: ${it.name}") }
            File(directory, "images").listFiles()?.filter { it.isFile && (downloading.isEmpty() || !it.name.startsWith("pending-")) && it.name !in images }
                ?.forEach { if (!it.delete()) report("Cleanup deferred: ${it.name}") }
        } catch (_: Exception) {
            // Corrupt metadata or unavailable storage is not permission to delete recovery files.
            report("Cleanup deferred: cannot read a valid lifecycle record")
        }
    }

    @Synchronized fun isBlocked(manifest: BundleManifest): Boolean = blocked(state(), manifest.sha256)
    private fun blocked(state: State, hash: String) =
        state.paused || state.quarantine.any { it.hash == hash } || state.quarantine.size >= MAX_QUARANTINE
    private fun checkAllowed(manifest: BundleManifest) {
        if (isBlocked(manifest)) throw BundleVerificationException("Updates paused, update quarantined, or quarantine capacity reached")
    }

    fun readBundle(): String = readBundle(state().active ?: error("No active update"))

    fun readBundle(update: Update): String {
        authenticate(update)
        requireHash(update.hash)
        val payload = bundleFile(update.hash).readBytes()
        if (sha256Hex(payload) != update.hash) throw BundleVerificationException("Stored bundle hash mismatch")
        val source = payload.decodeToString()
        val required = BundleImages.required(source)
        if (required != update.images) throw BundleVerificationException("Stored image dependencies disagree")
        required.forEach { readImage(it) }
        return source
    }

    /** Re-verifies persisted bytes before each use, including after an offline restart. */
    fun readImage(hash: String): ByteArray {
        requireHash(hash)
        val bytes = imageFile(hash).readBytes()
        verify(bytes, hash)
        return bytes
    }

    @Synchronized fun delete() {
        cached = null
        cachedFrom = null
        if (activeFile.exists() && !activeFile.delete()) error("Cannot delete update record")
    }

    /**
     * The last state read, and the file it was read from.
     *
     * Reading the state is not cheap: it parses the record and verifies the
     * publisher's signature over every proof in it -- the active update, the
     * last known good, the candidate and each retained version, up to eight
     * Ed25519 verifications. On a mid-range device that is tens of milliseconds
     * of pure computation.
     *
     * It was being done on every call, and `status()` calls it, and every
     * intercepted screen asks for the status when it finishes rendering. An app
     * with forty screens therefore verified a few hundred signatures during its
     * first composition, on the main thread, and was killed for not responding.
     *
     * A file that has not changed cannot have a different state, so it is read
     * once. Identified by the file's length and modification time rather than
     * only by this object's own writes, so a record replaced by another process
     * -- an operator command, a second process of the same app -- is noticed
     * rather than served from a stale copy.
     */
    private var cached: State? = null
    private var cachedFrom: Pair<Long, Long>? = null

    /** Runtime-9 signing records migrate as unconfirmed active, never inferred healthy. */
    @Synchronized fun state(): State {
        if (!activeFile.isFile) return State()
        val stamp = activeFile.length() to activeFile.lastModified()
        cached?.let { if (cachedFrom == stamp) return it }
        return read().also { cached = it; cachedFrom = stamp }
    }

    private fun read(): State {
        val properties = Properties().apply { activeFile.inputStream().use { load(it) } }
        fun update(prefix: String): Update? {
            val version = properties.getProperty(prefix + "version") ?: return null
            return Update(version.toInt(), properties.getProperty(prefix + "bundle") ?: error("Missing bundle"),
                properties.getProperty(prefix + "images", "").split(',').filter { it.isNotEmpty() }.toSet(),
                properties.getProperty(prefix + "manifest")).also { authenticate(it) }
        }
        val schema = properties.getProperty("stateSchema")
        require(schema == null || schema == "2" || schema == "3" || schema == "4") { "Unsupported update state" }
        val active = update("")
        val lkg = update("lkg.")
        val healthy = properties.getProperty("healthy", "false").toBooleanStrict()
        require(!healthy || (active != null && active == lkg)) { "Invalid health record" }
        val attempt = properties.getProperty("attempt")
        require(attempt == null || (!healthy && active?.identity == attempt)) { "Invalid attempt record" }
        val count = properties.getProperty("quarantine.count", "0").toInt()
        require(count in 0..MAX_QUARANTINE)
        val quarantine = (0 until count).map { index ->
            val prefix = "quarantine.$index."
            Failure(properties.getProperty(prefix + "hash").also { requireHash(it) },
                properties.getProperty(prefix + "version").toInt(), properties.getProperty(prefix + "reason"))
        }
        require(quarantine.map { it.hash }.distinct().size == count)
        require(active == null || quarantine.none { it.hash == active.hash })
        val retainedCount = properties.getProperty("retained.count")?.toInt()
        require(retainedCount == null || retainedCount in 0..MAX_RETAINED)
        // Old records already prove LKG was confirmed by native code; no history is invented.
        val retained = if (retainedCount == null) listOfNotNull(lkg)
            else (0 until retainedCount).map { update("retained.$it.") ?: error("Missing retained proof") }
        require(retained.map { it.identity }.distinct().size == retained.size)
        val historyCount = properties.getProperty("history.count", "0").toInt()
        require(historyCount in 0..MAX_HISTORY)
        val history = (0 until historyCount).map { index ->
            val prefix = "history.$index."
            UpdateHistoryEvent(properties.getProperty(prefix + "time").toLong(),
                UpdateEventKind.valueOf(properties.getProperty(prefix + "kind")),
                properties.getProperty(prefix + "version")?.toInt(), properties.getProperty(prefix + "identity"),
                properties.getProperty(prefix + "hash"),
                properties.getProperty(prefix + "reason")?.let { String(java.util.Base64.getDecoder().decode(it), Charsets.UTF_8) } ?: "",
                properties.getProperty(prefix + "fromVersion")?.toInt(), properties.getProperty(prefix + "fromIdentity"))
        }
        return State(update("candidate."), active, lkg, healthy, attempt, quarantine, retained, history,
            properties.getProperty("paused", "false").toBooleanStrict())
    }

    private fun authenticate(update: Update) {
        requireHash(update.hash)
        update.images.forEach { requireHash(it) }
        if (authenticate == null) return
        try {
            val manifest = BundleManifestParser.parse(String(java.util.Base64.getDecoder().decode(
                update.proof ?: error("Missing stored signature")
            ), Charsets.UTF_8))
            authenticate.invoke(manifest)
            if (update.version != manifest.bundleVersion || update.hash != manifest.sha256 ||
                update.images != manifest.images.map { it.id }.toSet()) {
                throw BundleVerificationException("Stored update record does not match signed manifest")
            }
        } catch (e: Exception) {
            throw BundleVerificationException("Stored publisher proof rejected", e)
        }
    }

    private fun save(state: State) {
        // Before the write, not after: a failed write must not leave the cache
        // describing a record that is no longer there, and two writes within
        // one clock tick must not look like no write at all.
        cached = null
        cachedFrom = null
        val record = buildString {
            append("stateSchema=4\nhealthy=${state.confirmedHealthy}\npaused=${state.paused}\n")
            state.attempt?.let { append("attempt=$it\n") }
            append("quarantine.count=${state.quarantine.size}\n")
            state.quarantine.forEachIndexed { index, failure ->
                append("quarantine.$index.hash=${failure.hash}\nquarantine.$index.version=${failure.version}\nquarantine.$index.reason=${failure.reason}\n")
            }
            fun update(prefix: String, value: Update?) {
                if (value == null) return
                append("${prefix}version=${value.version}\n${prefix}bundle=${value.hash}\n")
                append("${prefix}images=${value.images.sorted().joinToString(",")}\n")
                value.proof?.let { append("${prefix}manifest=$it\n") }
            }
            update("", state.active)
            update("candidate.", state.candidate)
            update("lkg.", state.lastKnownGood)
            append("retained.count=${state.retained.size}\n")
            state.retained.forEachIndexed { index, value -> update("retained.$index.", value) }
            append("history.count=${state.history.size}\n")
            state.history.forEachIndexed { index, value ->
                val prefix = "history.$index."
                append("${prefix}time=${value.timestampMillis}\n${prefix}kind=${value.kind.name}\n")
                value.bundleVersion?.let { append("${prefix}version=$it\n") }
                value.identity?.let { append("${prefix}identity=$it\n") }
                value.contentHash?.let { append("${prefix}hash=$it\n") }
                append("${prefix}reason=${java.util.Base64.getEncoder().encodeToString(value.reason.toByteArray(Charsets.UTF_8))}\n")
                value.previousVersion?.let { append("${prefix}fromVersion=$it\n") }
                value.previousIdentity?.let { append("${prefix}fromIdentity=$it\n") }
            }
        }
        atomicWrite(activeFile, record.toByteArray())
        cleanup()
    }
    private fun requireHash(hash: String) {
        if (!Regex("[0-9a-f]{64}").matches(hash)) throw BundleVerificationException("Invalid stored hash")
    }
    private fun valid(bytes: ByteArray, hash: String): Boolean =
        try { verify(bytes, hash); true } catch (_: BundleVerificationException) { false }
    private fun verify(bytes: ByteArray, hash: String) {
        if (bytes.size > MAX_IMAGE_BYTES || sha256Hex(bytes) != hash) {
            throw BundleVerificationException("Image hash mismatch: $hash")
        }
        try { validateImage(bytes) }
        catch (e: Exception) { throw BundleVerificationException("Corrupt or unsupported image: $hash", e) }
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        file.parentFile!!.mkdirs()
        val temporary = File.createTempFile("pending-", ".tmp", file.parentFile)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            if (file == activeFile) beforeStateCommit()
            if (!temporary.renameTo(file)) throw java.io.IOException("Cannot save ${file.name}")
        } finally { temporary.delete() }
    }

    companion object {
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        const val MAX_QUARANTINE = 32
        const val MAX_RETAINED = 3
        const val MAX_HISTORY = 32
    }
}
