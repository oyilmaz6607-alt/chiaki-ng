// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.companion

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class HttpParserTest
{
	private fun parse(raw: String, parser: HttpParser = HttpParser()) =
		parser.parse(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))

	private fun status(raw: String, parser: HttpParser = HttpParser()): Int =
		try
		{
			parse(raw, parser)
			200
		}
		catch(e: HttpException)
		{
			e.status
		}

	@Test
	fun parsesGetWithQuery()
	{
		val r = parse("GET /?t=AbC-_123&x=a%20b+c HTTP/1.1\r\nHost: 192.168.1.20:8642\r\nX-Token: abc\r\n\r\n")!!
		assertEquals("GET", r.method)
		assertEquals("/", r.path)
		assertEquals("AbC-_123", r.query["t"])
		assertEquals("a b c", r.query["x"])
		assertEquals("192.168.1.20:8642", r.header("Host"))
		assertEquals("abc", r.header("x-token"))
		assertEquals(0, r.body.size)
	}

	@Test
	fun parsesPostBodyByContentLength()
	{
		val body = "{\"a\":\"ç\"}"
		val len = body.toByteArray(Charsets.UTF_8).size
		val r = parse("POST /api/settings HTTP/1.1\r\nContent-Length: $len\r\nContent-Type: application/json\r\n\r\n$body" + "EXTRA")!!
		assertEquals("POST", r.method)
		assertEquals("/api/settings", r.path)
		assertEquals(body, r.bodyText)
	}

	@Test
	fun acceptsBareLfLineEndings()
	{
		val r = parse("GET /api/state HTTP/1.1\nHost: x\n\n")!!
		assertEquals("/api/state", r.path)
		assertEquals("x", r.header("host"))
	}

	@Test
	fun emptyConnectionReturnsNull()
	{
		assertNull(parse(""))
	}

	@Test
	fun rejectsMalformedRequests()
	{
		assertEquals(400, status("GARBAGE\r\n\r\n"))
		assertEquals(501, status("BREW / HTTP/1.1\r\n\r\n"))
		assertEquals(505, status("GET / HTTP/2.0\r\n\r\n"))
		assertEquals(400, status("GET relative HTTP/1.1\r\n\r\n"))
		assertEquals(400, status("GET / HTTP/1.1\r\nNoColonHere\r\n\r\n"))
		assertEquals(400, status("GET / HTTP/1.1\r\nBad Name: x\r\n\r\n"))
		assertEquals(400, status("GET /%zz HTTP/1.1\r\n\r\n"))
		assertEquals(400, status("GET /%00 HTTP/1.1\r\n\r\n"))
		assertEquals(400, status("GET / HTTP/1.1\r\nHost: x\r\n")) // başlık bitmeden kapandı
	}

	@Test
	fun rejectsBadContentLength()
	{
		assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n"))
		assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: 1e3\r\n\r\n"))
		assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: 5\r\nContent-Length: 6\r\n\r\nhello!"))
		assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort"))
		assertEquals(411, status("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"))
	}

	@Test
	fun enforcesBodyLimit()
	{
		val limit = HttpParser.MAX_BODY_BYTES
		assertEquals(413, status("POST / HTTP/1.1\r\nContent-Length: ${limit + 1}\r\n\r\n"))
		val ok = "x".repeat(limit)
		val r = parse("POST / HTTP/1.1\r\nContent-Length: $limit\r\n\r\n$ok")!!
		assertEquals(limit, r.body.size)
	}

	@Test
	fun enforcesHeaderLimits()
	{
		val small = HttpParser(maxHeaderBytes = 64)
		assertEquals(431, status("GET / HTTP/1.1\r\nX-Long: ${"a".repeat(100)}\r\n\r\n", small))
		val many = (0..HttpParser.MAX_HEADER_COUNT).joinToString("") { "H$it: v\r\n" }
		assertEquals(431, status("GET / HTTP/1.1\r\n$many\r\n"))
		assertEquals(414, status("GET /${"a".repeat(HttpParser.MAX_URI_LENGTH)} HTTP/1.1\r\n\r\n"))
	}

	@Test
	fun percentDecoding()
	{
		assertEquals("a b", HttpParser.percentDecode("a%20b", false))
		assertEquals("a+b", HttpParser.percentDecode("a+b", false))
		assertEquals("a b", HttpParser.percentDecode("a+b", true))
		assertEquals("ş", HttpParser.percentDecode("%C5%9F", false))
		assertEquals(mapOf("t" to "1", "k" to ""), HttpParser.parseQuery("t=1&t=2&k&&"))
	}

	@Test
	fun responseSerialization()
	{
		val text = String(HttpResponse.json(200, "{\"ok\":true}").serialize(), Charsets.ISO_8859_1)
		assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
		assertTrue(text.contains("Content-Length: 11\r\n"))
		assertTrue(text.contains("Connection: close\r\n"))
		assertTrue(text.contains("Cache-Control: no-store\r\n"))
		assertTrue(text.endsWith("\r\n\r\n{\"ok\":true}"))
		// CR/LF içeren başlık yazılmaz
		val injected = String(HttpResponse(200, HttpResponse.TEXT, ByteArray(0), mapOf("X-A" to "1\r\nSet-Cookie: x")).serialize(), Charsets.ISO_8859_1)
		assertFalse(injected.contains("Set-Cookie"))
	}

	@Test
	fun jsonQuoteEscapes()
	{
		assertEquals("\"a\\\"b\\\\c\\n\\u003c/script>\"", Json.quote("a\"b\\c\n</script>"))
		assertEquals("\"\\u0001\"", Json.quote("\u0001"))
	}
}
