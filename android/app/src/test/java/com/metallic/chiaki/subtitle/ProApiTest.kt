// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class ProApiTest
{
	@Test
	fun baseUrl()
	{
		assertEquals("https://pro.example.workers.dev", ProApi.normalizeBaseUrl(" https://pro.example.workers.dev/ "))
		assertEquals("https://x.dev/api", ProApi.normalizeBaseUrl("https://x.dev/api//"))
		assertNull(ProApi.normalizeBaseUrl(""))
		assertNull(ProApi.normalizeBaseUrl(null))
		assertNull(ProApi.normalizeBaseUrl("http://insecure.dev"))
		assertNull(ProApi.normalizeBaseUrl("https://"))
		assertNull(ProApi.normalizeBaseUrl("https://a b.dev"))
	}

	@Test
	fun sessionRequest()
	{
		val body = MiniJson.parse(ProApi.sessionBody("tok\"en", "1.2.3")) as Map<*, *>
		assertEquals("com.altyazitr.oyun", body["packageName"])
		assertEquals("altyazitr_abonelik", body["productId"])
		assertEquals("tok\"en", body["purchaseToken"])
		assertEquals("1.2.3", body["appVersion"])
	}

	@Test
	fun translateRequestIsTrimmedToContract()
	{
		val ctx = (1..6).map { SubtitlePair("en$it", "tr$it") } + SubtitlePair("x".repeat(700), "y")
		val body = MiniJson.parse(ProApi.translateBody("  " + "a".repeat(650) + " ", ctx)) as Map<*, *>
		assertEquals(600, (body["text"] as String).length)
		val c = body["context"] as List<*>
		assertEquals(4, c.size)
		assertEquals("en4", (c[0] as Map<*, *>)["en"])
		assertEquals(600, ((c[3] as Map<*, *>)["en"] as String).length)
		assertTrue(body.containsKey("speaker"))
		assertNull(body["speaker"])
		val empty = MiniJson.parse(ProApi.translateBody("Hi", emptyList())) as Map<*, *>
		assertEquals(emptyList<Any>(), empty["context"])
	}

	@Test
	fun sessionResponse()
	{
		val ok = ProApi.parseSession(200, """{"ok":true,"sessionToken":"abc.def","expiresAt":1793000000000,"quota":{"used":123,"limit":30000,"resetAt":1793491200000}}""")
		assertEquals(ProApi.SessionResult.Ok("abc.def", 1793000000000L, ProQuota(123, 30000, 1793491200000L)), ok)
		val noQuota = ProApi.parseSession(200, """{"ok":true,"sessionToken":"t","expiresAt":5}""") as ProApi.SessionResult.Ok
		assertNull(noQuota.quota)
		assertNull(ok.let { (it as ProApi.SessionResult.Ok).plan })
		// v1.1: paket alanları
		val pro = ProApi.parseSession(200, """{"ok":true,"sessionToken":"t","expiresAt":5,"plan":"pro","planName":"Pro","basePlan":"pro","basePlanName":"Pro","trial":false,"quota":{"used":3120,"limit":11000,"resetAt":7}}""") as ProApi.SessionResult.Ok
		assertEquals(ProPlan("pro", "Pro", "pro", false), pro.plan)
		assertEquals(ProQuota(3120, 11000, 7), pro.quota)
		val trial = ProApi.parseSession(200, """{"ok":true,"sessionToken":"t","expiresAt":5,"plan":"deneme","planName":"Deneme","basePlan":"premium","trial":true}""") as ProApi.SessionResult.Ok
		assertEquals(ProPlan("deneme", "Deneme", "premium", true), trial.plan)
		val noBase = ProApi.parseSession(200, """{"ok":true,"sessionToken":"t","expiresAt":5,"plan":"standart"}""") as ProApi.SessionResult.Ok
		assertEquals(ProPlan("standart", null, "standart", false), noBase.plan)
		val trialNoBase = ProApi.parseSession(200, """{"ok":true,"sessionToken":"t","expiresAt":5,"plan":"deneme","trial":true}""") as ProApi.SessionResult.Ok
		assertNull(trialNoBase.plan!!.basePlan)
		val badPlan = ProApi.parseSession(200, """{"ok":true,"sessionToken":"t","expiresAt":5,"plan":"Pro Plus!"}""") as ProApi.SessionResult.Ok
		assertNull(badPlan.plan)
		val denied = ProApi.parseSession(403, """{"ok":false,"code":"not_subscribed","message":"Abonelik bitmiş"}""")
		assertEquals(ProApi.SessionResult.Failure(ProError("not_subscribed", 403, "Abonelik bitmiş")), denied)
		val broken = ProApi.parseSession(200, """{"ok":true}""") as ProApi.SessionResult.Failure
		assertEquals(ProApi.CODE_BAD_RESPONSE, broken.error.code)
		val html = ProApi.parseSession(502, "<html>Bad gateway</html>") as ProApi.SessionResult.Failure
		assertEquals(ProError(ProApi.CODE_SERVER_ERROR, 502, null), html.error)
	}

	@Test
	fun errorCodesFromStatus()
	{
		assertEquals("unauthorized", ProApi.parseError(401, "").code)
		assertEquals("rate_limited", ProApi.parseError(429, null).code)
		assertEquals("quota_exceeded", ProApi.parseError(429, """{"ok":false,"code":"quota_exceeded","message":"1 Kasım'da"}""").code)
		assertTrue(ProApi.shouldRenewSession(ProApi.parseError(401, null)))
		assertFalse(ProApi.shouldRenewSession(ProApi.parseError(403, null)))
	}

	@Test
	fun translateResponse()
	{
		assertEquals(ProApi.TranslateResult.Ok("Merhaba!", ProQuota(1, 2, 3), true),
			ProApi.parseTranslate(200, """{"ok":true,"text":"\"Merhaba!\"","quota":{"used":1,"limit":2,"resetAt":3},"cached":true}"""))
		val q = ProApi.parseTranslate(429, """{"ok":false,"code":"quota_exceeded","message":"Sınır doldu"}""") as ProApi.TranslateResult.Failure
		assertEquals("quota_exceeded", q.error.code)
		assertTrue(ProApi.parseTranslate(200, """{"ok":true,"text":"  "}""") is ProApi.TranslateResult.Failure)
	}

	@Test
	fun streamLines()
	{
		assertNull(ProApi.parseStreamLine("   "))
		assertEquals(ProApi.StreamEvent.Delta("Yakın "), ProApi.parseStreamLine("""{"d":"Yakın "}"""))
		assertEquals(ProApi.StreamEvent.Done("Yakın dur evlat.", ProQuota(10, 30000, 99), false),
			ProApi.parseStreamLine("""{"done":true,"text":"Yakın dur evlat.","quota":{"used":10,"limit":30000,"resetAt":99},"cached":false}"""))
		assertEquals(ProApi.StreamEvent.Error(ProError("upstream_error", 200, "Gemini yanıt vermedi")),
			ProApi.parseStreamLine("""{"error":"upstream_error","message":"Gemini yanıt vermedi"}"""))
		assertEquals(ProApi.StreamEvent.Unknown, ProApi.parseStreamLine("{bozuk"))
		assertEquals(ProApi.StreamEvent.Unknown, ProApi.parseStreamLine("""{"x":1}"""))
	}

	@Test
	fun streamAccumulatorThrottles()
	{
		val a = StreamAccumulator(startMs = 0, minIntervalMs = 80)
		// ilk parça isteğin üzerinden 80 ms geçtiyse hemen yazılır
		assertEquals("Yakın", a.add("Yakın", 300))
		// 80 ms dolmadan gelen parça bekletilir…
		assertNull(a.add(" dur", 320))
		// …noktalama gelirse hemen yazılır
		assertEquals("Yakın dur,", a.add(",", 330))
		assertNull(a.add(" ev", 340))
		assertEquals("Yakın dur, evlat", a.add("lat", 420))
		assertNull(a.add("", 600))
		assertEquals("Yakın dur, evlat", a.text)
		// aynı metin tekrar yazılmaz
		assertNull(a.add("   ", 700))
	}

	@Test
	fun sessionRefreshUsesMonotonicClock()
	{
		val wall = 1_000_000_000L
		val s = ProSession.create("t", wall + 60 * 60_000L, null, wall, nowMonoMs = 5000)
		assertFalse(s.needsRefresh(5000))
		assertFalse(s.needsRefresh(5000 + 57 * 60_000L))
		assertTrue(s.needsRefresh(5000 + 59 * 60_000L))
		// cihaz saati ileri (bitiş geçmişte görünüyor): yine de en az 5 dk kullanılır
		val skewed = ProSession.create("t", wall - 10_000L, null, wall, nowMonoMs = 0)
		assertFalse(skewed.needsRefresh(60_000))
		assertTrue(skewed.needsRefresh(4 * 60_000L))
		// cihaz saati geri (bitiş çok ileride): en fazla 1 saat
		val late = ProSession.create("t", wall + 10 * 3_600_000L, null, wall, nowMonoMs = 0)
		assertTrue(late.needsRefresh(59 * 60_000L))
	}

	@Test
	fun turkishMessages()
	{
		val utc = TimeZone.getTimeZone("UTC")
		val now = 1_792_972_800_000L // 26 Ekim 2026
		val quota = ProQuota(30000, 30000, 1_793_491_200_000L)
		assertEquals("Paketinizin yapay zekâ çeviri hakkı doldu; 1 Kasım tarihinde yenilenir.",
			ProApi.userMessage(ProError("quota_exceeded", 429, "x"), quota, now, utc))
		assertEquals("Sunucu mesajı", ProApi.userMessage(ProError("quota_exceeded", 429, "Sunucu mesajı"), null, now, utc))
		assertEquals(ProApi.MSG_NOT_CONFIGURED, ProApi.userMessage(ProError(ProApi.CODE_NOT_CONFIGURED)))
		assertTrue(ProApi.userMessage(ProError("not_subscribed", 403)).contains("etkin değil"))
		assertEquals("Yeni kod", ProApi.userMessage(ProError("brand_new", 418, "Yeni kod")))
		assertEquals("Abonelik hatası (brand_new)", ProApi.userMessage(ProError("brand_new", 418)))
		listOf("not_subscribed", "invalid_purchase", "rate_limited", "server_error", "unauthorized", "bad_request",
			"upstream_error", "timeout", "network", "no_purchase").forEach {
			assertFalse(it, ProApi.userMessage(ProError(it)).startsWith("Abonelik hatası"))
		}
		assertEquals(403, ProApi.backoffCode(ProError("quota_exceeded", 429)))
		assertEquals(429, ProApi.backoffCode(ProError("rate_limited", 429)))
		assertEquals(502, ProApi.backoffCode(ProError("upstream_error", 502)))
		assertEquals(0, ProApi.backoffCode(ProError("timeout")))
	}
}
