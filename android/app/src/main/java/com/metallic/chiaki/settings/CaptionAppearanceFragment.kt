// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.subtitle.CaptionPreview
import com.metallic.chiaki.subtitle.CaptionStyle

/**
 * "Altyazı görünümü" alt ekranı. Önizleme listenin üstüne sabitlenir; böylece kumandayla
 * aşağıdaki ayarlara inildiğinde de değişiklikler anında görünür.
 * Bu ekran SettingsFragment'taki özel DataStore'u değil, doğrudan varsayılan SharedPreferences'ı kullanır.
 */
class CaptionAppearanceFragment: PreferenceFragmentCompat(), TitleFragment
{
	private var preferences: Preferences? = null
	private var previewView: View? = null

	// SharedPreferences dinleyicileri zayıf referansla tutar; alan olarak saklanmalı.
	private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		val prefs = preferences ?: return@OnSharedPreferenceChangeListener
		if(key == null || key in prefs.subtitleCaptionStyleKeys)
			refreshPreview()
	}

	override fun getTitle(resources: Resources): String = resources.getString(R.string.preferences_caption_appearance_title)

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
	{
		val context = context ?: return
		preferences = Preferences(context)
		setPreferencesFromResource(R.xml.preferences_caption_appearance, rootKey)
		setupPreferences()
	}

	private fun setupPreferences()
	{
		val prefs = preferences ?: return

		// Konum: eski "subtitle_cover_original" anahtarından taşınır (yeni anahtar yoksa).
		findPreference<ListPreference>(prefs.subtitleCaptionPositionKey)?.let {
			if(it.value == null)
				it.value = prefs.subtitleCaptionPosition
		}

		findPreference<Preference>(getString(R.string.preferences_caption_reset_key))?.setOnPreferenceClickListener {
			resetToDefaults()
			true
		}
	}

	private fun resetToDefaults()
	{
		val context = context ?: return
		val prefs = preferences ?: return
		val editor = prefs.sharedPreferences.edit()
		prefs.subtitleCaptionStyleKeys.forEach { editor.remove(it) }
		editor.putString(prefs.subtitleCaptionPositionKey, CaptionStyle.DEFAULT_POSITION)
		editor.apply()

		// Ekranı yeniden oluştur: varsayılan değerler XML'deki defaultValue'lardan yeniden yazılır.
		setPreferencesFromResource(R.xml.preferences_caption_appearance, null)
		setupPreferences()
		refreshPreview()
		Toast.makeText(context, R.string.preferences_caption_reset_done, Toast.LENGTH_SHORT).show()
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View
	{
		val list = super.onCreateView(inflater, container, savedInstanceState)
		val root = LinearLayout(inflater.context).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
		}
		val preview = inflater.inflate(R.layout.preference_caption_preview, root, false)
		root.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
		root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
		previewView = preview
		return root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?)
	{
		super.onViewCreated(view, savedInstanceState)
		refreshPreview()
	}

	override fun onDestroyView()
	{
		previewView = null
		super.onDestroyView()
	}

	override fun onResume()
	{
		super.onResume()
		preferences?.sharedPreferences?.registerOnSharedPreferenceChangeListener(changeListener)
		refreshPreview()
	}

	override fun onPause()
	{
		preferences?.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(changeListener)
		super.onPause()
	}

	private fun refreshPreview()
	{
		val view = previewView ?: return
		CaptionPreview.bind(view)
	}
}
