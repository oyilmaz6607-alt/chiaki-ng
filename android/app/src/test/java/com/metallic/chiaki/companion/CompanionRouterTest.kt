// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionRouterTest
{
	private class FakeBackend: CompanionBackend
	{
		val calls = mutableListOf<String>()
		var seen = 0
		private fun r(name: String): HttpResponse
		{
			calls.add(name)
			return HttpResponse.json(200, "{\"ok\":true,\"call\":\"$name\"}")
		}
		override fun page() = r("page")
		override fun state() = r("state")
		override fun consoles() = r("consoles")
		override fun getSettings() = r("getSettings")
		override fun postSettings(body: String) = r("postSettings:$body")
		override fun captionStart(body: String) = r("captionStart")
		override fun captionStop() = r("captionStop")
		override fun regist(body: String) = r("regist")
		override fun onClientSeen() { seen++ }
	}

	private val token = CompanionToken.of("Tok3n-_x", "4821")

	private fun req(method: String, path: String, headers: Map<String, String> = emptyMap(), body: String = "",
		host: String? = "192.168.1.20:8642"): HttpRequest
	{
		val h = LinkedHashMap<String, String>()
		if(host != null)
			h["host"] = host
		headers.forEach { (k, v) -> h[k.lowercase()] = v }
		return HttpRequest(method, path, emptyMap(), h, body.toByteArray())
	}

	private val auth = mapOf("X-Token" to "Tok3n-_x")
	private val jsonAuth = auth + ("Content-Type" to "application/json")

	@Test
	fun pageNeedsNoToken()
	{
		val b = FakeBackend()
		val router = CompanionRouter(token, b)
		assertEquals(200, router.route(req("GET", "/")).status)
		assertEquals(200, router.route(req("GET", "/index.html")).status)
		assertEquals(405, router.route(req("POST", "/")).status)
		assertEquals(listOf("page", "page"), b.calls)
		assertEquals(0, b.seen)
	}

	@Test
	fun apiRequiresToken()
	{
		val b = FakeBackend()
		val router = CompanionRouter(token, b)
		assertEquals(401, router.route(req("GET", "/api/state")).status)
		assertEquals(401, router.route(req("GET", "/api/state", mapOf("X-Token" to "wrong"))).status)
		assertEquals(401, router.route(req("POST", "/api/caption/stop", mapOf("X-Token" to "tok3n-_x"))).status)
		assertTrue(b.calls.isEmpty())
		assertEquals(200, router.route(req("GET", "/api/state", auth)).status)
		assertEquals(listOf("state"), b.calls)
		assertEquals(1, b.seen)
	}

	@Test
	fun routesAllEndpoints()
	{
		val b = FakeBackend()
		val router = CompanionRouter(token, b)
		assertEquals(200, router.route(req("GET", "/api/consoles", auth)).status)
		assertEquals(200, router.route(req("GET", "/api/settings", auth)).status)
		assertEquals(200, router.route(req("POST", "/api/settings", jsonAuth, "{\"a\":1}")).status)
		assertEquals(200, router.route(req("POST", "/api/caption/start", jsonAuth, "{}")).status)
		assertEquals(200, router.route(req("POST", "/api/caption/stop", auth)).status)
		assertEquals(200, router.route(req("POST", "/api/regist", jsonAuth, "{}")).status)
		assertEquals(listOf("consoles", "getSettings", "postSettings:{\"a\":1}", "captionStart", "captionStop", "regist"), b.calls)
	}

	@Test
	fun wrongMethodsAndUnknownPaths()
	{
		val router = CompanionRouter(token, FakeBackend())
		assertEquals(405, router.route(req("POST", "/api/state", auth)).status)
		assertEquals(405, router.route(req("GET", "/api/caption/start", auth)).status)
		assertEquals(405, router.route(req("DELETE", "/api/settings", auth)).status)
		assertEquals(404, router.route(req("GET", "/api/nope", auth)).status)
		assertEquals(404, router.route(req("GET", "/etc/passwd", auth)).status)
		assertEquals(404, router.route(req("GET", "/../assets/x", auth)).status)
	}

	@Test
	fun postBodiesMustBeJson()
	{
		val router = CompanionRouter(token, FakeBackend())
		assertEquals(415, router.route(req("POST", "/api/settings", auth + ("Content-Type" to "text/plain"), "{}")).status)
		assertEquals(415, router.route(req("POST", "/api/regist", auth + ("Content-Type" to "application/x-www-form-urlencoded"), "a=b")).status)
		assertEquals(200, router.route(req("POST", "/api/settings", auth + ("Content-Type" to "application/json; charset=utf-8"), "{}")).status)
	}

	@Test
	fun hostAndOriginChecks()
	{
		val router = CompanionRouter(token, FakeBackend())
		// DNS rebinding: ad ile gelen Host reddedilir.
		assertEquals(403, router.route(req("GET", "/api/state", auth, host = "evil.example.com")).status)
		assertEquals(403, router.route(req("GET", "/", host = "evil.example.com:8642")).status)
		assertEquals(403, router.route(req("GET", "/", host = null)).status)
		assertEquals(200, router.route(req("GET", "/", host = "[fe80::1]:8642")).status)
		assertEquals(200, router.route(req("GET", "/", host = "localhost:8642")).status)
		assertEquals(200, router.route(req("GET", "/", host = "android-tv.local:8642")).status)
		// Başka kaynaklı POST reddedilir.
		assertEquals(403, router.route(req("POST", "/api/caption/stop", auth + ("Origin" to "http://evil.example.com"))).status)
		assertEquals(200, router.route(req("POST", "/api/caption/stop", auth + ("Origin" to "http://192.168.1.20:8642"))).status)
	}

	@Test
	fun hostHeaderParsing()
	{
		assertTrue(CompanionRouter.isAllowedHost("192.168.1.20"))
		assertTrue(CompanionRouter.isAllowedHost("8.8.8.8:80")) // sayısal: rebinding yok (istemci adresi ayrıca denetlenir)
		assertFalse(CompanionRouter.isAllowedHost("192.168.1.20:"))
		assertFalse(CompanionRouter.isAllowedHost("192.168.1.20:abc"))
		assertFalse(CompanionRouter.isAllowedHost("[fe80::1"))
		assertFalse(CompanionRouter.isAllowedHost(".local"))
		assertFalse(CompanionRouter.isAllowedHost("a b.local"))
		assertTrue(CompanionRouter.isAllowedOrigin(null, "x"))
		assertFalse(CompanionRouter.isAllowedOrigin("null", "192.168.1.20:8642"))
		assertFalse(CompanionRouter.isAllowedOrigin("https://192.168.1.20:8642", "192.168.1.20:8642"))
	}

	@Test
	fun shortCodePairing()
	{
		val b = FakeBackend()
		val router = CompanionRouter(token, b, ShortCodeGuard(maxFailures = 2))
		val ok = router.route(req("POST", "/api/pair", body = "{\"code\":\"4821\"}"))
		assertEquals(200, ok.status)
		assertTrue(String(ok.body).contains("\"token\":\"Tok3n-_x\""))
		assertEquals(405, router.route(req("GET", "/api/pair")).status)
		assertEquals(401, router.route(req("POST", "/api/pair", body = "{\"code\":\"0000\"}")).status)
		assertEquals(401, router.route(req("POST", "/api/pair", body = "{}")).status)
		// İki yanlıştan sonra kilitli: doğru kod da 429.
		assertEquals(429, router.route(req("POST", "/api/pair", body = "{\"code\":\"4821\"}")).status)
	}
}
