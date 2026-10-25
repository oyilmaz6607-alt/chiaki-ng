// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.companion

import com.metallic.chiaki.companion.CompanionSettings.Value
import org.junit.Assert.*
import org.junit.Test

class CompanionTranslationSettingsTest
{
	@Test
	fun acceptsTranslationKeys()
	{
		val r = CompanionSettings.validate(linkedMapOf(
			"subtitle_translation_engine" to "gemini",
			"subtitle_gemini_api_key" to "  AIzaSyA1234567890abcdef_-X ",
			"subtitle_gemini_model" to "gemini-3.6-flash",
			"subtitle_translation_draft_first" to false
		))
		assertTrue(r.isValid)
		assertEquals(Value.StringValue("gemini"), r.values["subtitle_translation_engine"])
		assertEquals(Value.StringValue("AIzaSyA1234567890abcdef_-X"), r.values["subtitle_gemini_api_key"])
		assertEquals(Value.StringValue("gemini-3.6-flash"), r.values["subtitle_gemini_model"])
		assertEquals(Value.BoolValue(false), r.values["subtitle_translation_draft_first"])
	}

	@Test
	fun emptyKeyClearsIt()
	{
		val r = CompanionSettings.validate(mapOf("subtitle_gemini_api_key" to ""))
		assertTrue(r.isValid)
		assertEquals(Value.StringValue(""), r.values["subtitle_gemini_api_key"])
	}

	@Test
	fun rejectsBadTranslationValues()
	{
		listOf(
			"subtitle_translation_engine" to "deepl",
			"subtitle_translation_engine" to true,
			"subtitle_gemini_model" to "gemini-1.0-pro",
			"subtitle_gemini_model" to "../x",
			// maskeli değer geri gönderilirse kaydedilmez
			"subtitle_gemini_api_key" to "AIza…cdef",
			"subtitle_gemini_api_key" to "has space in key 123",
			"subtitle_gemini_api_key" to "x".repeat(201),
			"subtitle_gemini_api_key" to 12345678901,
			"subtitle_gemini_api_key" to null,
			"subtitle_translation_draft_first" to "maybe"
		).forEach { (key, value) ->
			val r = CompanionSettings.validate(mapOf(key to value))
			assertFalse("$key=$value", r.isValid)
			assertTrue(r.values.isEmpty())
			// Hata mesajı gönderilen değeri geri yansıtmaz.
			assertFalse(r.errors.values.any { it.contains("AIza") })
		}
	}

	@Test
	fun translationKeysSurviveStyleReset()
	{
		CompanionSettings.TRANSLATION_KEYS.forEach {
			assertNotNull(it, CompanionSettings.spec(it))
			assertFalse(it, CompanionSettings.STYLE_KEYS.contains(it))
		}
		assertTrue(CompanionSettings.spec(CompanionSettings.KEY_GEMINI_API_KEY) is CompanionSettings.Spec.Secret)
	}
}
