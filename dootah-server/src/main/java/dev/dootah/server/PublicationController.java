package dev.dootah.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.util.Map;

@RestController
public class PublicationController {
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
        PublicationStore.require(bytes.length <= 65536);
        return mapper.readTree(bytes);
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
    public ResponseEntity<Void> conflict() { return ResponseEntity.status(409).build(); }
    @ExceptionHandler({IllegalArgumentException.class, java.security.GeneralSecurityException.class})
    public ResponseEntity<Void> invalid() { return ResponseEntity.badRequest().build(); }
    @ExceptionHandler(NoSuchFileException.class)
    public ResponseEntity<Void> missing() { return ResponseEntity.status(404).build(); }
}
