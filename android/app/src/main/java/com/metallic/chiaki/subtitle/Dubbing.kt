// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import java.util.Locale

/**
 * Sesli dublaj için saf (Android bağımlılığı olmayan) mantık: ses profilleri, ses konumu (pan),
 * konuşmacı ayrıştırma ve konuşmacıdan sese eşleme. Seslendirme motoru [DubbingSpeaker] tarafındadır.
 */
object DubbingSettings
{
	const val KEY_ENABLED = "subtitle_dubbing_enabled"
	const val KEY_DEFAULT_PROFILE = "subtitle_dubbing_default_profile"
	const val KEY_SPEAKERS = "subtitle_dubbing_speakers"
	const val KEY_FAST = "subtitle_dubbing_fast"
	const val KEY_GAME = "subtitle_game_profile"
	const val DEFAULT_PROFILE = "erkek-yetiskin"
	const val GAME_GENERAL = "genel"
	const val GAME_GTA6 = "gta6"
	/** Aynı satır bu süre içinde yeniden gelirse (büyüyen/yenilenen altyazı) ikinci kez okunmaz. */
	const val REPEAT_WINDOW_MS = 6000L
}

/** Ses profili: cinsiyet + yaş. Motorun cinsiyet bilgisi vermediği için perde ve hız yaşa göre ayarlanır. */
data class VoiceProfile(val female: Boolean, val age: Age)
{
	enum class Age(val key: String) { CHILD("cocuk"), ADULT("yetiskin"), ELDER("yasli") }

	val key: String get() = (if(female) "kadin" else "erkek") + "-" + age.key

	/** Perde (1.0 normal). Çocuk sesi yüksek, yaşlı erkek sesi düşük. */
	val pitch: Float get() = when(age)
	{
		Age.CHILD -> 1.35f
		Age.ADULT -> if(female) 1.05f else 0.9f
		Age.ELDER -> if(female) 0.95f else 0.8f
	}

	/** Konuşma hızı (1.0 normal). */
	val rate: Float get() = when(age)
	{
		Age.CHILD -> 1.1f
		Age.ADULT -> 1.0f
		Age.ELDER -> 0.88f
	}

	companion object
	{
		val ALL: List<VoiceProfile> = listOf(
			VoiceProfile(false, Age.ADULT), VoiceProfile(true, Age.ADULT),
			VoiceProfile(false, Age.ELDER), VoiceProfile(true, Age.ELDER),
			VoiceProfile(false, Age.CHILD), VoiceProfile(true, Age.CHILD))

		/** "kadin-cocuk" gibi anahtarı profile çevirir; tanınmazsa null. */
		fun parse(key: String?): VoiceProfile?
		{
			val k = key?.trim()?.lowercase(Locale.ROOT) ?: return null
			return ALL.firstOrNull { it.key == k }
		}
	}
}

/** Ses konumu: kulaklıkta/TV'de sesin sol, orta ya da sağdan gelmesi. */
enum class Pan(val key: String, val value: Float)
{
	CENTER("orta", 0f), LEFT("sol", -1f), RIGHT("sag", 1f);

	companion object
	{
		fun parse(key: String?): Pan? = key?.trim()?.lowercase(Locale.ROOT)?.let { k -> values().firstOrNull { it.key == k } }
	}
}

/** Bir konuşmacının sesi: profil + konum. */
data class SpeakerVoice(val profile: VoiceProfile, val pan: Pan = Pan.CENTER)

/** Altyazı satırından konuşmacı adını ve okunacak metni çıkarır. */
object DubbingParser
{
	/** "AVCI: Buraya gel" — konuşmacı adı büyük harfli ve en fazla 24 karakter. */
	private val SPEAKER = Regex("^\\s*([A-ZÇĞİÖŞÜ][A-ZÇĞİÖŞÜ .'\\-]{1,23}?)\\s*:\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)

	data class Line(val speaker: String?, val text: String)

	/**
	 * [english] İngilizce kaynak (konuşmacı burada aranır), [turkish] çeviri (okunacak metin).
	 * Türkçede de başta aynı etiket kaldıysa okunmadan çıkarılır.
	 */
	fun parse(english: String, turkish: String): Line
	{
		val speaker = SPEAKER.find(english.trim())?.let { normalizeName(it.groupValues[1]) }
		val t = turkish.trim()
		val text = SPEAKER.find(t)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() } ?: t
		return Line(speaker, text)
	}

	/** Konuşmacı adları İngilizce altyazıdan gelir: ASCII büyük harf (Locale.ROOT), yerel ayardan bağımsız. */
	fun normalizeName(name: String): String = name.trim().uppercase(Locale.ROOT)
}

/** Konuşmacı → ses eşlemesi. Biçim: "AVCI=erkek-yetiskin@sol;KIZ=kadin-cocuk" (@konum isteğe bağlı). */
object VoiceMap
{
	fun parse(raw: String?): Map<String, SpeakerVoice>
	{
		val out = LinkedHashMap<String, SpeakerVoice>()
		raw?.split(';')?.forEach { entry ->
			val i = entry.indexOf('=')
			if(i <= 0)
				return@forEach
			val name = DubbingParser.normalizeName(entry.substring(0, i))
			val value = entry.substring(i + 1)
			val at = value.indexOf('@')
			val profile = VoiceProfile.parse(if(at < 0) value else value.substring(0, at))
			val pan = if(at < 0) Pan.CENTER else Pan.parse(value.substring(at + 1)) ?: Pan.CENTER
			if(name.isNotEmpty() && profile != null)
				out[name] = SpeakerVoice(profile, pan)
		}
		return out
	}

	/** Konuşmacı eşlemede yoksa varsayılan ses; konuşmacı hiç yoksa da varsayılan. */
	fun voiceFor(map: Map<String, SpeakerVoice>, speaker: String?, default: SpeakerVoice): SpeakerVoice =
		speaker?.let { map[DubbingParser.normalizeName(it)] } ?: default

	/** Ayar metnini normalize eder (boşlukları temizler, geçersiz girdileri atar). */
	fun normalize(raw: String): String
	{
		val map = parse(raw)
		return map.entries.joinToString(";") { e ->
			"${e.key}=${e.value.profile.key}" + if(e.value.pan == Pan.CENTER) "" else "@${e.value.pan.key}"
		}
	}
}
