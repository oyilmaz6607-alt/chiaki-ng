// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

/**
 * Abonelik (Pro) çevirisinin yönlendirme kuralları (Android bağımlılığı yok).
 *
 * - Kısa satırlar ([isOnDevice]: ≤ 3 kelime veya ≤ 18 karakter, büyük harfli arayüz yazıları, yalnızca sayı/işaret)
 *   sunucuya hiç gitmez, cihaz içi (ML Kit) çevrilir ve kotadan düşmez. 1–3 kelimelik satırlarda cihaz içi
 *   çeviri yapay zekâ ile eşdeğerdir; uzun replikler her zaman yapay zekâya gider.
 * - Önceki satırlar (bağlam) yalnızca işe yaradığında gönderilir ([wantsContext]): ≥ 6 kelimelik ve
 *   zamir/gönderme içeren (it, they, he, she, this, that, there…) ya da küçük harfle veya "and/but/so" ile başlayan
 *   satırlar. En fazla son 2 çift, her metin 160 karakter. Sunucu aynı kuralı ayrıca uygular (PRO_API.md v1.2).
 */
object ProRouting
{
	const val SHORT_MAX_WORDS = 3
	const val SHORT_MAX_CHARS = 18
	/** Tamamı büyük harfli satır bu kadar kelimeye kadar arayüz yazısı sayılır ("PRESS X TO CONTINUE"). */
	const val LABEL_MAX_WORDS = 5

	const val CONTEXT_MIN_WORDS = 6
	const val CONTEXT_PAIRS = 2
	const val CONTEXT_CHARS = 160

	private val REFERENCE_WORDS = setOf("it", "they", "he", "she", "him", "her", "this", "that", "there", "them", "his", "its", "those", "these")
	private val WS = Regex("\\s+")
	private val LATIN_WORD = Regex("[a-z]+")

	private fun words(text: String): List<String> = text.trim().split(WS).filter { it.isNotEmpty() }

	/** Kısa satır mı: abonelikte sunucuya gönderilmeden cihaz içi çevrilir (kota harcamaz). */
	fun isOnDevice(text: String): Boolean
	{
		val t = text.trim().replace(WS, " ")
		if(t.isEmpty())
			return true
		// Yalnızca harf/rakam içeren parçalar kelime sayılır ("SAVING - DO NOT" = 3 kelime).
		val n = words(t).count { w -> w.any { it.isLetterOrDigit() } }
		if(n <= SHORT_MAX_WORDS || t.length <= SHORT_MAX_CHARS)
			return true
		val letters = t.filter { it.isLetter() }
		if(letters.isEmpty())
			return true // yalnızca sayı/işaret: "120 / 250", "03:45"
		val allCaps = letters.none { it.isLowerCase() } && letters.any { it.isUpperCase() }
		return allCaps && n <= LABEL_MAX_WORDS
	}

	/** Önceki satırlar bu satırın çevirisine yardım eder mi (sunucudaki contextUseful ile aynı kural)? */
	fun wantsContext(text: String): Boolean
	{
		val t = text.trim()
		if(words(t).size < CONTEXT_MIN_WORDS)
			return false
		val first = t.firstOrNull { it.isLetter() } ?: return false
		if(first.isLowerCase())
			return true
		val latin = LATIN_WORD.findAll(t.lowercase()).map { it.value }.toList()
		if(latin.firstOrNull().let { it == "and" || it == "but" || it == "so" })
			return true
		return latin.any { it in REFERENCE_WORDS }
	}

	/** Sunucuya gidecek bağlam: gerekmiyorsa boş; gerekiyorsa son [CONTEXT_PAIRS] çift, her metin [CONTEXT_CHARS] karakter. */
	fun contextFor(text: String, pairs: List<SubtitlePair>): List<SubtitlePair>
	{
		if(pairs.isEmpty() || !wantsContext(text))
			return emptyList()
		return pairs.filter { it.english.isNotBlank() && it.turkish.isNotBlank() }
			.takeLast(CONTEXT_PAIRS)
			.map { SubtitlePair(it.english.trim().take(CONTEXT_CHARS), it.turkish.trim().take(CONTEXT_CHARS)) }
	}
}
