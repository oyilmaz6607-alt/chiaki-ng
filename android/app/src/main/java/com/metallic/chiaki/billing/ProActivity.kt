// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.billing

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
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
import com.metallic.chiaki.databinding.ItemPlanCardBinding
import com.metallic.chiaki.main.HomeFocus
import com.metallic.chiaki.subtitle.ProApi
import com.metallic.chiaki.subtitle.ProTranslator
import com.metallic.chiaki.subtitle.TranslationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AltyazıTR abonelik ekranı (TV, kumandayla): üç paket kartı (Standart / Pro / Premium) yan yana, "Ne kadar
 * oynuyorsun?" yardımcısı, mevcut paket ve bu dönemin kotası. Fiyat ve deneme teklifi Google Play'den
 * (ProductDetails) gelir; mevcut paket ve kota sunucudan (/v1/session) okunur, çünkü Play'in Purchase nesnesi
 * temel planı içermez. Abonelik koşulları ve gizlilik politikası bağlantısı Play politikası gereği ekranda yazılıdır.
 * İlk odak önerilen ("En çok tercih edilen") karttadır.
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
	private lateinit var cards: List<Pair<PlanSpec, ItemPlanCardBinding>>
	private lateinit var habits: List<Pair<PlayHabit, TextView>>
	private var state: BillingRepository.State = BillingRepository.State.Loading
	/** "Ne kadar oynuyorsun?" ile vurgulanan paket. */
	private var highlightedPlan: String? = null
	private var quotaJob: Job? = null
	/** Kotanın yüklendiği satın alma jetonu: paket değişince (yeni jeton) yeniden yüklenir. */
	private var quotaToken: String? = null
	private var quotaLoading = false
	private var quotaError: String? = null

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityProBinding.inflate(layoutInflater)
		setContentView(binding.root)

		billing = BillingRepository.get(this)
		preferences = Preferences(this)
		store = ProStore(this)

		cards = listOf(
			Plans.STANDART to binding.standartCard,
			Plans.PRO to binding.proCard,
			Plans.PREMIUM to binding.premiumCard
		)
		habits = listOf(
			PlayHabit.FEW_HOURS_WEEKLY to binding.habitFewButton,
			PlayHabit.MOST_EVENINGS to binding.habitEveningsButton,
			PlayHabit.LONG_DAILY to binding.habitDailyButton
		)
		for((spec, card) in cards)
		{
			HomeFocus.applyScale(card.root, 1.03f)
			card.root.setOnClickListener { onPlanClicked(spec) }
		}
		for((habit, view) in habits)
		{
			HomeFocus.applyScale(view, 1.06f)
			view.setOnClickListener { highlight(habit) }
			// Kumanda: seçenek ↓ ilgili kart, kart ↑ ilgili seçenek.
			val card = cardFor(Plans.forHabit(habit)).root
			view.nextFocusDownId = card.id
			card.nextFocusUpId = view.id
		}
		HomeFocus.applyScale(binding.useEngineButton, 1.04f)
		HomeFocus.applyScale(binding.manageButton, 1.04f)
		HomeFocus.applyScale(binding.privacyButton, 1.06f)

		binding.useEngineButton.setOnClickListener { useSubscriptionEngine() }
		binding.manageButton.setOnClickListener { openUrl(ProProduct.MANAGE_URL, isPlay = true) }
		binding.privacyButton.setOnClickListener { openUrl(ProProduct.PRIVACY_URL, isPlay = false) }

		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				launch { billing.state.collect { state = it; render() } }
				launch { billing.messages.collect { toast(it) } }
			}
		}
		render()
		cardFor(Plans.recommended).root.requestFocus()
	}

	override fun onResume()
	{
		super.onResume()
		// Satın almalar uygulama dışında değişmiş olabilir (Play'den iptal, bekleyen ödeme onaylandı).
		billing.refreshPurchases()
		render()
	}

	private fun cardFor(spec: PlanSpec): ItemPlanCardBinding = cards.first { it.first.id == spec.id }.second

	private fun highlight(habit: PlayHabit)
	{
		val spec = Plans.forHabit(habit)
		highlightedPlan = spec.id
		for((h, view) in habits)
			view.isSelected = h == habit
		render()
		cardFor(spec).root.requestFocus()
	}

	/** Mevcut paket (sunucunun bildirdiği temel plan; denemede de temel plan). Abone değilse null. */
	private fun currentPlan(): String? =
		if(state is BillingRepository.State.Subscribed) store.plan?.basePlan else null

	private fun onPlanClicked(spec: PlanSpec)
	{
		val configured = ProTranslator.isConfigured
		when(val s = state)
		{
			is BillingRepository.State.NotSubscribed -> when
			{
				s.pending -> toast(getString(R.string.pro_pending_body))
				// Sunucu adresi olmadan satış yapılmaz.
				!configured -> toast(getString(R.string.pro_not_ready))
				else -> billing.launchPurchase(this, spec.id, null)
			}
			is BillingRepository.State.Subscribed ->
			{
				val current = currentPlan()
				when
				{
					PlanChanges.of(true, current, spec.id) == PlanChange.SAME ->
						if(configured && preferences.translationEngineChoice != TranslationSettings.ENGINE_PRO)
							useSubscriptionEngine()
						else
							toast(BillingLogic.MSG_SAME_PLAN)
					!configured -> toast(getString(R.string.pro_not_ready))
					else -> billing.launchPurchase(this, spec.id, current)
				}
			}
			is BillingRepository.State.NotConfigured ->
			{
				toast(s.message)
				billing.start()
			}
			is BillingRepository.State.Error, BillingRepository.State.Loading -> billing.start()
		}
	}

	private fun useSubscriptionEngine()
	{
		preferences.sharedPreferences.edit().putString(preferences.translationEngineKey, TranslationSettings.ENGINE_PRO).apply()
		Toast.makeText(this, R.string.pro_engine_set, Toast.LENGTH_SHORT).show()
		render()
	}

	private fun render()
	{
		if(!::binding.isInitialized)
			return
		val b = binding
		val configured = ProTranslator.isConfigured
		val s = state
		val subscribed = s as? BillingRepository.State.Subscribed
		val plan = if(subscribed != null) store.plan else null
		val current = plan?.basePlan
		val offers: List<PlanOffer>? = when(s)
		{
			is BillingRepository.State.NotSubscribed -> s.plans
			is BillingRepository.State.Subscribed -> s.plans
			else -> null
		}

		when(s)
		{
			BillingRepository.State.Loading -> setStatus(getString(R.string.pro_status_loading), R.color.home_offline)
			is BillingRepository.State.NotConfigured -> setStatus(getString(R.string.pro_status_inactive), R.color.home_offline)
			is BillingRepository.State.Error -> setStatus(getString(R.string.pro_status_inactive), R.color.home_danger)
			is BillingRepository.State.NotSubscribed ->
				if(s.pending) setStatus(getString(R.string.pro_status_pending), R.color.home_standby)
				else setStatus(getString(R.string.pro_status_inactive), R.color.home_offline)
			is BillingRepository.State.Subscribed ->
				setStatus(PlanText.badge(plan?.plan)?.let { getString(R.string.pro_status_active_plan, it) }
					?: getString(R.string.pro_status_active), R.color.home_ready)
		}

		for((spec, card) in cards)
			renderCard(spec, card, s, offers?.firstOrNull { it.spec.id == spec.id }?.choice, subscribed != null, current, configured)

		showDetail(when(s)
		{
			BillingRepository.State.Loading -> ""
			is BillingRepository.State.NotConfigured -> s.message
			is BillingRepository.State.Error -> s.message
			is BillingRepository.State.NotSubscribed -> when
			{
				s.pending -> getString(R.string.pro_pending_body)
				!configured -> getString(R.string.pro_not_ready) + ". " + getString(R.string.pro_not_ready_body)
				s.productDetails == null -> BillingLogic.MSG_ITEM_UNAVAILABLE
				else -> ""
			}
			is BillingRepository.State.Subscribed ->
			{
				val renew = getString(if(s.autoRenewing) R.string.pro_renewing else R.string.pro_not_renewing)
				val trial = if(plan?.trial == true) " " + getString(R.string.pro_trial_note, PriceText.count(Plans.TRIAL_LINES)) else ""
				val engine = if(configured)
					getString(if(preferences.translationEngine == TranslationSettings.ENGINE_PRO) R.string.pro_engine_in_use else R.string.pro_engine_other)
				else
					getString(R.string.pro_not_ready) + "."
				"$renew$trial $engine"
			}
		})

		b.useEngineButton.isVisible = subscribed != null && configured &&
				preferences.translationEngineChoice != TranslationSettings.ENGINE_PRO

		if(subscribed != null && configured && quotaToken != subscribed.purchaseToken)
			loadQuota(subscribed.purchaseToken)
		renderQuota(subscribed != null)

		// Kumanda odağı kaybolduysa (ör. gizlenen düğme) önerilen karta dön.
		if(currentFocus == null)
			cardFor(Plans.recommended).root.requestFocus()
	}

	private fun renderCard(spec: PlanSpec, card: ItemPlanCardBinding, s: BillingRepository.State, choice: OfferChoice?,
		subscribed: Boolean, current: String?, configured: Boolean)
	{
		val change = PlanChanges.of(subscribed, current, spec.id)
		val price = PriceText.priceLine(choice?.price)
			?: getString(if(s == BillingRepository.State.Loading) R.string.pro_plan_price_loading else R.string.pro_plan_price_unknown)
		val trial = if(s is BillingRepository.State.NotSubscribed && !s.pending) PlanText.trial(choice) else null
		val action = when
		{
			s == BillingRepository.State.Loading -> ""
			s is BillingRepository.State.NotConfigured -> getString(R.string.pro_plan_action_unavailable)
			s is BillingRepository.State.Error -> getString(R.string.pro_plan_action_retry)
			s is BillingRepository.State.NotSubscribed && s.pending -> ""
			change == PlanChange.SAME -> PlanText.action(change)
			!configured -> getString(R.string.pro_plan_action_unavailable)
			choice == null -> getString(R.string.pro_plan_action_retry)
			else -> PlanText.action(change)
		}
		card.badgeTextView.visibility = if(spec.recommended) View.VISIBLE else View.INVISIBLE
		card.nameTextView.text = spec.name
		card.hoursTextView.text = PlanText.hours(spec)
		card.linesTextView.text = PlanText.lines(spec)
		card.priceTextView.text = price
		card.trialTextView.text = trial ?: ""
		card.trialTextView.isVisible = trial != null
		card.actionTextView.text = action
		card.actionTextView.isVisible = action.isNotEmpty()
		card.actionTextView.setTextColor(ContextCompat.getColor(this,
			if(change == PlanChange.SAME) R.color.home_ready else R.color.home_text))
		card.root.isActivated = change == PlanChange.SAME
		card.root.isSelected = highlightedPlan == spec.id
		card.root.contentDescription = getString(R.string.pro_plan_card_description,
			spec.name, PlanText.hours(spec), PlanText.lines(spec), listOfNotNull(price, trial).joinToString(", "), action)
	}

	private fun showDetail(text: String)
	{
		binding.detailTextView.text = text
		binding.detailTextView.isVisible = text.isNotBlank()
	}

	private fun setStatus(text: String, @ColorRes color: Int)
	{
		binding.statusTextView.text = text
		ImageViewCompat.setImageTintList(binding.statusDotImageView, ContextCompat.getColorStateList(this, color))
	}

	/** "Bu ay: 3.120 / 11.000 satır, yenilenme: 1 Kasım" + çubuk. */
	private fun renderQuota(subscribed: Boolean)
	{
		val b = binding
		val q = if(subscribed) store.quota else null
		val trial = store.plan?.trial == true
		val text = when
		{
			!subscribed -> null
			q != null ->
			{
				val line = PriceText.quotaLine(q.used, q.limit, q.resetAt, trial = trial)
				if(QuotaNotice.isExhausted(q.used, q.limit, q.resetAt, System.currentTimeMillis()))
					line + "\n" + getString(R.string.pro_quota_exhausted_note)
				else
					line
			}
			quotaError != null -> getString(R.string.pro_quota_failed, quotaError)
			quotaLoading -> getString(R.string.pro_quota_loading)
			else -> null
		}
		b.quotaContainer.isVisible = text != null
		b.quotaTextView.text = text ?: ""
		b.quotaProgressBar.isVisible = q != null
		if(q != null)
			b.quotaProgressBar.progress = PriceText.quotaPermille(q.used, q.limit)
	}

	/** Ekran açılınca (ve paket değişince) bir kez: yeni oturum açıp paketi ve kotayı alır. */
	private fun loadQuota(token: String)
	{
		quotaJob?.cancel()
		quotaToken = token
		quotaLoading = true
		quotaError = null
		quotaJob = lifecycleScope.launch {
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
			// Paket artık biliniyor: kartlardaki "Mevcut paketin / Yükselt / Düşür" de güncellenir.
			render()
		}
	}

	private fun toast(message: String)
	{
		Toast.makeText(this, message, Toast.LENGTH_LONG).show()
	}

	private fun openUrl(url: String, isPlay: Boolean)
	{
		try
		{
			startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
		}
		catch(e: ActivityNotFoundException)
		{
			toast(if(isPlay) getString(R.string.pro_no_play) else getString(R.string.pro_no_browser, url))
		}
		catch(e: SecurityException)
		{
			toast(getString(R.string.pro_no_browser, url))
		}
	}
}
