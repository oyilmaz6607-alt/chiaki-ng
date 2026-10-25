// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.companion

import com.metallic.chiaki.subtitle.TranslationSettings

/**
 * AltyazıTR telefon kumandası: telefondan değiştirilebilen ayarların izin listesi ve doğrulaması.
 * Android'e bağımlı değildir.
 *
 * Anahtarlar SharedPreferences anahtarlarıyla birebir aynıdır (res/values/strings.xml → preferences_*_key);
 * aralıklar ve seçenekler res/xml/preferences*.xml, res/values/strings.xml dizileri ve
 * CaptionStyle/Preferences içindeki sınırlarla aynı tutulmalıdır.
 * Bu listede olmayan hiçbir anahtar yazılmaz.
 */
object CompanionSettings
{
	sealed class Spec(val key: String)
	{
		/** SeekBarPreference: SharedPreferences'a putInt ile yazılır. */
		class IntRange(key: String, val min: Int, val max: Int, val default: Int, val step: Int = 1): Spec(key)
		/** SwitchPreference: putBoolean. */
		class Bool(key: String, val default: Boolean): Spec(key)
		/** ListPreference: putString, yalnızca listedeki değerler. */
		open class Choice(key: String, val options: List<String>, val default: String): Spec(key)
		/**
		 * Gizli metin (API anahtarı): putString. GET yanıtında asla açık değeri değil, yalnızca maskeli hâli döner.
		 * Boş dizge = sil. Seçenek listesi yoktur; [Choice]'tan türetilmesinin tek nedeni Spec üzerindeki
		 * mevcut `when` ifadelerinin kapsayıcı kalmasıdır — `when` içinde her zaman Choice'tan ÖNCE eşleştirin.
		 */
		class Secret(key: String, val maxLength: Int, val validate: (String) -> String?): Choice(key, emptyList(), "")
	}

	sealed class Value
	{
		data class IntValue(val value: Int): Value()
		data class BoolValue(val value: Boolean): Value()
		data class StringValue(val value: String): Value()
	}

	const val KEY_TEXT_SIZE = "subtitle_text_size"
	const val KEY_FONT = "subtitle_caption_font"
	const val KEY_BOLD = "subtitle_caption_bold"
	const val KEY_TEXT_COLOR = "subtitle_caption_text_color"
	const val KEY_BG_COLOR = "subtitle_caption_bg_color"
	const val KEY_BG_OPACITY = "subtitle_caption_bg_opacity"
	const val KEY_CORNER_RADIUS = "subtitle_caption_corner_radius"
	const val KEY_SHADOW = "subtitle_caption_shadow"
	const val KEY_LINE_SPACING = "subtitle_caption_line_spacing"
	const val KEY_POSITION = "subtitle_caption_position"
	const val KEY_MAX_WIDTH = "subtitle_caption_max_width"
	const val KEY_CAPTURE_PERCENT = "subtitle_capture_percent"
	const val KEY_INTERVAL_MS = "subtitle_interval_ms"
	const val KEY_TRANSLATION_ENGINE = TranslationSettings.KEY_ENGINE
	const val KEY_GEMINI_API_KEY = TranslationSettings.KEY_GEMINI_API_KEY
	const val KEY_GEMINI_MODEL = TranslationSettings.KEY_GEMINI_MODEL
	const val KEY_DRAFT_FIRST = TranslationSettings.KEY_DRAFT_FIRST

	/** İstek gövdesinde bu alan true ise görünüm ayarları varsayılana döner (tarama ayarları korunur). */
	const val RESET_FIELD = "_reset"

	val FONTS = listOf("inter", "atkinson", "lexend", "noto_sans", "system")
	val TEXT_COLORS = listOf("#FFFFFF", "#FFD60A", "#FFF3A6", "#9AD4FF", "#5CF2F2", "#8CF08C", "#D6D6D6")
	val BG_COLORS = listOf("#000000", "#2A2A2A", "#0B1B3A", "#2B1D14")
	val SHADOWS = listOf("none", "soft", "outline")
	val LINE_SPACINGS = listOf("1.0", "1.1", "1.25", "1.4")
	val POSITIONS = listOf("cover", "bottom", "top")

	/** Sıra, telefondaki form sırasıdır. */
	val SPECS: List<Spec> = listOf(
		Spec.Choice(KEY_FONT, FONTS, "inter"),
		Spec.IntRange(KEY_TEXT_SIZE, 14, 56, 30, 2),
		Spec.Bool(KEY_BOLD, true),
		Spec.Choice(KEY_TEXT_COLOR, TEXT_COLORS, "#FFFFFF"),
		Spec.Choice(KEY_BG_COLOR, BG_COLORS, "#000000"),
		Spec.IntRange(KEY_BG_OPACITY, 0, 100, 75, 5),
		Spec.IntRange(KEY_CORNER_RADIUS, 0, 24, 8, 2),
		Spec.Choice(KEY_SHADOW, SHADOWS, "soft"),
		Spec.Choice(KEY_POSITION, POSITIONS, "cover"),
		Spec.IntRange(KEY_MAX_WIDTH, 50, 100, 80, 5),
		Spec.Choice(KEY_LINE_SPACING, LINE_SPACINGS, "1.1"),
		Spec.IntRange(KEY_CAPTURE_PERCENT, 10, 100, 35, 5),
		Spec.IntRange(KEY_INTERVAL_MS, 150, 1500, 300, 50),
		Spec.Choice(KEY_TRANSLATION_ENGINE, TranslationSettings.ENGINES, TranslationSettings.ENGINE_MLKIT),
		Spec.Secret(KEY_GEMINI_API_KEY, TranslationSettings.API_KEY_MAX_LENGTH) { TranslationSettings.normalizeApiKey(it) },
		Spec.Choice(KEY_GEMINI_MODEL, TranslationSettings.MODELS, TranslationSettings.DEFAULT_MODEL),
		Spec.Bool(KEY_DRAFT_FIRST, TranslationSettings.DEFAULT_DRAFT_FIRST)
	)

	/** Çeviri ayarları: görünüm sıfırlamasında silinmez. */
	val TRANSLATION_KEYS: Set<String> = setOf(KEY_TRANSLATION_ENGINE, KEY_GEMINI_API_KEY, KEY_GEMINI_MODEL, KEY_DRAFT_FIRST)

	private val byKey: Map<String, Spec> = SPECS.associateBy { it.key }

	/** Sıfırlamada silinecek görünüm anahtarları (tarama alanı/aralığı hariç). */
	val STYLE_KEYS: List<String> = SPECS.map { it.key }
		.filter { it != KEY_CAPTURE_PERCENT && it != KEY_INTERVAL_MS && it !in TRANSLATION_KEYS }

	fun spec(key: String): Spec? = byKey[key]

	class Result(
		/** Geçerli değerler, gövdedeki sırayla. */
		val values: LinkedHashMap<String, Value>,
		/** Anahtar → okunur hata. Boş değilse hiçbir şey yazılmamalıdır. */
		val errors: Map<String, String>,
		val reset: Boolean
	)
	{
		val isValid get() = errors.isEmpty()
	}

	/**
	 * [input]: JSON nesnesinin alanları (Number, Boolean, String veya null).
	 * Bilinmeyen anahtar, yanlış tür veya aralık dışı değer → hata (istek bütünüyle reddedilir).
	 */
	fun validate(input: Map<String, Any?>): Result
	{
		val values = LinkedHashMap<String, Value>()
		val errors = LinkedHashMap<String, String>()
		var reset = false
		for((key, raw) in input)
		{
			if(key == RESET_FIELD)
			{
				if(raw == true)
					reset = true
				else if(raw != false && raw != null)
					errors[key] = "must be boolean"
				continue
			}
			val spec = byKey[key]
			if(spec == null)
			{
				errors[safeKey(key)] = "unknown setting"
				continue
			}
			when(val v = validateValue(spec, raw))
			{
				null -> errors[key] = describe(spec)
				else -> values[key] = v
			}
		}
		if(errors.size > 0)
			values.clear()
		return Result(values, errors, reset)
	}

	fun validateValue(spec: Spec, raw: Any?): Value? = when(spec)
	{
		is Spec.IntRange -> toInt(raw)?.takeIf { it in spec.min..spec.max }?.let { Value.IntValue(it) }
		is Spec.Bool -> when(raw)
		{
			is Boolean -> Value.BoolValue(raw)
			"true" -> Value.BoolValue(true)
			"false" -> Value.BoolValue(false)
			else -> null
		}
		is Spec.Secret -> (raw as? String)?.takeIf { it.length <= spec.maxLength }?.let(spec.validate)?.let { Value.StringValue(it) }
		is Spec.Choice -> (raw as? String)?.let { s ->
			// Renkler büyük/küçük harf duyarsız kabul edilir ama listedeki yazımla kaydedilir.
			spec.options.firstOrNull { it.equals(s.trim(), ignoreCase = true) }
		}?.let { Value.StringValue(it) }
	}

	private fun toInt(raw: Any?): Int? = when(raw)
	{
		is Int -> raw
		is Long -> if(raw in Int.MIN_VALUE..Int.MAX_VALUE) raw.toInt() else null
		is Short -> raw.toInt()
		is Byte -> raw.toInt()
		is Double -> if(!raw.isNaN() && !raw.isInfinite() && raw == Math.rint(raw) && raw >= Int.MIN_VALUE && raw <= Int.MAX_VALUE) raw.toInt() else null
		is Float -> if(!raw.isNaN() && !raw.isInfinite() && raw == Math.rint(raw.toDouble()).toFloat()) raw.toInt() else null
		is Number -> raw.toDouble().let { d -> if(d == Math.rint(d) && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt() else null }
		is String -> raw.trim().takeIf { it.length in 1..6 && it.all { c -> c in '0'..'9' } }?.toInt()
		else -> null
	}

	private fun describe(spec: Spec): String = when(spec)
	{
		is Spec.IntRange -> "must be an integer in ${spec.min}..${spec.max}"
		is Spec.Bool -> "must be boolean"
		is Spec.Secret -> "invalid value"
		is Spec.Choice -> "must be one of ${spec.options.joinToString(", ")}"
	}

	/** Hata mesajında geri yansıtılan bilinmeyen anahtar kısaltılır. */
	private fun safeKey(key: String) = key.take(40).filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "?" }
}
