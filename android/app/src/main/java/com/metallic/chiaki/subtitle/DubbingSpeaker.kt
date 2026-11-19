// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.subtitle

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cihaz içi (ücretsiz, internetsiz) Türkçe seslendirme: Android TextToSpeech.
 * Her yeni satır öncekinin üstüne yazar (QUEUE_FLUSH) — gecikme birikmez.
 *
 * Not: Android ses motorları cinsiyet bilgisi vermez. Bu yüzden kadın/erkek ayrımı, cihazdaki
 * iki farklı Türkçe ses (varsa) ve perde/hız ayarıyla yapılır; sonuç cihaza göre değişir.
 */
class DubbingSpeaker(context: Context)
{
	private val appContext = context.applicationContext
	private val counter = AtomicInteger(0)

	@Volatile private var ready = false
	private var tts: TextToSpeech? = null
	/** Türkçe çevrimdışı sesler, ada göre sıralı. Erkek ilk sesi, kadın ikinci sesi (varsa) kullanır. */
	private var trVoices: List<Voice> = emptyList()

	init
	{
		tts = TextToSpeech(appContext) { status -> onInit(status) }
	}

	private fun onInit(status: Int)
	{
		val t = tts ?: return
		if(status != TextToSpeech.SUCCESS)
			return
		val tr = Locale.forLanguageTag("tr-TR")
		val res = t.setLanguage(tr)
		if(res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED)
			return
		t.setAudioAttributes(AudioAttributes.Builder()
			.setUsage(AudioAttributes.USAGE_MEDIA)
			.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
			.build())
		trVoices = try
		{
			t.voices.orEmpty()
				.filter { it.locale.language == "tr" && !it.isNetworkConnectionRequired }
				.sortedBy { it.name }
		}
		catch(e: Exception) { emptyList() }
		ready = true
	}

	/** Satırı verilen profil ve konumla okur (sol/orta/sağ). Motor hazır değilse sessizce atlanır. */
	fun speak(text: String, profile: VoiceProfile, pan: Pan)
	{
		val t = tts ?: return
		if(!ready || text.isBlank())
			return
		t.setPitch(profile.pitch)
		t.setSpeechRate(profile.rate)
		voiceFor(profile)?.let { runCatching { t.voice = it } }
		val params = Bundle().apply {
			putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, pan.value)
			putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f)
		}
		t.speak(text, TextToSpeech.QUEUE_FLUSH, params, "d" + counter.incrementAndGet())
	}

	/** Erkek → ilk Türkçe ses; kadın → ikinci Türkçe ses (yoksa ilk sesin perdesi kadın profilinde yükselir). */
	private fun voiceFor(profile: VoiceProfile): Voice?
	{
		if(trVoices.isEmpty())
			return null
		return if(profile.female && trVoices.size > 1) trVoices[1] else trVoices[0]
	}

	fun shutdown()
	{
		ready = false
		tts?.stop()
		tts?.shutdown()
		tts = null
	}
}
