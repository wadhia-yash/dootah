package dev.dootah.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class UpdateSelector {
    public record Request(String appId, String runtimeVersion, long appVersion, String channel, String installationId) {
        public Request {
            if (appId == null || !appId.matches("[A-Za-z0-9_.-]{1,200}") || runtimeVersion == null
                    || !runtimeVersion.matches("[A-Za-z0-9_.-]{1,100}") || appVersion < 1
                    || !ReleaseCatalog.CHANNELS.contains(channel == null ? "" : channel)
                    || installationId == null || !installationId.matches("[A-Za-z0-9_-]{1,128}"))
                throw new IllegalArgumentException("Invalid update request");
        }
    }
    /** SHA-256 domain + app + installation + immutable signed identity. Unsigned first 32 bits modulo 100. */
    public static int bucket(String appId, String installationId, String releaseIdentity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("Dootah.Rollout.v1\n" + appId + "\n" + installationId + "\n" + releaseIdentity).getBytes(StandardCharsets.UTF_8));
            long n = 0; for (int i = 0; i < 4; i++) n = (n << 8) | (digest[i] & 255);
            return (int) (n % 100);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static Optional<ReleaseCatalog.Release> select(List<ReleaseCatalog.Release> releases, Request q) {
        return releases.stream().filter(r -> r.appId().equals(q.appId()) && r.runtimeVersion().equals(q.runtimeVersion())
                        && r.channel().equals(q.channel()) && !r.paused()
                        && (r.minAppVersion() == null || q.appVersion() >= r.minAppVersion())
                        && (r.maxAppVersion() == null || q.appVersion() <= r.maxAppVersion())
                        && bucket(q.appId(), q.installationId(), r.identity()) < r.rolloutPercent())
                .max(Comparator.comparingInt(ReleaseCatalog.Release::version));
    }
    private UpdateSelector() { }
}
