package dev.dootah.gradle.internal

import dev.dootah.contract.BundleImages
import dev.dootah.contract.SignedImage
import dev.dootah.contract.SignedUpdate
import groovy.json.JsonSlurper
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI

class PublicationClientTest {
    @get:Rule val temp = TemporaryFolder()
    private val origin = "https://example.test"
    private val token = "test-token-".repeat(4)
    private val calls = mutableListOf<Pair<String, ByteArray>>()
    private var identity = ""
    private fun fixture(): java.io.File {
        val dir = temp.newFolder()
        val image = byteArrayOf(1,2,3)
        val ih = BundleImagePackaging.sha256(image)
        val bundle = (BundleImages.header(listOf(ih)) + "var okay=true;").toByteArray()
        val hash = BundleImagePackaging.sha256(bundle)
        val update = SignedUpdate(1,"example.app","9",1,true,"$origin/artifacts/$hash",hash,listOf(SignedImage(ih,"$origin/artifacts/$ih",ih)))
        identity = BundleImagePackaging.sha256(update.signingBytes())
        dir.resolve("manifest.json").writeText(update.manifestJson("signed-publisher-output"))
        dir.resolve("bundle.js").writeBytes(bundle)
        dir.resolve("images/$ih").apply { parentFile.mkdirs(); writeBytes(image) }
        return dir
    }
    private fun client(fail: Int = -1, malformed: Int = -1) = PublicationClient(origin,token) { method,url,bytes,auth ->
        assertEquals(token,auth)
        calls += url to bytes
        if (calls.size == fail) error("simulated transport failure")
        if (calls.size == malformed) "not json".toByteArray()
        else if(method == "PUT") "{\"status\":\"stored\",\"sha256\":\"${url.substringAfterLast('/')}\"}".toByteArray()
        else "{\"status\":\"registered\",\"identity\":\"$identity\"}".toByteArray()
    }
    @Test fun `complete noninteractive publication uploads all before register`() {
        val dir=fixture(); assertEquals("registered",client().publish(dir,"production",100)["status"])
        assertEquals(3,calls.size); assertTrue(calls[0].first.contains("artifacts")); assertTrue(calls[1].first.contains("artifacts")); assertTrue(calls[2].first.endsWith("releases"))
    }
    @Test fun `bundle upload failure prevents registration`() {val dir=fixture();assertThrows(IllegalStateException::class.java){client(fail=1).publish(dir,"production",100)};assertEquals(1,calls.size)}
    @Test fun `asset upload failure prevents registration`() {val dir=fixture();assertThrows(IllegalStateException::class.java){client(fail=2).publish(dir,"production",100)};assertEquals(2,calls.size)}
    @Test fun `registration failure is reported and exact retry sends identical bytes`() {
        val dir=fixture();assertThrows(IllegalStateException::class.java){client(fail=3).publish(dir,"production",100)}
        val before=calls.toList();calls.clear();client().publish(dir,"production",100)
        before.zip(calls).forEach{(a,b)->assertEquals(a.first,b.first);assertArrayEquals(a.second,b.second)}
    }
    @Test fun `partial upload retry is safe`() {val dir=fixture();assertThrows(IllegalStateException::class.java){client(fail=2).publish(dir,"production",100)};calls.clear();client().publish(dir,"production",100);assertEquals(3,calls.size)}
    @Test fun `only manifest and declared artifacts are sent never private files`() {
        val dir=fixture();dir.resolve("private.pem").writeText("PRIVATE SECRET NEVER SEND");client().publish(dir,"staging",10,2,4)
        assertFalse(calls.any{it.second.toString(Charsets.UTF_8).contains("PRIVATE SECRET")})
        val metadata=JsonSlurper().parse(calls.last().second) as Map<*,*>
        assertEquals("staging",metadata["channel"]);assertEquals(10,metadata["rolloutPercent"]);assertEquals(2,metadata["minAppVersion"]);assertEquals(4,metadata["maxAppVersion"])
    }
    @Test fun `malformed upload response prevents registration`() {val dir=fixture();assertThrows(Exception::class.java){client(malformed=1).publish(dir,"production",100)};assertEquals(1,calls.size)}
    @Test fun `malformed registration response fails with safe retry`() {val dir=fixture();assertThrows(Exception::class.java){client(malformed=3).publish(dir,"production",100)};calls.clear();client().publish(dir,"production",100)}
    @Test fun `trailing JSON or duplicate receipt fields fail before registration`() {
        val dir=fixture()
        for (suffix in listOf("{}", "garbage")) {
            val client=PublicationClient(origin,token){_,url,_,_->("{\"status\":\"stored\",\"sha256\":\"${url.substringAfterLast('/')}\"}"+suffix).toByteArray()}
            assertThrows(Exception::class.java){client.publish(dir,"production",100)}
        }
        val client=PublicationClient(origin,token){_,url,_,_->"{\"status\":\"bad\",\"status\":\"stored\",\"sha256\":\"${url.substringAfterLast('/')}\"}".toByteArray()}
        assertThrows(Exception::class.java){client.publish(dir,"production",100)}
    }
    @Test fun `wrong receipt identity never reports success`() {
        val dir=fixture();val client=PublicationClient(origin,token){_,url,_,_->if(url.endsWith("releases")) "{\"status\":\"registered\",\"identity\":\"${"a".repeat(64)}\"}".toByteArray() else "{\"status\":\"stored\",\"sha256\":\"${url.substringAfterLast('/')}\"}".toByteArray()}
        assertThrows(IllegalArgumentException::class.java){client.publish(dir,"production",100)}
    }
    @Test fun `missing or tampered local asset fails before first upload`() {
        val dir=fixture();dir.resolve("images").listFiles()!!.first().writeText("bad")
        assertThrows(IllegalArgumentException::class.java){client().publish(dir,"production",100)};assertTrue(calls.isEmpty())
    }
    @Test fun `reject credentials in URL http invalid channel percentage and token`() {
        for(url in listOf("http://example.test","https://user:pass@example.test","https://example.test?key=x","https://example.test/path")) assertThrows(IllegalArgumentException::class.java){PublicationClient(url,token)}
        assertThrows(IllegalArgumentException::class.java){PublicationClient(origin,"")}
        val dir=fixture();assertThrows(IllegalArgumentException::class.java){client().publish(dir,"unknown",100)}
        assertThrows(IllegalArgumentException::class.java){client().publish(dir,"production",101)};assertTrue(calls.isEmpty())
    }

    /** Exercise the real HTTP error stream through the existing transport seam; production still requires HTTPS. */
    private fun rejectedByServer(status: Int, body: String): String {
        val directory = fixture()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val registration = exchange.requestMethod == "POST"
            val response = if (registration) body else "{\"status\":\"stored\",\"sha256\":\"${exchange.requestURI.path.substringAfterLast('/')}\"}"
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(if (registration) status else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = PublicationClient(origin, token) { method, url, bytes, auth ->
                PublicationClient.request(method, "http://127.0.0.1:${server.address.port}${URI(url).path}", bytes, auth)
            }
            return assertThrows(IllegalStateException::class.java) { client.publish(directory, "production", 100) }.message!!
        } finally { server.stop(0) }
    }

    @Test fun `HTTP rejection surfaces publisher configuration reason`() {
        val message = rejectedByServer(400, """{"code":"PUBLISHER_NOT_CONFIGURED","message":"No publisher public key is configured for appId 'example.app'; configure publishers[appId] in the server catalog with the trusted public key"}""")
        assertTrue(message, message.contains("PUBLISHER_NOT_CONFIGURED"))
        assertTrue(message, message.contains("example.app"))
        assertTrue(message, message.contains("configure publishers[appId]"))
        assertTrue(message, message.contains("HTTP 400"))
        assertFalse(message, message.contains("retry the same command"))
    }

    @Test fun `HTTP errors ignore proxy bodies and redact credentials in structured reasons`() {
        val malformed = rejectedByServer(400, "<html>Authorization: Bearer $token</html>")
        assertTrue(malformed, malformed.contains("server returned no usable rejection reason"))
        assertFalse(malformed, malformed.contains(token))
        assertFalse(malformed, malformed.contains("html"))
        val structured = rejectedByServer(400, """{"code":"INVALID_PUBLICATION","message":"Invalid $token\nvalue"}""")
        assertFalse(structured, structured.contains(token))
        assertFalse(structured, structured.contains('\n'))
        assertTrue(structured, structured.contains("[redacted]"))
    }

    @Test fun `HTTP auth and conflict errors do not suggest blind retries`() {
        val auth = rejectedByServer(401, "")
        assertTrue(auth, auth.contains("DOOTAH_PUBLISH_TOKEN"))
        val conflict = rejectedByServer(409, """{"code":"IMMUTABLE_PUBLICATION_CONFLICT","message":"bundleVersion 2 already exists; use a new bundleVersion"}""")
        assertTrue(conflict, conflict.contains("use a new bundleVersion"))
        val oversized = rejectedByServer(400, "x".repeat(65537))
        assertTrue(oversized, oversized.contains("no usable rejection reason"))
    }
}
