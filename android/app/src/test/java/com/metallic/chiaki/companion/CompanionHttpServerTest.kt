// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.companion

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** Gerçek soket üzerinden uçtan uca: 127.0.0.1'de dinleyen sunucuya ham HTTP gönderilir. */
class CompanionHttpServerTest
{
	private fun exchange(port: Int, raw: String): String
	{
		Socket(InetAddress.getLoopbackAddress(), port).use { s ->
			s.soTimeout = 5000
			s.getOutputStream().write(raw.toByteArray(Charsets.UTF_8))
			s.getOutputStream().flush()
			val out = ByteArrayOutputStream()
			s.getInputStream().copyTo(out)
			return String(out.toByteArray(), Charsets.UTF_8)
		}
	}

	private fun startServer(filter: (InetAddress?) -> Boolean = LanAddressFilter::isAllowed, first: Int = 18642, last: Int = 18660): CompanionHttpServer
	{
		val server = CompanionHttpServer({ req ->
			if(req.path == "/boom")
				throw IllegalStateException("boom")
			HttpResponse.json(200, "{\"ok\":true,\"path\":${Json.quote(req.path)},\"body\":${Json.quote(req.bodyText)}}")
		}, bindAddress = InetAddress.getLoopbackAddress(), readTimeoutMs = 500, addressFilter = filter)
		server.start(first, last)
		return server
	}

	@Test
	fun servesRequests()
	{
		val server = startServer()
		try
		{
			val r = exchange(server.port, "POST /api/x HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 4\r\n\r\nabcd")
			assertTrue(r, r.startsWith("HTTP/1.1 200 OK"))
			assertTrue(r, r.endsWith("{\"ok\":true,\"path\":\"/api/x\",\"body\":\"abcd\"}"))

			val head = exchange(server.port, "HEAD / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
			assertTrue(head, head.startsWith("HTTP/1.1 200 OK"))
			assertTrue(head, head.endsWith("\r\n\r\n"))

			val bad = exchange(server.port, "NONSENSE\r\n\r\n")
			assertTrue(bad, bad.startsWith("HTTP/1.1 400"))

			val big = exchange(server.port, "POST / HTTP/1.1\r\nContent-Length: 70000\r\n\r\n")
			assertTrue(big, big.startsWith("HTTP/1.1 413"))

			val crash = exchange(server.port, "GET /boom HTTP/1.1\r\n\r\n")
			assertTrue(crash, crash.startsWith("HTTP/1.1 500"))
		}
		finally
		{
			server.stop()
		}
		assertFalse(server.isRunning)
	}

	@Test
	fun slowClientTimesOut()
	{
		val server = startServer()
		try
		{
			val started = System.currentTimeMillis()
			val r = exchange(server.port, "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n") // başlık bitmiyor
			assertTrue(r, r.startsWith("HTTP/1.1 408"))
			assertTrue(System.currentTimeMillis() - started < 4000)
		}
		finally
		{
			server.stop()
		}
	}

	@Test
	fun rejectsNonLanClients()
	{
		val server = startServer(filter = { false })
		try
		{
			val r = exchange(server.port, "GET / HTTP/1.1\r\n\r\n")
			assertTrue(r, r.startsWith("HTTP/1.1 403"))
		}
		finally
		{
			server.stop()
		}
	}

	@Test
	fun fallsBackToNextFreePort()
	{
		val blocker = ServerSocket()
		blocker.reuseAddress = false
		blocker.bind(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
		val busy = blocker.localPort
		try
		{
			val server = CompanionHttpServer({ HttpResponse.empty(204) }, bindAddress = InetAddress.getLoopbackAddress())
			val port = server.start(busy, busy + 8)
			try
			{
				assertTrue(port in (busy + 1)..(busy + 8))
				// İkinci start aynı bağlantı noktasını döndürür.
				assertEquals(port, server.start(busy, busy + 8))
			}
			finally
			{
				server.stop()
			}
		}
		finally
		{
			blocker.close()
		}
	}

	@Test
	fun workerThreadsAreDaemon()
	{
		val server = startServer()
		try
		{
			exchange(server.port, "GET / HTTP/1.1\r\n\r\n")
			val threads = Thread.getAllStackTraces().keys.filter { it.name.startsWith("companion-") }
			assertTrue(threads.isNotEmpty())
			assertTrue(threads.all { it.isDaemon })
		}
		finally
		{
			server.stop()
		}
	}
}
