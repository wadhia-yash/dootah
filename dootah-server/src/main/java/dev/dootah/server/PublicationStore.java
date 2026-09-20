package dev.dootah.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.dootah.contract.BundleImages;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Local immutable objects, then one atomic catalog commit. No remote URL fetching or signing. */
@Component
public class PublicationStore {
    static final int MAX_ARTIFACT = 8 * 1024 * 1024;
    private final Path artifacts;
    private final String publicUrl;
    private final ReleaseCatalog catalog;
    private final ObjectMapper mapper;

    public PublicationStore(ReleaseCatalog catalog, ObjectMapper mapper,
            @Value("${dootah.artifacts:./artifacts}") String artifacts,
            @Value("${dootah.public-url:}") String publicUrl) {
        this.catalog = catalog; this.mapper = mapper; this.artifacts = Path.of(artifacts);
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }
    private static void require(boolean valid, String code, String message) {
        PublicationRejection.require(valid, code, message);
    }
    static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private Path artifact(String hash) {
        require(hash.matches("[0-9a-f]{64}"), "INVALID_ARTIFACT_HASH", "Artifact sha256 must contain 64 lowercase hexadecimal characters");
        return artifacts.resolve(hash);
    }
    public synchronized void upload(String hash, InputStream input) throws Exception {
        Path target = artifact(hash);
        byte[] bytes = input.readNBytes(MAX_ARTIFACT + 1);
        require(bytes.length > 0 && bytes.length <= MAX_ARTIFACT, "INVALID_ARTIFACT_SIZE", "Artifact must contain between 1 byte and 8 MiB");
        require(digest(bytes).equals(hash), "ARTIFACT_HASH_MISMATCH", "Artifact bytes do not match the supplied sha256");
        Files.createDirectories(artifacts);
        // Cross-process serialization also protects an immutable file from replacement.
        try (var c = FileChannel.open(artifacts.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = c.lock()) {
            if (Files.exists(target)) {
                if (!Arrays.equals(Files.readAllBytes(target), bytes)) throw new Conflict("Stored artifact differs from the uploaded bytes; immutable artifacts cannot be overwritten");
                return;
            }
            atomicWrite(target, bytes);
        }
    }
    public byte[] download(String hash) throws Exception {
        final byte[] bytes;
        try { bytes = Files.readAllBytes(artifact(hash)); }
        catch (NoSuchFileException e) { throw new MissingArtifact(hash); }
        require(bytes.length <= MAX_ARTIFACT && digest(bytes).equals(hash), "ARTIFACT_HASH_MISMATCH", "Stored artifact bytes do not match the supplied sha256"); return bytes;
    }
    public synchronized Map<String, String> register(JsonNode item) throws Exception {
        return mutate(root -> {
            require(item != null && item.isObject(), "INVALID_RELEASE_METADATA", "Release metadata must be a JSON object");
            // Validate signature and metadata independently before checking idempotency.
            ObjectNode single = root.deepCopy(); single.putArray("releases").add(item);
            var release = catalog.validate(single).get(0);
            verifyArtifacts(release.manifest());
            var existing = catalog.validate(root);
            for (var old : existing) {
                if (old.identity().equals(release.identity())) {
                    if (!old.channel().equals(release.channel()) || !old.manifest().equals(release.manifest())
                            || !Objects.equals(old.minAppVersion(), release.minAppVersion())
                            || !Objects.equals(old.maxAppVersion(), release.maxAppVersion()))
                        throw new Conflict("Release identity already exists with different channel or targeting metadata; published releases are immutable");
                    // Retrying publication never resets a later rollout/pause control change.
                    return Map.of("status", "existing", "identity", old.identity());
                }
                if (old.appId().equals(release.appId()) && old.runtimeVersion().equals(release.runtimeVersion())
                        && old.channel().equals(release.channel()) && old.version() == release.version())
                    throw new Conflict("bundleVersion " + release.version() + " already exists for this app/runtime/channel with different signed content; use a new bundleVersion");
            }
            root.withArray("releases").add(item.deepCopy());
            return Map.of("status", "registered", "identity", release.identity());
        });
    }
    public synchronized Map<String, String> rollout(String identity, JsonNode control) throws Exception {
        require(identity.matches("[0-9a-f]{64}"), "INVALID_RELEASE_IDENTITY", "Release identity must contain 64 lowercase hexadecimal characters");
        require(control != null && control.isObject() && control.size() == 2 && control.has("rolloutPercent") && control.has("paused"),
                "INVALID_ROLLOUT_METADATA", "Rollout metadata must contain only rolloutPercent and paused");
        return mutate(root -> {
            var releases = catalog.validate(root);
            for (int i = 0; i < releases.size(); i++) {
                var release = releases.get(i);
                if (!release.identity().equals(identity)) continue;
                verifyArtifacts(release.manifest());
                ObjectNode item = (ObjectNode) root.withArray("releases").get(i);
                item.set("rolloutPercent", control.get("rolloutPercent")); item.set("paused", control.get("paused"));
                return Map.of("status", "configured", "identity", identity);
            }
            throw new PublicationRejection("RELEASE_NOT_FOUND", "Release identity does not exist in the server catalog");
        });
    }
    private void verifyArtifacts(JsonNode manifest) throws Exception {
        var uri = java.net.URI.create(publicUrl);
        require("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                && uri.getQuery() == null && uri.getFragment() == null,
                "INVALID_PUBLIC_URL", "Server dootah.public-url must be a valid HTTPS URL without credentials, query or fragment");
        byte[] bundle = verifyArtifact(manifest);
        Set<String> images = new HashSet<>();
        for (JsonNode image : manifest.path("images")) { verifyArtifact(image); images.add(image.path("id").asText()); }
        require(BundleImages.INSTANCE.required(new String(bundle, java.nio.charset.StandardCharsets.UTF_8)).equals(images),
                "ARTIFACT_DEPENDENCIES_MISMATCH", "Bundle image dependencies do not match manifest images");
    }
    private byte[] verifyArtifact(JsonNode node) throws Exception {
        String hash = node.path("sha256").asText();
        require(node.path("url").asText().equals(publicUrl + "/artifacts/" + hash),
                "ARTIFACT_URL_MISMATCH", "Manifest artifact URL does not match server dootah.public-url and sha256; check the publishing origin");
        return download(hash);
    }
    private interface Mutation { Map<String, String> apply(ObjectNode root) throws Exception; }
    private Map<String, String> mutate(Mutation mutation) throws Exception {
        Path path = catalog.path().toAbsolutePath();
        try (var c = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = c.lock()) {
            require(Files.size(path) <= 8 * 1024 * 1024, "INVALID_CATALOG", "Server catalog exceeds 8 MiB");
            JsonNode parsed = mapper.readTree(Files.readAllBytes(path));
            catalog.validate(parsed);
            ObjectNode root = (ObjectNode) parsed;
            var result = mutation.apply(root);
            catalog.validate(root);
            byte[] bytes = mapper.writeValueAsBytes(root);
            require(bytes.length <= 8 * 1024 * 1024, "INVALID_CATALOG", "Server catalog exceeds 8 MiB");
            atomicWrite(path, bytes);
            return result;
        }
    }
    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path pending = Files.createTempFile(target.toAbsolutePath().getParent(), ".pending-", ".tmp");
        try {
            try (var out = new FileOutputStream(pending.toFile())) { out.write(bytes); out.getFD().sync(); }
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(pending); }
    }
    static class Conflict extends RuntimeException {
        Conflict(String message) { super(message); }
    }
    static class MissingArtifact extends NoSuchFileException {
        final String hash;
        MissingArtifact(String hash) { super(hash); this.hash = hash; }
    }
}
