package dev.dootah.server;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
public class UpdateController {
    private final ReleaseCatalog catalog;
    public UpdateController(ReleaseCatalog catalog) { this.catalog = catalog; }

    @GetMapping("/updates/check")
    public ResponseEntity<?> check(@RequestParam("appId") String appId,
            @RequestParam("runtimeVersion") String runtimeVersion, @RequestParam("appVersion") long appVersion,
            @RequestParam("channel") String channel, @RequestParam("installationId") String installationId) {
        final UpdateSelector.Request request;
        try { request = new UpdateSelector.Request(appId, runtimeVersion, appVersion, channel, installationId); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
        try {
            var selected = UpdateSelector.select(catalog.read(), request);
            if (selected.isEmpty()) return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
            var release = selected.get();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(Map.of("channel", release.channel(), "manifest", release.manifest()));
        } catch (Exception e) {
            // Invalid/missing catalog offers nothing; never substitute another app/channel.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Catalog unavailable");
        }
    }
}
