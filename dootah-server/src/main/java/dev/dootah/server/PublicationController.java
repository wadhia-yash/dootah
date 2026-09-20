package dev.dootah.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
public class PublicationController {
    private static final Logger log = LoggerFactory.getLogger(PublicationController.class);
    private final PublicationStore store;
    private final ObjectMapper mapper;
    private final String token;
    public PublicationController(PublicationStore store, ObjectMapper mapper,
            @Value("${dootah.publish-token:}") String token) {
        this.store = store; this.mapper = mapper; this.token = token;
    }
    private void authorize(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (token.length() < 32 || header == null || !MessageDigest.isEqual(
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    private JsonNode metadata(HttpServletRequest request) throws Exception {
        byte[] bytes = request.getInputStream().readNBytes(65537);
        PublicationRejection.require(bytes.length <= 65536, "METADATA_TOO_LARGE", "Publication metadata exceeds 65536 bytes");
        return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(bytes);
    }
    @PutMapping("/publish/artifacts/{hash}")
    public Map<String, String> upload(@PathVariable("hash") String hash, HttpServletRequest request) throws Exception {
        authorize(request); store.upload(hash, request.getInputStream());
        return Map.of("sha256", hash, "status", "stored");
    }
    @PostMapping("/publish/releases")
    public Map<String, String> register(HttpServletRequest request) throws Exception {
        authorize(request); return store.register(metadata(request));
    }
    @PutMapping("/publish/releases/{identity}/rollout")
    public Map<String, String> rollout(@PathVariable("identity") String identity, HttpServletRequest request) throws Exception {
        authorize(request); return store.rollout(identity, metadata(request));
    }
    @GetMapping("/artifacts/{hash}")
    public ResponseEntity<byte[]> artifact(@PathVariable("hash") String hash) throws Exception {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Cache-Control", "public, max-age=31536000, immutable").body(store.download(hash));
    }
    @ExceptionHandler(PublicationStore.Conflict.class)
    public ResponseEntity<Map<String, String>> conflict(PublicationStore.Conflict error) {
        return rejection(409, "IMMUTABLE_PUBLICATION_CONFLICT", error.getMessage());
    }
    @ExceptionHandler(PublicationRejection.class)
    public ResponseEntity<Map<String, String>> rejected(PublicationRejection error) {
        return rejection(400, error.code(), error.getMessage());
    }
    @ExceptionHandler(JsonProcessingException.class)
    public ResponseEntity<Map<String, String>> malformed() {
        return rejection(400, "MALFORMED_METADATA", "Publication metadata must be one valid JSON object with no duplicate fields");
    }
    @ExceptionHandler({IllegalArgumentException.class, java.security.GeneralSecurityException.class})
    public ResponseEntity<Map<String, String>> invalid() {
        // Parser/crypto exceptions may contain submitted data. Only deliberate diagnostics are public.
        return rejection(400, "INVALID_PUBLICATION", "Publication metadata, publisher key or signature is invalid");
    }
    @ExceptionHandler(NoSuchFileException.class)
    public ResponseEntity<Map<String, String>> missing(NoSuchFileException error) {
        if (error instanceof PublicationStore.MissingArtifact artifact)
            return rejection(404, "ARTIFACT_NOT_FOUND", "Artifact sha256 " + artifact.hash + " does not exist; upload it before registering the release");
        return rejection(404, "PUBLICATION_STORAGE_UNAVAILABLE", "Publication storage is missing; check the server catalog and artifact configuration");
    }
    private ResponseEntity<Map<String, String>> rejection(int status, String code, String message) {
        log.warn("Publication rejected: {}: {}", code, message);
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
