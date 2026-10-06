package dev.dootah.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Shared pre-shrinking JVM identity contract for instrumentation and future publishing. */
public final class FunctionIdentity {
    private FunctionIdentity() {}

    public static String of(String owner, String name, String descriptor,
                            boolean isStatic, boolean extension, String genericSignature, String nullability) {
        String canonical = String.join("\n", "dootah-entry-v1", owner, name, descriptor,
                isStatic ? "static" : "instance", extension ? "extension" : "ordinary",
                genericSignature == null ? "" : genericSignature, nullability);
        try {
            return "dth1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
