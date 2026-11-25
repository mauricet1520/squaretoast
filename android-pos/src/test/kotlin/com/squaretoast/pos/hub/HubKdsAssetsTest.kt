package com.squaretoast.pos.hub

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slice 6: the hub serves the browser KDS bundle from APK assets so kitchen screens work without internet. */
class HubKdsAssetsTest {

    private val files = mapOf(
        "index.html" to "<!doctype html><title>KDS</title>",
        "assets/app-1a2b.js" to "console.log('kds')",
        "assets/app-1a2b.css" to "body{}",
        "bell.ogg" to "OggS",
    )
    private val asked = mutableListOf<String>()
    private val assets = KdsAssets { path -> asked += path; files[path]?.toByteArray() }

    private fun hub(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { hubModule(CourseBoard(), PairingTokens(), assets) }
        block()
    }

    @Test
    fun kdsRootServesIndexWithStrictHeaders() = hub {
        for (path in listOf("/kds", "/kds/", "/kds?pair=abc")) {
            val r = client.get(path)
            assertEquals(path, HttpStatusCode.OK, r.status)
            assertEquals(path, "<!doctype html><title>KDS</title>", r.bodyAsText())
            assertTrue(path, r.headers[HttpHeaders.ContentType]!!.startsWith("text/html"))
            assertEquals(path, "no-store", r.headers[HttpHeaders.CacheControl])
            val csp = r.headers["Content-Security-Policy"]!!
            assertTrue(csp, csp.contains("default-src 'self'"))
            assertTrue(csp, csp.contains("frame-ancestors 'none'"))
            assertEquals("nosniff", r.headers["X-Content-Type-Options"])
            assertEquals("no-referrer", r.headers["Referrer-Policy"])
        }
    }

    @Test
    fun assetsGetContentTypesFromTheirExtension() = hub {
        assertTrue(client.get("/kds/assets/app-1a2b.js").headers[HttpHeaders.ContentType]!!.startsWith("text/javascript"))
        assertTrue(client.get("/kds/assets/app-1a2b.css").headers[HttpHeaders.ContentType]!!.startsWith("text/css"))
        assertEquals("audio/ogg", client.get("/kds/bell.ogg").headers[HttpHeaders.ContentType])
        assertEquals("console.log('kds')", client.get("/kds/assets/app-1a2b.js").bodyAsText())
    }

    @Test
    fun deepLinksFallBackToIndexButMissingFilesAreNotFound() = hub {
        assertEquals("<!doctype html><title>KDS</title>", client.get("/kds/station/grill").bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/kds/assets/missing.js").status)
    }

    @Test
    fun pathsThatLeaveTheBundleAreRefusedBeforeTouchingAssets() = hub {
        for (path in listOf("/kds/../secret.txt", "/kds/%2e%2e/secret.txt", "/kds/assets/..%2Fsecret.txt",
            "/kds/assets/%5C..%5Csecret.txt", "/kds/assets/a%00.js")) {
            assertNotEquals(path, HttpStatusCode.OK, client.get(path).status)
        }
        assertTrue(asked.toString(), asked.none { it.contains("..") || it.contains('\\') || it.contains('\u0000') })
    }
}
