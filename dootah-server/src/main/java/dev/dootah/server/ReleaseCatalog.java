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
        if (!root.isObject() || !root.path("publishers").isObject() || !root.path("releases").isArray()
                || root.path("releases").size() > 128) throw new IllegalArgumentException("Invalid catalog");
        List<Release> releases = new ArrayList<>(); Set<String> versions = new HashSet<>(); Set<String> identities = new HashSet<>();
        for (JsonNode item : root.get("releases")) {
            String channel = text(item, "channel"); require(CHANNELS.contains(channel));
            int percent = integer(item, "rolloutPercent"); require(percent >= 0 && percent <= 100);
            boolean paused = bool(item, "paused");
            Long min = versionBound(item, "minAppVersion"), max = versionBound(item, "maxAppVersion");
            require(min == null || max == null || min <= max);
            JsonNode m = item.get("manifest"); require(m != null && m.isObject());
            require(m.toString().getBytes(StandardCharsets.UTF_8).length <= 65536);
            int schema = integer(m, "schemaVersion"), version = integer(m, "bundleVersion");
            require(schema == 1 && version > 0);
            String app = text(m, "appId"), runtime = text(m, "runtimeVersion");
            require(app.matches("[A-Za-z0-9_.-]{1,200}") && runtime.length() <= 100);
            List<SignedImage> images = new ArrayList<>(); Set<String> ids = new HashSet<>();
            JsonNode imageNodes = m.get("images");
            if (imageNodes != null) {
                require(imageNodes.isArray() && imageNodes.size() <= 128);
                for (JsonNode image : imageNodes) {
                    String hash = hash(image), id = text(image, "id"); require(id.equals(hash) && ids.add(id));
                    images.add(new SignedImage(id, https(image), hash));
                }
            }
            SignedUpdate signed = new SignedUpdate(schema, app, runtime, version, bool(m, "enabled"), https(m), hash(m), images);
            byte[] rawKey = Base64.getDecoder().decode(text(root.get("publishers"), app)); require(rawKey.length == 32);
            byte[] prefix = HexFormat.of().parseHex("302a300506032b6570032100");
            byte[] key = Arrays.copyOf(prefix, prefix.length + rawKey.length);
            System.arraycopy(rawKey, 0, key, prefix.length, rawKey.length);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(key)));
            verifier.update(signed.signingBytes());
            require(verifier.verify(Base64.getDecoder().decode(text(m, "signature"))));
            String identity = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(signed.signingBytes()));
            // A signed release belongs to exactly one channel. No ambiguous version within a channel.
            require(identities.add(identity));
            require(versions.add(app + "\n" + runtime + "\n" + channel + "\n" + version));
            releases.add(new Release(app, runtime, version, channel, min, max, percent, paused, identity, m.deepCopy()));
        }
        return List.copyOf(releases);
    }
    private static Long versionBound(JsonNode item, String key) {
        JsonNode n = item.get(key); if (n == null || n.isNull()) return null;
        require(n.isIntegralNumber() && n.canConvertToLong() && n.longValue() >= 1); return n.longValue();
    }
    private static String text(JsonNode n, String key) {
        JsonNode value = n.get(key); require(value != null && value.isTextual() && !value.textValue().isBlank()); return value.textValue();
    }
    private static int integer(JsonNode n, String key) {
        JsonNode value = n.get(key); require(value != null && value.isIntegralNumber() && value.canConvertToInt()); return value.intValue();
    }
    private static boolean bool(JsonNode n, String key) {
        JsonNode value = n.get(key); require(value != null && value.isBoolean()); return value.booleanValue();
    }
    private static String hash(JsonNode n) {
        String hash = text(n, "sha256").toLowerCase(Locale.ROOT); require(hash.matches("[0-9a-f]{64}")); return hash;
    }
    private static String https(JsonNode n) {
        String value = text(n, "url"); URI uri = URI.create(value);
        require("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null); return value;
    }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid release catalog"); }
}
