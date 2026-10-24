// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.crash

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Uygulama çökerse kapanıp gitmek yerine hatayı ekranda gösterir
 * (TV'de günlük okumak zor; ekran fotoğrafı yeterli olsun diye).
 */
class AltyazitrApplication : Application()
{
	override fun onCreate()
	{
		CrashReporter.install(this)
		super.onCreate()
	}
}

object CrashReporter
{
	const val EXTRA_TRACE = "crash_trace"

	fun install(app: Application)
	{
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, error ->
			try
			{
				val sw = StringWriter()
				error.printStackTrace(PrintWriter(sw))
				val info = "Cihaz: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
						"ABI: ${Build.SUPPORTED_ABIS.joinToString()}\nThread: ${thread.name}\n\n"
				val intent = Intent(app, CrashActivity::class.java)
					.putExtra(EXTRA_TRACE, info + sw.toString().take(20000))
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
				app.startActivity(intent)
				Process.killProcess(Process.myPid())
				exitProcess(10)
			}
			catch(_: Throwable)
			{
				previous?.uncaughtException(thread, error)
			}
		}
	}
}

/** Ayrı süreçte çalışır; uygulamanın geri kalanına bağımlı değildir. */
class CrashActivity : Activity()
{
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		val trace = intent.getStringExtra(CrashReporter.EXTRA_TRACE) ?: "Bilinmeyen hata"
		val text = TextView(this).apply {
			text = "Uygulama bir hatayla karşılaştı. Bu ekranın fotoğrafını çekip gönderin.\n\n$trace"
			setTextColor(Color.WHITE)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
			setPadding(48, 32, 48, 32)
			setTextIsSelectable(false)
		}
		val scroll = ScrollView(this).apply {
			setBackgroundColor(Color.rgb(20, 20, 20))
			isFocusable = true
			isFocusableInTouchMode = true
			addView(text)
		}
		setContentView(scroll)
		scroll.requestFocus()
	}
}
