package dev.dootah.gradle.internal

import dev.dootah.contract.SignedUpdate
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** JVM 17 publisher: standard Ed25519, PKCS#8 PEM supplied outside the project. */
internal object PublisherSigning {
    fun sign(update: SignedUpdate, privateKeyFile: File): String {
        require(privateKeyFile.length() in 1..16384) { "Invalid signing key file size" }
        val pem = privateKeyFile.readText().trim()
        require(pem.startsWith("-----BEGIN PRIVATE KEY-----") && pem.endsWith("-----END PRIVATE KEY-----")) {
            "Expected a PKCS#8 PEM signing key"
        }
        val encoded = Base64.getDecoder().decode(
            pem.removePrefix("-----BEGIN PRIVATE KEY-----").removeSuffix("-----END PRIVATE KEY-----")
                .filterNot { it.isWhitespace() }
        )
        return try {
            val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(encoded))
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(key)
            signer.update(update.signingBytes())
            Base64.getEncoder().encodeToString(signer.sign())
        } finally { encoded.fill(0) }
    }
}
