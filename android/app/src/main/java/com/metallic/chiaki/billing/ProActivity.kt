// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.billing

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.databinding.ActivityProBinding
import com.metallic.chiaki.main.HomeFocus
import com.metallic.chiaki.subtitle.ProApi
import com.metallic.chiaki.subtitle.ProQuota
import com.metallic.chiaki.subtitle.ProTranslator
import com.metallic.chiaki.subtitle.TranslationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AltyazıTR Pro abonelik ekranı (TV, kumandayla). Fiyat ve deneme süresi Google Play'den (ProductDetails)
 * gelir; abone olunca kalan aylık kota sunucudan (/v1/session) okunur. Abonelik koşulları ve gizlilik
 * politikası bağlantısı Play politikası gereği ekranda açıkça yazılıdır.
 */
class ProActivity: AppCompatActivity()
{
	companion object
	{
		fun intent(context: Context) = Intent(context, ProActivity::class.java)
	}

	private lateinit var binding: ActivityProBinding
	private lateinit var billing: BillingRepository
	private lateinit var preferences: Preferences
	private lateinit var store: ProStore
	private var state: BillingRepository.State = BillingRepository.State.Loading
	private var quotaJob: Job? = null
	private var quotaLoading = false
	private var lastPrimaryUsable = false
	private var quotaError: String? = null

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityProBinding.inflate(layoutInflater)
		setContentView(binding.root)

		billing = BillingRepository.get(this)
		preferences = Preferences(this)
		store = ProStore(this)

		HomeFocus.applyScale(binding.primaryButton, 1.04f)
		HomeFocus.applyScale(binding.manageButton, 1.04f)
		HomeFocus.applyScale(binding.privacyButton, 1.06f)

		binding.primaryButton.setOnClickListener { primaryAction() }
		binding.manageButton.setOnClickListener { openUrl(ProProduct.MANAGE_URL, isPlay = true) }
		binding.privacyButton.setOnClickListener { openUrl(ProProduct.PRIVACY_URL, isPlay = false) }

		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				launch { billing.state.collect { state = it; render() } }
				launch { billing.messages.collect { Toast.makeText(this@ProActivity, it, Toast.LENGTH_LONG).show() } }
			}
		}
		render()
		binding.primaryButton.requestFocus()
	}

	override fun onResume()
	{
		super.onResume()
		// Satın almalar uygulama dışında değişmiş olabilir (Play'den iptal, bekleyen ödeme onaylandı).
		billing.refreshPurchases()
		render()
	}

	private fun primaryAction()
	{
		when(val s = state)
		{
			is BillingRepository.State.Subscribed ->
			{
				preferences.sharedPreferences.edit().putString(preferences.translationEngineKey, TranslationSettings.ENGINE_PRO).apply()
				Toast.makeText(this, R.string.pro_engine_set, Toast.LENGTH_SHORT).show()
				render()
			}
			is BillingRepository.State.NotSubscribed ->
				if(!s.pending)
					billing.launchPurchase(this)
			is BillingRepository.State.Error, BillingRepository.State.Loading -> billing.start()
			is BillingRepository.State.NotConfigured -> billing.start()
		}
	}

	private fun render()
	{
		if(!::binding.isInitialized)
			return
		val b = binding
		val configured = ProTranslator.isConfigured
		b.trialTextView.isVisible = false
		b.detailTextView.isVisible = false
		b.quotaTextView.isVisible = false
		b.primaryButton.isEnabled = true
		when(val s = state)
		{
			BillingRepository.State.Loading ->
			{
				setStatus(R.string.pro_status_loading, R.color.home_offline)
				b.priceTextView.setText(R.string.pro_price_loading)
				b.primaryButton.isVisible = true
				b.primaryButton.isEnabled = false
				b.primaryButton.setText(R.string.pro_action_subscribe)
			}
			is BillingRepository.State.NotConfigured ->
			{
				setStatus(R.string.pro_status_inactive, R.color.home_offline)
				b.priceTextView.setText(R.string.pro_price_unknown)
				showDetail(s.message)
				b.primaryButton.isVisible = false
			}
			is BillingRepository.State.Error ->
			{
				setStatus(R.string.pro_status_inactive, R.color.home_danger)
				b.priceTextView.setText(R.string.pro_price_unknown)
				showDetail(s.message)
				b.primaryButton.isVisible = true
				b.primaryButton.setText(R.string.pro_action_retry)
			}
			is BillingRepository.State.NotSubscribed ->
			{
				val offer = s.offer
				setStatus(if(s.pending) R.string.pro_status_pending else R.string.pro_status_inactive,
					if(s.pending) R.color.home_standby else R.color.home_offline)
				val price = PriceText.priceLine(offer?.price)
				if(price != null) b.priceTextView.text = price else b.priceTextView.setText(R.string.pro_price_unknown)
				offer?.let { PriceText.trialLine(it) }?.let {
					b.trialTextView.text = it
					b.trialTextView.isVisible = true
				}
				when
				{
					s.pending ->
					{
						showDetail(getString(R.string.pro_pending_body))
						b.primaryButton.isVisible = false
					}
					!configured ->
					{
						// Sunucu adresi olmadan satış yapılmaz.
						showDetail(getString(R.string.pro_not_ready) + ". " + getString(R.string.pro_not_ready_body))
						b.primaryButton.isVisible = false
					}
					s.productDetails == null || offer == null ->
					{
						showDetail(BillingLogic.MSG_ITEM_UNAVAILABLE)
						b.primaryButton.isVisible = true
						b.primaryButton.setText(R.string.pro_action_retry)
					}
					else ->
					{
						b.primaryButton.isVisible = true
						b.primaryButton.text = PriceText.buttonLabel(offer)
					}
				}
			}
			is BillingRepository.State.Subscribed ->
			{
				setStatus(R.string.pro_status_active, R.color.home_ready)
				b.priceTextView.setText(R.string.pro_status_active)
				val engineNote = getString(if(preferences.translationEngine == TranslationSettings.ENGINE_PRO) R.string.pro_engine_in_use else R.string.pro_engine_other)
				val renew = getString(if(s.autoRenewing) R.string.pro_renewing else R.string.pro_not_renewing)
				showDetail(if(configured) "$renew $engineNote" else "$renew ${getString(R.string.pro_not_ready)}.")
				val usePro = configured && preferences.translationEngineChoice != TranslationSettings.ENGINE_PRO
				b.primaryButton.isVisible = usePro
				b.primaryButton.setText(R.string.pro_action_use_pro)
				renderQuota()
				if(configured && quotaJob == null)
					loadQuota()
			}
		}
		// Kumanda odağı: kullanılamayan düğmede kalmasın; düğme kullanılabilir olunca ona geçsin.
		val primaryUsable = b.primaryButton.isVisible && b.primaryButton.isEnabled
		val focus = currentFocus
		if(!primaryUsable && (focus == null || focus === b.primaryButton))
			b.manageButton.requestFocus()
		else if(primaryUsable && !lastPrimaryUsable && (focus == null || focus === b.manageButton))
			b.primaryButton.requestFocus()
		lastPrimaryUsable = primaryUsable
	}

	private fun showDetail(text: String)
	{
		binding.detailTextView.text = text
		binding.detailTextView.isVisible = text.isNotBlank()
	}

	private fun setStatus(text: Int, @ColorRes color: Int)
	{
		binding.statusTextView.setText(text)
		ImageViewCompat.setImageTintList(binding.statusDotImageView, ContextCompat.getColorStateList(this, color))
	}

	private fun renderQuota()
	{
		val q: ProQuota? = store.quota
		val text = when
		{
			q != null -> PriceText.quotaLine(q.used, q.limit, q.resetAt)
			quotaError != null -> getString(R.string.pro_quota_failed, quotaError)
			quotaLoading -> getString(R.string.pro_quota_loading)
			else -> null
		}
		binding.quotaTextView.text = text ?: ""
		binding.quotaTextView.isVisible = text != null
	}

	/** Ekran açılınca bir kez: oturumu (gerekirse) yenileyip kotayı alır. */
	private fun loadQuota()
	{
		quotaLoading = true
		quotaJob = lifecycleScope.launch {
			quotaError = null
			try
			{
				withContext(Dispatchers.IO) { ProTranslator.get(this@ProActivity).refreshSessionBlocking() }
			}
			catch(e: ProTranslator.ProException)
			{
				quotaError = e.message
				if(e.error.code == ProApi.CODE_NOT_SUBSCRIBED || e.error.code == ProApi.CODE_INVALID_PURCHASE)
					billing.refreshPurchases()
			}
			catch(e: CancellationException)
			{
				throw e
			}
			catch(e: Exception)
			{
				quotaError = e.message ?: e.javaClass.simpleName
			}
			quotaLoading = false
			renderQuota()
		}
	}

	private fun openUrl(url: String, isPlay: Boolean)
	{
		try
		{
			startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
		}
		catch(e: ActivityNotFoundException)
		{
			val message = if(isPlay) getString(R.string.pro_no_play) else getString(R.string.pro_no_browser, url)
			Toast.makeText(this, message, Toast.LENGTH_LONG).show()
		}
		catch(e: SecurityException)
		{
			Toast.makeText(this, getString(R.string.pro_no_browser, url), Toast.LENGTH_LONG).show()
		}
	}
}
