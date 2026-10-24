// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.companion

import com.metallic.chiaki.companion.CompanionSettings.Value
import org.junit.Assert.*
import org.junit.Test

class CompanionSettingsTest
{
	@Test
	fun acceptsValidValues()
	{
		val r = CompanionSettings.validate(linkedMapOf(
			"subtitle_text_size" to 40,
			"subtitle_caption_bold" to false,
			"subtitle_caption_font" to "lexend",
			"subtitle_caption_text_color" to "#ffd60a",
			"subtitle_caption_bg_opacity" to 0.0, // org.json sayıları Double olarak da gelebilir
			"subtitle_caption_line_spacing" to "1.25",
			"subtitle_capture_percent" to "50",
			"subtitle_interval_ms" to 1500L
		))
		assertTrue(r.isValid)
		assertFalse(r.reset)
		assertEquals(Value.IntValue(40), r.values["subtitle_text_size"])
		assertEquals(Value.BoolValue(false), r.values["subtitle_caption_bold"])
		assertEquals(Value.StringValue("lexend"), r.values["subtitle_caption_font"])
		// Renk listedeki yazımla kaydedilir.
		assertEquals(Value.StringValue("#FFD60A"), r.values["subtitle_caption_text_color"])
		assertEquals(Value.IntValue(0), r.values["subtitle_caption_bg_opacity"])
		assertEquals(Value.StringValue("1.25"), r.values["subtitle_caption_line_spacing"])
		assertEquals(Value.IntValue(50), r.values["subtitle_capture_percent"])
		assertEquals(Value.IntValue(1500), r.values["subtitle_interval_ms"])
	}

	@Test
	fun rejectsUnknownKeys()
	{
		val r = CompanionSettings.validate(mapOf("subtitle_text_size" to 30, "preferences_log_verbose" to true))
		assertFalse(r.isValid)
		assertTrue(r.values.isEmpty())
		assertTrue(r.errors.containsKey("preferences_log_verbose"))
	}

	@Test
	fun rejectsOutOfRangeAndWrongTypes()
	{
		listOf(
			"subtitle_text_size" to 13,
			"subtitle_text_size" to 57,
			"subtitle_text_size" to 30.5,
			"subtitle_text_size" to "30px",
			"subtitle_text_size" to true,
			"subtitle_text_size" to null,
			"subtitle_caption_bg_opacity" to 101,
			"subtitle_caption_corner_radius" to -1,
			"subtitle_caption_max_width" to 49,
			"subtitle_capture_percent" to 5,
			"subtitle_interval_ms" to 100,
			"subtitle_interval_ms" to Double.NaN,
			"subtitle_caption_bold" to "yes",
			"subtitle_caption_bold" to 1,
			"subtitle_caption_font" to "comic_sans",
			"subtitle_caption_text_color" to "#123456",
			"subtitle_caption_bg_color" to "red",
			"subtitle_caption_shadow" to "glow",
			"subtitle_caption_position" to "left",
			"subtitle_caption_line_spacing" to "2.0",
			"subtitle_caption_line_spacing" to 1.1
		).forEach { (key, value) ->
			val r = CompanionSettings.validate(mapOf(key to value))
			assertFalse("$key=$value", r.isValid)
			assertTrue(r.values.isEmpty())
		}
	}

	@Test
	fun boundsAreInclusive()
	{
		val r = CompanionSettings.validate(mapOf(
			"subtitle_text_size" to 14, "subtitle_caption_bg_opacity" to 100,
			"subtitle_caption_max_width" to 50, "subtitle_capture_percent" to 100, "subtitle_interval_ms" to 150))
		assertTrue(r.isValid)
		assertEquals(5, r.values.size)
	}

	@Test
	fun resetFlag()
	{
		val r = CompanionSettings.validate(mapOf(CompanionSettings.RESET_FIELD to true))
		assertTrue(r.isValid)
		assertTrue(r.reset)
		assertTrue(r.values.isEmpty())
		assertFalse(CompanionSettings.validate(mapOf(CompanionSettings.RESET_FIELD to "x")).isValid)
		assertFalse(CompanionSettings.STYLE_KEYS.contains(CompanionSettings.KEY_CAPTURE_PERCENT))
		assertFalse(CompanionSettings.STYLE_KEYS.contains(CompanionSettings.KEY_INTERVAL_MS))
		assertEquals(11, CompanionSettings.STYLE_KEYS.size)
	}

	@Test
	fun specsHaveUniqueKeysAndValidDefaults()
	{
		val keys = CompanionSettings.SPECS.map { it.key }
		assertEquals(keys.size, keys.toSet().size)
		CompanionSettings.SPECS.forEach { spec ->
			val default: Any = when(spec)
			{
				is CompanionSettings.Spec.IntRange -> spec.default
				is CompanionSettings.Spec.Bool -> spec.default
				is CompanionSettings.Spec.Choice -> spec.default
			}
			assertNotNull(spec.key, CompanionSettings.validateValue(spec, default))
		}
	}
}
