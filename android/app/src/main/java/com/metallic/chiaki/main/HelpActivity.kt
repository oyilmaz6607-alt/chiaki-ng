// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.databinding.DialogHomeHelpBinding
import com.metallic.chiaki.databinding.ItemHomeHelpStepBinding

/**
 * AltyazıTR: "Nasıl kullanılır?" animasyonlu anlatımı.
 * assets/help/index.html'i bir WebView'da gösterir (aynı dosya telefona /help adresinden sunulur).
 * Kumanda tuşları sayfaya window.helpNav(...) ile iletilir: ◀/▶ önceki/sonraki, OK sonraki,
 * ▲ animasyonu tekrar oynat, ▼ uzun metni kaydır, Geri kapat.
 * Cihazda WebView yoksa (bazı TV'lerde kapatılmış olabilir) eski düz metin penceresi gösterilir.
 */
class HelpActivity: AppCompatActivity()
{
	companion object
	{
		private const val TAG = "HelpActivity"

		/** Açılacak sahnenin kimliği (sayfadaki section id'si), ör. [ANCHOR_ACCOUNT_ID]. */
		const val EXTRA_ANCHOR = "help_anchor"

		const val ANCHOR_ACCOUNT_ID = "hesap-kimligi"

		private const val PAGE_URL = "file:///android_asset/help/index.html"
		/** Sayfadaki "Kapat" düğmesi bu adrese gider. */
		private const val CLOSE_URL = "altyazitr-help://close"

		fun intent(context: Context, anchor: String? = null): Intent =
			Intent(context, HelpActivity::class.java).also {
				if(anchor != null)
					it.putExtra(EXTRA_ANCHOR, anchor)
			}
	}

	private var webView: WebView? = null

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		val anchor = intent.getStringExtra(EXTRA_ANCHOR)?.takeIf { it.matches(Regex("[a-z0-9-]{1,40}")) }

		val view = try
		{
			createWebView()
		}
		catch(e: Throwable)
		{
			// WebView sağlayıcısı yok / devre dışı / güncelleniyor: AndroidRuntimeException vb.
			Log.e(TAG, "WebView unavailable, showing static help", e)
			null
		}
		if(view == null)
		{
			showFallback(anchor)
			return
		}
		webView = view
		setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		if(savedInstanceState == null || view.restoreState(savedInstanceState) == null)
			view.loadUrl(if(anchor != null) "$PAGE_URL#$anchor" else PAGE_URL)
		view.requestFocus()
	}

	@SuppressLint("SetJavaScriptEnabled")
	private fun createWebView(): WebView
	{
		val view = WebView(this)
		view.setBackgroundColor(ContextCompat.getColor(this, R.color.home_bg))
		view.isFocusable = true
		view.isFocusableInTouchMode = true
		view.overScrollMode = View.OVER_SCROLL_NEVER
		view.settings.apply {
			javaScriptEnabled = true
			domStorageEnabled = false
			allowContentAccess = false
			// file:///android_asset bu ayardan bağımsız olarak açılabilir; başka yerel dosyalara erişim kapalı.
			allowFileAccess = false
			blockNetworkLoads = true
			setGeolocationEnabled(false)
			setSupportZoom(false)
			builtInZoomControls = false
			displayZoomControls = false
			mediaPlaybackRequiresUserGesture = true
			cacheMode = WebSettings.LOAD_NO_CACHE
			textZoom = 100
		}
		// Yalnızca paketteki sayfa yüklenir (ağ kapalı); sayfa "Kapat" için bunu çağırır.
		view.addJavascriptInterface(object
		{
			@JavascriptInterface
			fun close()
			{
				runOnUiThread { finish() }
			}
		}, "AltyazitrHelp")
		view.webViewClient = object: WebViewClient()
		{
			override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
				handleUrl(request.url?.toString())
		}
		return view
	}

	/** true: WebView bu adresi açmasın. */
	private fun handleUrl(url: String?): Boolean
	{
		if(url == null)
			return true
		if(url.startsWith(CLOSE_URL))
		{
			finish()
			return true
		}
		if(url.startsWith(PAGE_URL))
			return false
		if(url.startsWith("https://") || url.startsWith("http://"))
		{
			try
			{
				startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
			}
			catch(e: ActivityNotFoundException)
			{
				Toast.makeText(this, getString(R.string.help_link_no_browser, Uri.parse(url).host ?: url), Toast.LENGTH_LONG).show()
			}
		}
		return true
	}

	override fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		val view = webView ?: return super.dispatchKeyEvent(event)
		val command = when(event.keyCode)
		{
			KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> "prev"
			KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "next"
			KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
				KeyEvent.KEYCODE_BUTTON_A -> "ok"
			KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "replay"
			KeyEvent.KEYCODE_DPAD_DOWN -> "down"
			else -> null
		} ?: return super.dispatchKeyEvent(event)
		// Bu tuşlar WebView'a hiç gitmez (odak gezintisi sayfayı kaydırmasın); yalnızca ilk basış sayılır.
		if(event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
			view.evaluateJavascript("window.helpNav&&window.helpNav('$command')", null)
		return true
	}

	override fun onResume()
	{
		super.onResume()
		webView?.onResume()
	}

	override fun onPause()
	{
		webView?.onPause()
		super.onPause()
	}

	override fun onSaveInstanceState(outState: Bundle)
	{
		super.onSaveInstanceState(outState)
		webView?.saveState(outState)
	}

	override fun onDestroy()
	{
		webView?.let {
			(it.parent as? ViewGroup)?.removeView(it)
			it.destroy()
		}
		webView = null
		super.onDestroy()
	}

	// region WebView yoksa: düz metin

	private fun showFallback(anchor: String?)
	{
		val builder = MaterialAlertDialogBuilder(this)
			.setPositiveButton(R.string.help_fallback_close, null)
			.setOnDismissListener { finish() }
		if(anchor == ANCHOR_ACCOUNT_ID)
		{
			builder.setTitle(R.string.help_fallback_account_title)
				.setMessage(R.string.help_fallback_account_body)
		}
		else
		{
			val helpBinding = DialogHomeHelpBinding.inflate(layoutInflater)
			bindStep(helpBinding.step1, 1, R.string.home_help_step1_title, R.string.home_help_step1_body)
			bindStep(helpBinding.step2, 2, R.string.home_help_step2_title, R.string.home_help_step2_body)
			bindStep(helpBinding.step3, 3, R.string.home_help_step3_title, R.string.home_help_step3_body)
			bindStep(helpBinding.step4, 4, R.string.home_help_step4_title, R.string.home_help_step4_body)
			builder.setView(helpBinding.root)
		}
		builder.show()
	}

	private fun bindStep(step: ItemHomeHelpStepBinding, number: Int, titleRes: Int, bodyRes: Int)
	{
		step.numberTextView.text = number.toString()
		step.titleTextView.setText(titleRes)
		step.bodyTextView.setText(bodyRes)
	}

	// endregion
}
