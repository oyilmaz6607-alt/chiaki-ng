// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.app.Activity
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.widget.Toast
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import com.metallic.chiaki.R
import com.metallic.chiaki.billing.ProActivity
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.exportAndShareAllSettings
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.common.importSettingsFromUri
import com.metallic.chiaki.subtitle.CaptionPreviewPreference
import com.metallic.chiaki.subtitle.GeminiClient
import com.metallic.chiaki.subtitle.TranslationSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DataStore(val preferences: Preferences): PreferenceDataStore()
{
	override fun getBoolean(key: String?, defValue: Boolean) = when(key)
	{
		preferences.logVerboseKey -> preferences.logVerbose
		preferences.swapCrossMoonKey -> preferences.swapCrossMoon
		preferences.rumbleEnabledKey -> preferences.rumbleEnabled
		preferences.motionEnabledKey -> preferences.motionEnabled
		preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled
		else -> preferences.sharedPreferences.getBoolean(key, defValue)
	}

	override fun putBoolean(key: String?, value: Boolean)
	{
		when(key)
		{
			preferences.logVerboseKey -> preferences.logVerbose = value
			preferences.swapCrossMoonKey -> preferences.swapCrossMoon = value
			preferences.rumbleEnabledKey -> preferences.rumbleEnabled = value
			preferences.motionEnabledKey -> preferences.motionEnabled = value
			preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled = value
			else -> preferences.sharedPreferences.edit().putBoolean(key, value).apply()
		}
	}

	// Altyazı ayarları (SeekBar/List) gibi özel karşılığı olmayan anahtarlar doğrudan SharedPreferences'a yazılır.
	override fun getInt(key: String?, defValue: Int) = preferences.sharedPreferences.getInt(key, defValue)

	override fun putInt(key: String?, value: Int)
	{
		preferences.sharedPreferences.edit().putInt(key, value).apply()
	}

	override fun getString(key: String, defValue: String?) = when(key)
	{
		preferences.resolutionKey -> preferences.resolution.value
		preferences.fpsKey -> preferences.fps.value
		preferences.bitrateKey -> preferences.bitrate?.toString() ?: ""
		preferences.codecKey -> preferences.codec.value
		preferences.translationEngineKey -> preferences.translationEngineChoice
		preferences.geminiModelKey -> preferences.geminiModel
		else -> preferences.sharedPreferences.getString(key, defValue)
	}

	override fun putString(key: String, value: String?)
	{
		when(key)
		{
			preferences.resolutionKey ->
			{
				val resolution = Preferences.Resolution.values().firstOrNull { it.value == value } ?: return
				preferences.resolution = resolution
			}
			preferences.fpsKey ->
			{
				val fps = Preferences.FPS.values().firstOrNull { it.value == value } ?: return
				preferences.fps = fps
			}
			preferences.bitrateKey -> preferences.bitrate = value?.toIntOrNull()
			preferences.geminiApiKeyKey ->
			{
				// Geçersiz biçim kaydedilmez (SettingsFragment önceden uyarır).
				val key = TranslationSettings.normalizeApiKey(value ?: "") ?: return
				preferences.sharedPreferences.edit().putString(preferences.geminiApiKeyKey, key).apply()
			}
			preferences.codecKey ->
			{
				val codec = Preferences.Codec.values().firstOrNull { it.value == value } ?: return
				preferences.codec = codec
			}
			else -> preferences.sharedPreferences.edit().putString(key, value).apply()
		}
	}
}

class SettingsFragment: PreferenceFragmentCompat(), TitleFragment
{
	companion object
	{
		private const val PICK_SETTINGS_JSON_REQUEST = 1
	}

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
	{
		val context = context ?: return

		val viewModel = ViewModelProvider(this, viewModelFactory { SettingsViewModel(getDatabase(context), Preferences(context)) })
			.get(SettingsViewModel::class.java)

		val preferences = viewModel.preferences
		preferenceManager.preferenceDataStore = DataStore(preferences)
		setPreferencesFromResource(R.xml.preferences, rootKey)

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_resolution_key))?.let {
			it.entryValues = Preferences.resolutionAll.map { res -> res.value }.toTypedArray()
			it.entries = Preferences.resolutionAll.map { res -> getString(res.title) }.toTypedArray()
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_fps_key))?.let {
			it.entryValues = Preferences.fpsAll.map { fps -> fps.value }.toTypedArray()
			it.entries = Preferences.fpsAll.map { fps -> getString(fps.title) }.toTypedArray()
		}

		val bitratePreference = preferenceScreen.findPreference<EditTextPreference>(getString(R.string.preferences_bitrate_key))
		val bitrateSummaryProvider = Preference.SummaryProvider<EditTextPreference> {
			preferences.bitrate?.toString() ?: getString(R.string.preferences_bitrate_auto, preferences.bitrateAuto)
		}
		bitratePreference?.let {
			it.summaryProvider = bitrateSummaryProvider
			it.setOnBindEditTextListener { editText ->
				editText.hint = getString(R.string.preferences_bitrate_auto, preferences.bitrateAuto)
				editText.inputType = InputType.TYPE_CLASS_NUMBER
				editText.setText(preferences.bitrate?.toString() ?: "")
			}
		}
		viewModel.bitrateAuto.observe(this, Observer {
			bitratePreference?.summaryProvider = bitrateSummaryProvider
		})

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_codec_key))?.let {
			it.entryValues = Preferences.codecAll.map { codec -> codec.value }.toTypedArray()
			it.entries = Preferences.codecAll.map { codec -> getString(codec.title) }.toTypedArray()
		}

		setupTranslationPreferences(preferences)

		val registeredHostsPreference = preferenceScreen.findPreference<Preference>("registered_hosts")
		viewModel.registeredHostsCount.observe(this, Observer {
			registeredHostsPreference?.summary = getString(R.string.preferences_registered_hosts_summary, it)
		})

		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_export_settings_key))?.setOnPreferenceClickListener { exportSettings(); true }
		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_import_settings_key))?.setOnPreferenceClickListener { importSettings(); true }
	}

	private fun setupTranslationPreferences(preferences: Preferences)
	{
		val enginePreference = preferenceScreen.findPreference<ListPreference>(preferences.translationEngineKey)
		enginePreference?.summaryProvider = Preference.SummaryProvider<ListPreference> {
			val label = it.entry?.toString() ?: ""
			val choice = preferences.translationEngineChoice
			when
			{
				choice == TranslationSettings.ENGINE_GEMINI && preferences.geminiApiKey.isEmpty() ->
					getString(R.string.preferences_translation_engine_no_key, label)
				choice == TranslationSettings.ENGINE_PRO && !preferences.proSubscribed ->
					getString(R.string.preferences_translation_engine_no_pro, label, getString(
						if(preferences.translationEngine == TranslationSettings.ENGINE_GEMINI) R.string.preferences_translation_engine_fallback_gemini
						else R.string.preferences_translation_engine_fallback_mlkit))
				else -> label
			}
		}
		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_pro_key))?.let { pref ->
			pref.summaryProvider = Preference.SummaryProvider<Preference> {
				getString(if(preferences.proSubscribed) R.string.preferences_pro_summary_on else R.string.preferences_pro_summary_off)
			}
			pref.setOnPreferenceClickListener {
				startActivity(ProActivity.intent(requireContext()))
				true
			}
		}
		preferenceScreen.findPreference<EditTextPreference>(preferences.geminiApiKeyKey)?.let { pref ->
			// Anahtar ekranda asla açık gösterilmez.
			pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
				val key = preferences.geminiApiKey
				if(key.isEmpty()) getString(R.string.preferences_gemini_api_key_empty)
				else getString(R.string.preferences_gemini_api_key_set, TranslationSettings.maskApiKey(key))
			}
			pref.setOnBindEditTextListener { editText ->
				editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
				editText.isSingleLine = true
				editText.setText(preferences.geminiApiKey)
				editText.setSelection(editText.text.length)
			}
			pref.setOnPreferenceChangeListener { _, newValue ->
				val ok = TranslationSettings.normalizeApiKey(newValue as? String ?: "") != null
				if(!ok)
					Toast.makeText(requireContext(), R.string.preferences_gemini_api_key_invalid, Toast.LENGTH_LONG).show()
				else
					// Motor özeti ("anahtar girilmedi") yenilensin.
					listView?.post { enginePreference?.let { e -> e.summaryProvider = e.summaryProvider } }
				ok
			}
		}
		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_gemini_test_key))?.setOnPreferenceClickListener {
			testGemini(preferences)
			true
		}
	}

	/** Küçük bir deneme çevirisi: süre ve sonuç (veya hata) kısa mesajla gösterilir. */
	private fun testGemini(preferences: Preferences)
	{
		val context = context ?: return
		val key = preferences.geminiApiKey
		if(key.isEmpty())
		{
			Toast.makeText(context, R.string.gemini_test_no_key, Toast.LENGTH_LONG).show()
			return
		}
		val model = preferences.geminiModel
		val sample = getString(R.string.gemini_test_sample)
		Toast.makeText(context, R.string.gemini_test_running, Toast.LENGTH_SHORT).show()
		lifecycleScope.launch {
			val started = SystemClock.uptimeMillis()
			val message = try
			{
				// Test için daha uzun süre tanınır (ilk TLS bağlantısı yavaş olabilir).
				val text = withContext(Dispatchers.IO) { GeminiClient(6000, 8000).translate(key, model, sample, emptyList()) }
				getString(R.string.gemini_test_ok, (SystemClock.uptimeMillis() - started).toInt(), text)
			}
			catch(e: Exception)
			{
				getString(R.string.gemini_test_failed, e.message ?: e.javaClass.simpleName)
			}
			val ctx = this@SettingsFragment.context ?: return@launch
			Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
		}
	}

	override fun getTitle(resources: Resources): String = resources.getString(R.string.title_settings)

	override fun onResume()
	{
		super.onResume()
		// Altyazı görünümü alt ekranından dönünce önizlemeyi tazele.
		preferenceScreen?.findPreference<CaptionPreviewPreference>("subtitle_caption_preview")?.refresh()
		// Abonelik ekranından dönünce Pro ve motor özetleri yenilensin (özet sağlayıcıyı yeniden atamak yeniden çizer).
		val context = context ?: return
		val preferences = Preferences(context)
		listOf(getString(R.string.preferences_pro_key), preferences.translationEngineKey).forEach { key ->
			preferenceScreen?.findPreference<Preference>(key)?.let { p -> p.summaryProvider = p.summaryProvider }
		}
	}

	private fun exportSettings()
	{
		val activity = activity ?: return
		exportAndShareAllSettings(activity, lifecycleScope)
	}

	private fun importSettings()
	{
		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
			addCategory(Intent.CATEGORY_OPENABLE)
			type = "application/json"
		}
		startActivityForResult(intent, PICK_SETTINGS_JSON_REQUEST)
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
	{
		if(requestCode == PICK_SETTINGS_JSON_REQUEST && resultCode == Activity.RESULT_OK)
		{
			val activity = activity ?: return
			data?.data?.also {
				importSettingsFromUri(activity, it, lifecycleScope)
			}
		}
	}
}
