package dev.dootah.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dootah.contract.SignedImage;
import dev.dootah.contract.SignedUpdate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Immutable request snapshot, loaded from a small atomically replaced local catalog.
 * Only public keys and already-signed manifests enter this service. No signing operation.
 */
@Component
public class ReleaseCatalog {
    public static final Set<String> CHANNELS = Set.of("development", "staging", "production");
    private final Path path;
    private final ObjectMapper mapper;
    public ReleaseCatalog(@Value("${dootah.catalog}") String path, ObjectMapper mapper) {
        this.path = Path.of(path); this.mapper = mapper;
    }
    public record Release(String appId, String runtimeVersion, int version, String channel,
                          Long minAppVersion, Long maxAppVersion, int rolloutPercent,
                          boolean paused, String identity, JsonNode manifest) { }
    public List<Release> read() throws IOException, GeneralSecurityException {
        if (Files.size(path) > 8 * 1024 * 1024) throw new IOException("Catalog too large");
        return validate(mapper.readTree(Files.readAllBytes(path)));
    }
    Path path() { return path; }
    public List<Release> validate(JsonNode root) throws GeneralSecurityException {
        PublicationRejection.require(root != null && root.isObject() && root.path("publishers").isObject()
                && root.path("releases").isArray() && root.path("releases").size() <= 128,
                "INVALID_CATALOG", "Server catalog must contain publishers and at most 128 releases");
        List<Release> releases = new ArrayList<>(); Set<String> versions = new HashSet<>(); Set<String> identities = new HashSet<>();
        for (JsonNode item : root.get("releases")) {
            String channel = text(item, "channel"); require(CHANNELS.contains(channel), "channel must be development, staging or production");
            int percent = integer(item, "rolloutPercent"); require(percent >= 0 && percent <= 100, "rolloutPercent must be between 0 and 100");
            boolean paused = bool(item, "paused");
            Long min = versionBound(item, "minAppVersion"), max = versionBound(item, "maxAppVersion");
            require(min == null || max == null || min <= max, "minAppVersion must not exceed maxAppVersion");
            JsonNode m = item.get("manifest"); require(m != null && m.isObject(), "manifest must be a JSON object");
            require(m.toString().getBytes(StandardCharsets.UTF_8).length <= 65536, "manifest exceeds 65536 bytes");
            int schema = integer(m, "schemaVersion"), version = integer(m, "bundleVersion");
            require(schema == 1, "schemaVersion must be 1");
            require(version > 0, "bundleVersion must be a positive integer");
            String app = text(m, "appId"), runtime = text(m, "runtimeVersion");
            require(app.matches("[A-Za-z0-9_.-]{1,200}"), "appId must contain 1 to 200 letters, digits, dots, underscores or hyphens");
            require(runtime.length() <= 100, "runtimeVersion must contain at most 100 characters");
            List<SignedImage> images = new ArrayList<>(); Set<String> ids = new HashSet<>();
            JsonNode imageNodes = m.get("images");
            if (imageNodes != null) {
                require(imageNodes.isArray() && imageNodes.size() <= 128, "images must be an array of at most 128 entries");
                for (JsonNode image : imageNodes) {
                    String hash = hash(image), id = text(image, "id"); require(id.equals(hash) && ids.add(id), "Each image id must equal its sha256 and be unique");
                    images.add(new SignedImage(id, https(image), hash));
                }
            }
            SignedUpdate signed = new SignedUpdate(schema, app, runtime, version, bool(m, "enabled"), https(m), hash(m), images);
            JsonNode publisher = root.get("publishers").get(app);
            PublicationRejection.require(publisher != null, "PUBLISHER_NOT_CONFIGURED",
                    "No publisher public key is configured for appId '" + app
                            + "'; configure publishers[appId] in the server catalog with the trusted public key");
            require(publisher.isTextual() && !publisher.textValue().isBlank(), "Publisher public key must be nonempty base64 text");
            byte[] rawKey = base64(publisher.textValue(), "publisher public key");
            require(rawKey.length == 32, "Publisher public key must decode to 32 raw Ed25519 bytes");
            byte[] prefix = HexFormat.of().parseHex("302a300506032b6570032100");
            byte[] key = Arrays.copyOf(prefix, prefix.length + rawKey.length);
            System.arraycopy(rawKey, 0, key, prefix.length, rawKey.length);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(key)));
            verifier.update(signed.signingBytes());
            byte[] signature = base64(text(m, "signature"), "signature");
            PublicationRejection.require(signature.length == 64 && verifier.verify(signature),
                    "SIGNATURE_INVALID", "Manifest signature does not match the configured publisher public key");
            String identity = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(signed.signingBytes()));
            // A signed release belongs to exactly one channel. No ambiguous version within a channel.
            require(identities.add(identity), "Catalog contains a duplicate signed release identity");
            require(versions.add(app + "\n" + runtime + "\n" + channel + "\n" + version), "Catalog contains a duplicate app/runtime/channel/bundleVersion");
            releases.add(new Release(app, runtime, version, channel, min, max, percent, paused, identity, m.deepCopy()));
        }
        return List.copyOf(releases);
    }
    private static Long versionBound(JsonNode item, String key) {
        JsonNode n = item.get(key); if (n == null || n.isNull()) return null;
        require(n.isIntegralNumber() && n.canConvertToLong() && n.longValue() >= 1, key + " must be a positive integer"); return n.longValue();
    }
    private static String text(JsonNode n, String key) {
        JsonNode value = n.get(key); require(value != null && value.isTextual() && !value.textValue().isBlank(), key + " must be nonempty text"); return value.textValue();
    }
    private static int integer(JsonNode n, String key) {
        JsonNode value = n.get(key); require(value != null && value.isIntegralNumber() && value.canConvertToInt(), key + " must be a 32-bit integer"); return value.intValue();
    }
    private static boolean bool(JsonNode n, String key) {
        JsonNode value = n.get(key); require(value != null && value.isBoolean(), key + " must be a boolean"); return value.booleanValue();
    }
    private static String hash(JsonNode n) {
        String hash = text(n, "sha256").toLowerCase(Locale.ROOT); require(hash.matches("[0-9a-f]{64}"), "sha256 must contain 64 hexadecimal characters"); return hash;
    }
    private static String https(JsonNode n) {
        String value = text(n, "url");
        final URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException e) { throw new PublicationRejection("INVALID_RELEASE_METADATA", "Artifact url must be a valid HTTPS URL without credentials"); }
        require("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null,
                "Artifact url must be a valid HTTPS URL without credentials"); return value;
    }
    private static byte[] base64(String value, String field) {
        try { return Base64.getDecoder().decode(value); }
        catch (IllegalArgumentException e) { throw new PublicationRejection("INVALID_RELEASE_METADATA", field + " must be valid base64"); }
    }
    private static void require(boolean valid, String message) {
        PublicationRejection.require(valid, "INVALID_RELEASE_METADATA", message);
    }
}
