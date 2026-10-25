// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import org.junit.Assert.*
import org.junit.Test

class GeminiRequestTest
{
	@Test
	fun bodyIsValidJsonWithContextAndThinking()
	{
		val ctx = (1..6).map { SubtitlePair("Line $it \"quoted\"", "Satır $it") }
		val body = GeminiRequest.body("gemini-3.5-flash-lite", "KRATOS: Loss of awareness.\nStay close, boy.", ctx)
		val root = MiniJson.parse(body) as Map<*, *>
		val sys = (((root["systemInstruction"] as Map<*, *>)["parts"] as List<*>)[0] as Map<*, *>)["text"] as String
		assertTrue(sys.contains("NAME:"))
		val user = ((((root["contents"] as List<*>)[0] as Map<*, *>)["parts"] as List<*>)[0] as Map<*, *>)["text"] as String
		assertTrue(user.endsWith("LINE: KRATOS: Loss of awareness. Stay close, boy."))
		// yalnızca son 4 çift bağlam olarak gider
		assertFalse(user.contains("Line 2 "))
		assertTrue(user.contains("EN: Line 3 \"quoted\"\nTR: Satır 3"))
		assertTrue(user.contains("Line 6"))
		val gen = root["generationConfig"] as Map<*, *>
		assertEquals(GeminiRequest.MAX_OUTPUT_TOKENS.toLong(), gen["maxOutputTokens"])
		assertEquals("minimal", (gen["thinkingConfig"] as Map<*, *>)["thinkingLevel"])
		assertNull(gen["temperature"])
		// görüntü yok, yalnızca metin
		assertFalse(body.contains("inlineData") || body.contains("inline_data"))
	}

	@Test
	fun thinkingLevelPerModel()
	{
		assertEquals("{\"thinkingLevel\":\"minimal\"}", GeminiRequest.thinkingConfigJson("gemini-3.1-flash-lite"))
		assertEquals("{\"thinkingLevel\":\"minimal\"}", GeminiRequest.thinkingConfigJson("gemini-3.6-flash"))
		// 3.8 Flash "minimal"i reddeder
		assertEquals("{\"thinkingLevel\":\"low\"}", GeminiRequest.thinkingConfigJson("gemini-3.8-flash"))
		assertEquals("{\"thinkingBudget\":0}", GeminiRequest.thinkingConfigJson("gemini-2.5-flash-lite"))
		assertNull(GeminiRequest.thinkingConfigJson("gemini-2.0-flash"))
		val noThink = MiniJson.parse(GeminiRequest.body("gemini-3.6-flash", "Hi", emptyList(), withThinking = false)) as Map<*, *>
		assertNull((noThink["generationConfig"] as Map<*, *>)["thinkingConfig"])
	}

	@Test
	fun urlAndModelValidation()
	{
		assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent",
			GeminiRequest.url("gemini-3.5-flash-lite"))
		assertEquals(TranslationSettings.DEFAULT_MODEL, TranslationSettings.normalizeModel("../evil?x=1"))
		assertEquals(TranslationSettings.DEFAULT_MODEL, TranslationSettings.normalizeModel(null))
		assertEquals("gemini-3.8-flash", TranslationSettings.normalizeModel(" gemini-3.8-flash "))
	}

	@Test
	fun parsesResponseSkippingThoughts()
	{
		val json = """
			{"candidates":[{"content":{"role":"model","parts":[
				{"text":"thinking...","thought":true},
				{"text":"KRATOS: Farkındalık kaybı.\n"},{"text":" Yakın dur, evlat."}
			]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":120}}
		""".trimIndent()
		assertEquals(GeminiRequest.Parsed.Ok("KRATOS: Farkındalık kaybı. Yakın dur, evlat."), GeminiRequest.parseResponse(json))
	}

	@Test
	fun parsesErrorsAndEmpty()
	{
		assertTrue(GeminiRequest.parseResponse("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}") is GeminiRequest.Parsed.Error)
		assertTrue(GeminiRequest.parseResponse("not json") is GeminiRequest.Parsed.Error)
		assertTrue(GeminiRequest.parseResponse("{\"candidates\":[{\"content\":{\"parts\":[]},\"finishReason\":\"MAX_TOKENS\"}]}") is GeminiRequest.Parsed.Error)
		assertEquals("HTTP 400: API key not valid. Please pass a valid API key.",
			GeminiRequest.parseError(400, "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\",\"status\":\"INVALID_ARGUMENT\"}}"))
		assertEquals("HTTP 503", GeminiRequest.parseError(503, "<html>"))
	}

	@Test
	fun cleansModelOutput()
	{
		assertEquals("Merhaba.", GeminiRequest.cleanOutput("\"Merhaba.\""))
		assertEquals("Merhaba.", GeminiRequest.cleanOutput("TR: Merhaba."))
		assertEquals("Merhaba dünya", GeminiRequest.cleanOutput("“Merhaba\ndünya”"))
		assertEquals("ATREUS: Tamam.", GeminiRequest.cleanOutput("ATREUS: Tamam."))
	}

	@Test
	fun engineSelectionAndKeyHandling()
	{
		assertEquals("mlkit", TranslationSettings.effectiveEngine(null, null))
		assertEquals("gemini", TranslationSettings.effectiveEngine(null, "AIzaSyA1234567890abcdef"))
		assertEquals("mlkit", TranslationSettings.effectiveEngine("gemini", ""))
		assertEquals("mlkit", TranslationSettings.effectiveEngine("mlkit", "AIzaSyA1234567890abcdef"))
		assertEquals("mlkit", TranslationSettings.effectiveEngine("bogus", null))
		assertEquals("AIza…cdef", TranslationSettings.maskApiKey("AIzaSyA1234567890abcdef"))
		assertEquals("", TranslationSettings.maskApiKey(null))
		assertEquals("", TranslationSettings.normalizeApiKey("  "))
		assertEquals("AIzaSyA1234567890abcdef", TranslationSettings.normalizeApiKey(" AIzaSyA1234567890abcdef "))
		assertNull(TranslationSettings.normalizeApiKey("AIza…cdef"))
		assertNull(TranslationSettings.normalizeApiKey("short"))
		assertNull(TranslationSettings.normalizeApiKey("AIza Sy\nA1234567890"))
	}

	@Test
	fun refineStateShowsDraftThenFinal()
	{
		val s = RefineState()
		val g1 = s.newGeneration()
		assertTrue(s.offer(g1, RefineState.Source.MLKIT))
		assertTrue(s.offer(g1, RefineState.Source.GEMINI))
		// geç gelen taslak Gemini'yi ezmez
		assertFalse(s.offer(g1, RefineState.Source.MLKIT))
		val g2 = s.newGeneration()
		// eski neslin sonucu gösterilmez
		assertFalse(s.offer(g1, RefineState.Source.GEMINI))
		assertTrue(s.offer(g2, RefineState.Source.GEMINI))
	}

	@Test
	fun backoff()
	{
		val b = GeminiBackoff(baseMs = 1000, maxMs = 10_000)
		assertTrue(b.canTry(0))
		b.onFailure(0, 0) // tek zaman aşımı
		assertTrue(b.canTry(1))
		b.onFailure(1, 0)
		assertFalse(b.canTry(500))
		assertTrue(b.canTry(1001))
		b.onSuccess()
		b.onFailure(2000, 403)
		assertFalse(b.canTry(11_000))
		assertTrue(b.canTry(12_000))
	}
}
