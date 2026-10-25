// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

/**
 * Çeviri motoru seçenekleri ve Gemini ayarlarıyla ilgili sabitler (Android bağımlılığı yok).
 * Anahtarlar SharedPreferences anahtarlarıyla aynıdır (res/values/strings_translation.xml).
 */
object TranslationSettings
{
	const val KEY_ENGINE = "subtitle_translation_engine"
	const val KEY_GEMINI_API_KEY = "subtitle_gemini_api_key"
	const val KEY_GEMINI_MODEL = "subtitle_gemini_model"
	const val KEY_DRAFT_FIRST = "subtitle_translation_draft_first"

	const val ENGINE_MLKIT = "mlkit"
	const val ENGINE_GEMINI = "gemini"
	/** AltyazıTR Pro (abonelik): çeviri bizim sunucumuz üzerinden, kullanıcı anahtarı gerekmez (PRO_API.md). */
	const val ENGINE_PRO = "pro"
	val ENGINES = listOf(ENGINE_MLKIT, ENGINE_GEMINI, ENGINE_PRO)

	/** https://ai.google.dev/gemini-api/docs/models — kararlı (stable) model kimlikleri. */
	const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
	val MODELS = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.6-flash", "gemini-3.8-flash")

	const val DEFAULT_DRAFT_FIRST = true
	const val API_KEY_MAX_LENGTH = 200

	/** Kullanıcı hiç seçmediyse: abone → Pro; değilse anahtar varsa Gemini; yoksa cihaz içi. */
	fun defaultEngine(hasKey: Boolean, subscribed: Boolean = false): String = when
	{
		subscribed -> ENGINE_PRO
		hasKey -> ENGINE_GEMINI
		else -> ENGINE_MLKIT
	}

	/** Kayıtlı (seçilen) motor; geçersiz/boşsa [defaultEngine]. */
	fun chosenEngine(stored: String?, apiKey: String?, subscribed: Boolean = false): String =
		stored?.takeIf { it in ENGINES } ?: defaultEngine(!apiKey.isNullOrBlank(), subscribed)

	/**
	 * Etkin motor: kullanıcı açıkça seçtiyse o; hiç seçmediyse [defaultEngine].
	 * Gemini seçili ama anahtar yoksa cihaz içi; Pro seçili ama abonelik yoksa anahtar varsa Gemini, yoksa cihaz içi.
	 */
	fun effectiveEngine(stored: String?, apiKey: String?, subscribed: Boolean = false): String
	{
		val hasKey = !apiKey.isNullOrBlank()
		return when(chosenEngine(stored, apiKey, subscribed))
		{
			ENGINE_PRO -> if(subscribed) ENGINE_PRO else if(hasKey) ENGINE_GEMINI else ENGINE_MLKIT
			ENGINE_GEMINI -> if(hasKey) ENGINE_GEMINI else ENGINE_MLKIT
			else -> ENGINE_MLKIT
		}
	}

	/** Sunucuya giden (internet gerektiren) motor mu? Cihaz içi taslak + iyileştirme akışı bunlar için çalışır. */
	fun isRemote(engine: String) = engine == ENGINE_GEMINI || engine == ENGINE_PRO

	fun normalizeModel(model: String?): String = model?.trim()?.takeIf { isValidModelId(it) } ?: DEFAULT_MODEL

	/** Model kimliği URL yoluna girer: yalnızca güvenli karakterler. */
	fun isValidModelId(model: String): Boolean =
		model.length in 3..64 && model.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' || it == '_' }

	/**
	 * Gemini API anahtarı (kullanıcının kendi anahtarı, aistudio.google.com/apikey) için biçim kontrolü.
	 * Boş = anahtarı sil. Maskeli değer (… veya • içeren) asla kaydedilmez.
	 */
	fun normalizeApiKey(raw: String): String?
	{
		val k = raw.trim()
		if(k.isEmpty())
			return ""
		if(k.length > API_KEY_MAX_LENGTH || k.length < 10)
			return null
		if(!k.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' })
			return null
		return k
	}

	/** Ekranda/telefonda gösterilecek maskeli anahtar: "AIza…x7Q2". Boşsa "". */
	fun maskApiKey(key: String?): String
	{
		val k = key?.trim() ?: return ""
		if(k.isEmpty())
			return ""
		if(k.length < 12)
			return "••••"
		return k.take(4) + "…" + k.takeLast(4)
	}
}
