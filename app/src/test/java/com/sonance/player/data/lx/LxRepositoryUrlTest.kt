package com.sonance.player.data.lx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class LxRepositoryUrlTest {

    @Test
    fun validateAndNormalizeScriptUrl_acceptsHttpAndHttpsAndStripsQuotes() {
        val https = LxRepository.validateAndNormalizeScriptUrl("  \"https://raw.githubusercontent.com/demo/lx.js\"  ")
        assertTrue(https.isSuccess)
        assertEquals("https://raw.githubusercontent.com/demo/lx.js", https.getOrThrow().toString())

        val http = LxRepository.validateAndNormalizeScriptUrl("http://192.168.1.10:8080/source.js")
        assertTrue(http.isSuccess)
        assertEquals("http://192.168.1.10:8080/source.js", http.getOrThrow().toString())
    }

    @Test
    fun validateAndNormalizeScriptUrl_rejectsBlankAndUnsupportedSchemes() {
        assertTrue(LxRepository.validateAndNormalizeScriptUrl("   ").isFailure)
        assertTrue(LxRepository.validateAndNormalizeScriptUrl("ftp://example.com/lx.js").isFailure)
        assertTrue(LxRepository.validateAndNormalizeScriptUrl("file:///sdcard/lx.js").isFailure)
        assertTrue(LxRepository.validateAndNormalizeScriptUrl("javascript:alert(1)").isFailure)
        assertTrue(LxRepository.validateAndNormalizeScriptUrl("https:///missing-host.js").isFailure)
    }

    @Test
    fun deriveScriptFileName_prefersDispositionAndEnsuresJsExtension() {
        val url = URL("https://cdn.example.com/scripts/my_source?token=abc")
        assertEquals("my_source.js", LxRepository.deriveScriptFileName(url, null))

        val withHeader = LxRepository.deriveScriptFileName(
            url,
            "attachment; filename=\"huibq-v2.js\""
        )
        assertEquals("huibq-v2.js", withHeader)

        val encodedHeader = LxRepository.deriveScriptFileName(
            url,
            "attachment; filename*=UTF-8''%E6%B4%9B%E9%9B%AA%E9%9F%B3%E6%BA%90.js"
        )
        assertEquals("洛雪音源.js", encodedHeader)

        val rootPath = URL("https://example.com/")
        assertEquals("lx_source.js", LxRepository.deriveScriptFileName(rootPath, null))
    }

    @Test
    fun looksLikeHtmlDocument_detectsHtmlContentTypeAndMarkup() {
        assertTrue(LxRepository.looksLikeHtmlDocument("text/html; charset=utf-8", "console.log(1)"))
        assertTrue(LxRepository.looksLikeHtmlDocument("text/plain", "  <!DOCTYPE html><html><body>404</body></html>"))
        assertTrue(LxRepository.looksLikeHtmlDocument(null, "<html><head></head></html>"))
        assertFalse(LxRepository.looksLikeHtmlDocument("application/javascript", "/*!\n * @name Test\n */"))
    }
}
