// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.content.Context
import android.content.SharedPreferences

/**
 * AltyazıTR: ana ekranın küçük hafızası (son seçilen konsol, ağda en son görülen konsol IP'si).
 * Ayarlar ekranındaki Preferences'tan ayrı bir dosyada tutulur.
 */
class HomePrefs(context: Context)
{
	companion object
	{
		private const val FILE = "altyazitr_home"
		private const val KEY_LAST_CONSOLE_KEY = "last_console_key"
		private const val KEY_LAST_CONSOLE_HOST = "last_console_host"
		private const val KEY_LAST_DISCOVERED_HOST = "last_discovered_host"
	}

	private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

	var lastConsoleKey: String?
		get() = prefs.getString(KEY_LAST_CONSOLE_KEY, null)
		set(value) { prefs.edit().putString(KEY_LAST_CONSOLE_KEY, value).apply() }

	var lastConsoleHost: String?
		get() = prefs.getString(KEY_LAST_CONSOLE_HOST, null)
		set(value) { prefs.edit().putString(KEY_LAST_CONSOLE_HOST, value).apply() }

	var lastDiscoveredHost: String?
		get() = prefs.getString(KEY_LAST_DISCOVERED_HOST, null)
		set(value) { prefs.edit().putString(KEY_LAST_DISCOVERED_HOST, value).apply() }

	fun rememberConsole(console: HomeConsole)
	{
		prefs.edit()
			.putString(KEY_LAST_CONSOLE_KEY, console.key)
			.putString(KEY_LAST_CONSOLE_HOST, console.host)
			.apply()
	}
}
