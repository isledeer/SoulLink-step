package com.isledeer.soullink.game

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlSanitizerTest {

    @Test
    fun `script blocks are stripped`() {
        val out = stripDangerousHtml("<div>ok</div><script>alert(1)</script>")
        assertFalse(out.contains("script", ignoreCase = true))
        assertTrue(out.contains("ok"))
    }

    @Test
    fun `inline event handlers are stripped`() {
        val out = stripDangerousHtml("""<img src="x" onerror="alert(1)">""")
        assertFalse(out.contains("onerror", ignoreCase = true))
    }

    @Test
    fun `csp is injected at the very front`() {
        val out = buildCspHtml("<html><body>hi</body></html>")
        assertTrue(out.contains("Content-Security-Policy"))
        assertTrue(out.indexOf("Content-Security-Policy") < out.indexOf("<body"))
        assertTrue(out.contains("default-src 'none'"))
    }

    @Test
    fun `html without head still gets csp`() {
        val out = buildCspHtml("<div>x</div>")
        assertTrue(out.startsWith("<head>"))
        assertTrue(out.contains("Content-Security-Policy"))
    }
}
