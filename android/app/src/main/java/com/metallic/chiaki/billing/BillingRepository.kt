// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.billing

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.metallic.chiaki.subtitle.ProApi
import com.metallic.chiaki.subtitle.ProTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AltyazıTR aboneliği için Google Play Faturalandırma (Billing Library 8, Kotlin uzantısız Java API).
 * Tek ürün ([ProProduct.PRODUCT_ID]) ve paket başına bir temel plan ([Plans]); her paket için uygunsa
 * 3 günlük deneme teklifi, değilse temel plan teklifi seçilir. Abone iken başka pakete geçiş
 * SubscriptionUpdateParams ile yapılır (yükseltme: WITH_TIME_PRORATION, düşürme: DEFERRED).
 *
 * Uygulama ömrü boyunca tek örnek ([get]); tüm durum değişiklikleri ana iş parçacığında yapılır.
 * Bağlantı koparsa kütüphane kendisi yeniden bağlanır (enableAutoServiceReconnection); ilk kurulum
 * geçici bir hatayla biterse artan aralıklarla yeniden denenir. Play'siz cihazlarda (yan yüklenmiş test
 * sürümü vb.) çökme yerine [State.NotConfigured] ile Türkçe açıklama verilir.
 * Doğrulanmış abonelik [ProStore]'a yazılır; altyazı servisi oradan okur.
 *
 * Her paketin aylık ve yıllık ("<paket>-yillik") temel planı sorgulanır. Tek seferlik ek paket ([TopUp.PRODUCT_ID],
 * INAPP, tüketilebilir) sunucu (/v1/topup) kredilendirdikten sonra tüketilir; kredilendirilemezse tüketilmez ve
 * bir sonraki yenilemede yeniden denenir (para kaybı olmaz).
 */
class BillingRepository private constructor(context: Context): PurchasesUpdatedListener
{
	companion object
	{
		private const val TAG = "BillingRepository"

		@Volatile private var instance: BillingRepository? = null

		fun get(context: Context): BillingRepository =
			instance ?: synchronized(this) {
				instance ?: BillingRepository(context.applicationContext).also { instance = it }
			}
	}

	sealed class State
	{
		object Loading: State()
		/** Bu cihazda Google Play faturalandırması yok. */
		data class NotConfigured(val message: String): State()
		data class NotSubscribed(
			/** Satın alma için gerekli; ürün henüz/hiç bulunamadıysa null. */
			val productDetails: ProductDetails?,
			/** Paketler sırayla (Standart, Pro, Premium) ve her birinin seçilen teklifi (aylık). */
			val plans: List<PlanOffer>,
			/** Ödeme onay bekliyor (abonelik henüz başlamaz). */
			val pending: Boolean = false,
			/** Aynı paketlerin yıllık temel planları. */
			val yearlyPlans: List<PlanOffer> = emptyList()
		): State()
		data class Subscribed(
			val purchaseToken: String,
			val autoRenewing: Boolean,
			/** Paket değiştirmek için (fiyatlar, teklifler). */
			val productDetails: ProductDetails?,
			val plans: List<PlanOffer>,
			val yearlyPlans: List<PlanOffer> = emptyList()
		): State()
		data class Error(val message: String): State()
	}

	private val appContext = context.applicationContext
	private val store = ProStore(appContext)
	private val main = Handler(Looper.getMainLooper())

	private val _state = MutableStateFlow<State>(State.Loading)
	val state: StateFlow<State> = _state.asStateFlow()

	/** Kısa bilgi mesajları (iptal, bekleyen ödeme, hata): ekranda Toast olarak gösterilir. */
	private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
	val messages: SharedFlow<String> = _messages.asSharedFlow()

	private var client: BillingClient? = null
	private var connecting = false
	private var retryAttempt = 0
	private var productDetails: ProductDetails? = null
	private var plans: List<PlanOffer> = OfferSelector.selectPlans(emptyList())
	private var yearlyPlans: List<PlanOffer> = OfferSelector.selectPlans(emptyList(), period = BillingPeriod.YEARLY)
	private var status: ProStatus? = null
	/** Tek seferlik ek paket (INAPP); Play'de yoksa null. */
	private var topupDetails: ProductDetails? = null
	private val _topup = MutableStateFlow<TopUpOffer?>(null)
	/** Ek paketin Play fiyatı (abonelik ekranında %80'den sonra gösterilir); ürün yoksa null. */
	val topup: StateFlow<TopUpOffer?> = _topup.asStateFlow()
	/** Sunucuya kredilendirilmekte olan ek paket jetonları (aynı jeton iki kez gönderilmesin). */
	private val crediting = HashSet<String>()
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	/** Başlatılan paket değişikliğinin türü: sonuç gelince kısa bilgi verilir. */
	private var pendingChange: PlanChange? = null
	private val retryRunnable = Runnable { start() }

	init
	{
		// %80 uyarısı ana ekran açılınca (oyun dışında) gösterilir.
		(appContext as? Application)?.let { QuotaWarningNotifier.install(it) }
	}

	/** Bağlanır (gerekirse) ve ürün + satın alma bilgisini yeniler. Her yerden çağrılabilir. */
	fun start() = onMain {
		val c = client ?: createClient() ?: return@onMain
		when
		{
			c.isReady -> refresh()
			connecting -> {}
			else -> connect(c)
		}
	}

	/** Satın almaları yeniden sorgular (ör. Activity.onResume, sunucu "abonelik yok" dediğinde). */
	fun refreshPurchases() = start()

	private fun createClient(): BillingClient?
	{
		return try
		{
			BillingClient.newBuilder(appContext)
				.setListener(this)
				.enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
				.enableAutoServiceReconnection()
				.build()
				.also { client = it }
		}
		catch(e: Exception)
		{
			Log.e(TAG, "BillingClient oluşturulamadı", e)
			publishStatus(ProStatus.Unavailable(BillingLogic.MSG_UNAVAILABLE))
			null
		}
	}

	private fun connect(c: BillingClient)
	{
		connecting = true
		if(status == null)
			_state.value = State.Loading
		try
		{
			c.startConnection(object: BillingClientStateListener
			{
				override fun onBillingSetupFinished(result: BillingResult)
				{
					onMain { onSetupFinished(result) }
				}

				override fun onBillingServiceDisconnected()
				{
					// Otomatik yeniden bağlanma açık: kütüphane bir sonraki istekte kendisi bağlanır.
					onMain { connecting = false }
				}
			})
		}
		catch(e: Exception)
		{
			connecting = false
			Log.e(TAG, "startConnection başarısız", e)
			publishStatus(ProStatus.Unavailable(BillingLogic.MSG_UNAVAILABLE))
		}
	}

	private fun onSetupFinished(result: BillingResult)
	{
		connecting = false
		val code = codeOf(result)
		if(code != BillingCode.OK)
		{
			Log.w(TAG, "Billing kurulumu: ${result.responseCode} ${result.debugMessage}")
			val message = BillingLogic.message(code) ?: BillingLogic.MSG_ERROR
			if(BillingLogic.isUnavailable(code))
				publishStatus(ProStatus.Unavailable(message))
			else
			{
				publishStatus(ProStatus.Failed(message))
				scheduleRetry()
			}
			return
		}
		retryAttempt = 0
		val c = client ?: return
		val subs = codeOf(c.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS))
		if(subs == BillingCode.FEATURE_NOT_SUPPORTED)
		{
			publishStatus(ProStatus.Unavailable(BillingLogic.MSG_SUBS_UNSUPPORTED))
			return
		}
		refresh()
	}

	private fun scheduleRetry()
	{
		main.removeCallbacks(retryRunnable)
		main.postDelayed(retryRunnable, BillingLogic.retryDelayMs(retryAttempt++))
	}

	private fun refresh()
	{
		queryProduct()
		queryPurchases()
	}

	private fun queryProduct()
	{
		val c = client ?: return
		val params = QueryProductDetailsParams.newBuilder()
			.setProductList(listOf(
				QueryProductDetailsParams.Product.newBuilder()
					.setProductId(ProProduct.PRODUCT_ID)
					.setProductType(BillingClient.ProductType.SUBS)
					.build()
			))
			.build()
		c.queryProductDetailsAsync(params) { result, details ->
			val list = details.productDetailsList.orEmpty()
			onMain {
				if(codeOf(result) != BillingCode.OK)
				{
					Log.w(TAG, "Ürün sorgusu: ${result.responseCode} ${result.debugMessage}")
					return@onMain
				}
				val product = list.firstOrNull { it.productId == ProProduct.PRODUCT_ID }
				if(product == null)
					Log.w(TAG, "Ürün bulunamadı: ${ProProduct.PRODUCT_ID} (Play Console'da etkin mi?)")
				productDetails = product
				val offers = product?.let { offersOf(it) }.orEmpty()
				plans = OfferSelector.selectPlans(offers)
				yearlyPlans = OfferSelector.selectPlans(offers, period = BillingPeriod.YEARLY)
				(plans + yearlyPlans).filter { it.choice == null }.forEach { Log.w(TAG, "Temel plan bulunamadı: ${it.basePlanId} (Play Console'da etkin mi?)") }
				publish()
			}
		}
		val inapp = QueryProductDetailsParams.newBuilder()
			.setProductList(listOf(
				QueryProductDetailsParams.Product.newBuilder()
					.setProductId(TopUp.PRODUCT_ID)
					.setProductType(BillingClient.ProductType.INAPP)
					.build()
			))
			.build()
		c.queryProductDetailsAsync(inapp) { result, details ->
			val list = details.productDetailsList.orEmpty()
			onMain {
				if(codeOf(result) != BillingCode.OK)
				{
					Log.w(TAG, "Ek paket sorgusu: ${result.responseCode} ${result.debugMessage}")
					return@onMain
				}
				val product = list.firstOrNull { it.productId == TopUp.PRODUCT_ID }
				topupDetails = product
				_topup.value = product?.oneTimePurchaseOfferDetails?.formattedPrice?.takeIf { it.isNotBlank() }?.let { TopUpOffer(it) }
			}
		}
	}

	private fun queryPurchases()
	{
		val c = client ?: return
		val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
		c.queryPurchasesAsync(params) { result, purchases ->
			val list = purchases.orEmpty().toList()
			onMain { handlePurchases(codeOf(result), list) }
		}
		// Kredilendirilmemiş/tüketilmemiş ek paketler (ör. uygulama satın alma sırasında kapandı, bekleyen ödeme onaylandı).
		val inapp = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
		c.queryPurchasesAsync(inapp) { result, purchases ->
			val list = purchases.orEmpty().toList()
			onMain {
				if(codeOf(result) == BillingCode.OK)
					creditTopups(list.map { infoOf(it) }, announcePending = false)
			}
		}
	}

	/**
	 * Ek paketleri sunucuya kredilendirir, başarılı olunca tüketir. Abonelik yoksa veya sunucuya ulaşılamazsa
	 * tüketilmez (bir sonraki yenilemede yeniden denenir).
	 */
	private fun creditTopups(purchases: List<PurchaseInfo>, announcePending: Boolean)
	{
		if(announcePending && TopUp.hasPending(purchases))
			_messages.tryEmit(TopUp.MSG_PENDING)
		for(token in TopUp.tokensToCredit(purchases))
		{
			if(!crediting.add(token))
				continue
			scope.launch {
				try
				{
					val r = withContext(Dispatchers.IO) { ProTranslator.get(appContext).creditTopupBlocking(token) }
					consume(token)
					if(r.credited > 0)
						_messages.tryEmit(TopUp.MSG_CREDITED)
				}
				catch(e: ProTranslator.ProException)
				{
					Log.w(TAG, "Ek paket kredilendirilemedi: ${e.error.code}")
					_messages.tryEmit(when(e.error.code)
					{
						ProApi.CODE_NO_PURCHASE, ProApi.CODE_NOT_SUBSCRIBED -> TopUp.MSG_NEEDS_SUBSCRIPTION
						ProApi.CODE_PURCHASE_PENDING -> TopUp.MSG_PENDING
						else -> TopUp.MSG_RETRY
					})
				}
				catch(e: Exception)
				{
					Log.w(TAG, "Ek paket kredilendirilemedi", e)
					_messages.tryEmit(TopUp.MSG_RETRY)
				}
				finally
				{
					crediting.remove(token)
				}
			}
		}
	}

	private fun consume(token: String)
	{
		val c = client ?: return
		val params = ConsumeParams.newBuilder().setPurchaseToken(token).build()
		c.consumeAsync(params) { result, _ ->
			// Başarısızsa bir sonraki sorguda yeniden denenir; sunucu aynı jetonu ikinci kez saymaz.
			if(codeOf(result) != BillingCode.OK)
				Log.w(TAG, "Tüketme başarısız: ${result.responseCode} ${result.debugMessage}")
		}
	}

	/**
	 * Tek seferlik ek paketin (+2.000 satır, ay sonuna kadar) satın alma ekranını açar. Yalnızca abone iken
	 * kullanıcı istediğinde (abonelik ekranındaki düğme) çağrılır. false = başlatılamadı.
	 */
	fun launchTopup(activity: Activity): Boolean
	{
		val c = client
		val product = topupDetails
		if(status !is ProStatus.Subscribed)
		{
			_messages.tryEmit(TopUp.MSG_NEEDS_SUBSCRIPTION)
			return false
		}
		if(c == null || !c.isReady || product == null)
		{
			_messages.tryEmit(if(c != null && c.isReady) BillingLogic.MSG_ITEM_UNAVAILABLE else BillingLogic.MSG_NETWORK)
			start()
			return false
		}
		val flowParams = BillingFlowParams.newBuilder()
			.setProductDetailsParamsList(listOf(
				BillingFlowParams.ProductDetailsParams.newBuilder()
					.setProductDetails(product)
					.build()
			))
			.build()
		val result = try
		{
			c.launchBillingFlow(activity, flowParams)
		}
		catch(e: Exception)
		{
			Log.e(TAG, "launchBillingFlow (ek paket)", e)
			_messages.tryEmit(BillingLogic.MSG_ERROR)
			return false
		}
		val code = codeOf(result)
		if(code == BillingCode.OK)
			return true
		Log.w(TAG, "launchBillingFlow (ek paket): ${result.responseCode} ${result.debugMessage}")
		if(code == BillingCode.ITEM_ALREADY_OWNED)
			queryPurchases() // tüketilmemiş eski paket: önce o kredilendirilir
		else
			BillingLogic.message(code)?.let { _messages.tryEmit(it) }
		return false
	}

	private fun handlePurchases(code: BillingCode, purchases: List<Purchase>)
	{
		val infos = purchases.map { infoOf(it) }
		acknowledge(BillingLogic.tokensToAcknowledge(infos))
		val newStatus = BillingLogic.status(code, infos)
		if(newStatus is ProStatus.Failed)
			scheduleRetry()
		else
			retryAttempt = 0
		publishStatus(newStatus)
	}

	private fun acknowledge(tokens: List<String>)
	{
		val c = client ?: return
		for(token in tokens)
		{
			val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
			c.acknowledgePurchase(params) { result ->
				// Onaylanmayan abonelik 3 gün içinde iade edilir; başarısızsa bir sonraki sorguda yeniden denenir.
				if(codeOf(result) != BillingCode.OK)
					Log.w(TAG, "Onaylama başarısız: ${result.responseCode} ${result.debugMessage}")
			}
		}
	}

	/**
	 * [planId] paketi için Google Play satın alma ekranını açar. Abone iken başka pakete geçişi başlatır
	 * (eski satın alma jetonu + değiştirme modu). false = başlatılamadı (sebebi [messages] ile bildirilir).
	 * [currentPlan] sunucunun bildirdiği temel plan (Purchase nesnesinde temel plan yoktur); bilinmiyorsa null.
	 */
	fun launchPurchase(activity: Activity, planId: String, currentPlan: String?): Boolean
	{
		val c = client
		val product = productDetails
		val choice = (plans + yearlyPlans).firstOrNull { it.basePlanId == planId }?.choice
		val subscribed = status as? ProStatus.Subscribed
		val change = PlanChanges.of(subscribed != null, currentPlan, planId)
		if(change == PlanChange.SAME)
		{
			_messages.tryEmit(BillingLogic.MSG_SAME_PLAN)
			return false
		}
		if(c == null || !c.isReady || product == null || choice == null)
		{
			val st = _state.value
			_messages.tryEmit(when
			{
				st is State.NotConfigured -> st.message
				c != null && c.isReady && product == null -> BillingLogic.MSG_ITEM_UNAVAILABLE
				else -> BillingLogic.MSG_NETWORK
			})
			start()
			return false
		}
		val builder = BillingFlowParams.newBuilder()
			.setProductDetailsParamsList(listOf(
				BillingFlowParams.ProductDetailsParams.newBuilder()
					.setProductDetails(product)
					.setOfferToken(choice.offer.offerToken)
					.build()
			))
		if(subscribed != null)
		{
			// Aynı üründe temel plan değişikliği: eski jeton + değiştirme modu.
			// (8.0.0'da setSubscriptionReplacementMode; sonraki sürümlerde ProductDetailsParams.setSubscriptionProductReplacementParams'a taşındı.)
			val mode = when(change)
			{
				PlanChange.DOWNGRADE -> BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.DEFERRED
				else -> BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION
			}
			builder.setSubscriptionUpdateParams(
				BillingFlowParams.SubscriptionUpdateParams.newBuilder()
					.setOldPurchaseToken(subscribed.purchaseToken)
					.setSubscriptionReplacementMode(mode)
					.build()
			)
		}
		val flowParams = builder.build()
		val result = try
		{
			c.launchBillingFlow(activity, flowParams)
		}
		catch(e: Exception)
		{
			Log.e(TAG, "launchBillingFlow", e)
			_messages.tryEmit(BillingLogic.MSG_ERROR)
			return false
		}
		val code = codeOf(result)
		if(code == BillingCode.OK)
		{
			pendingChange = change
			return true
		}
		Log.w(TAG, "launchBillingFlow: ${result.responseCode} ${result.debugMessage}")
		handleUpdate(BillingLogic.onPurchasesUpdated(code, null), emptyList())
		return false
	}

	override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?)
	{
		val list = purchases?.toList().orEmpty()
		val code = codeOf(result)
		onMain {
			val infos = list.map { infoOf(it) }
			val topups = infos.filter { TopUp.PRODUCT_ID in it.products }
			if(code == BillingCode.OK && topups.isNotEmpty())
			{
				creditTopups(topups, announcePending = true)
				// Yalnızca ek paket geldiyse abonelik durumu değişmedi.
				if(topups.size == infos.size)
					return@onMain
			}
			handleUpdate(BillingLogic.onPurchasesUpdated(code, infos), list)
		}
	}

	private fun handleUpdate(update: PurchaseUpdate, purchases: List<Purchase>)
	{
		when(update)
		{
			is PurchaseUpdate.Status ->
			{
				handlePurchases(BillingCode.OK, purchases)
				if(update.status == ProStatus.Pending)
					_messages.tryEmit(BillingLogic.MSG_PENDING)
				else if(update.status is ProStatus.Subscribed)
					when(pendingChange)
					{
						PlanChange.UPGRADE, PlanChange.UNKNOWN -> _messages.tryEmit(BillingLogic.MSG_UPGRADED)
						PlanChange.DOWNGRADE -> _messages.tryEmit(BillingLogic.MSG_DOWNGRADE_DEFERRED)
						else -> {}
					}
				pendingChange = null
			}
			is PurchaseUpdate.Canceled ->
			{
				pendingChange = null
				_messages.tryEmit(update.message)
			}
			PurchaseUpdate.Requery ->
			{
				queryPurchases()
			}
			is PurchaseUpdate.Failed ->
			{
				pendingChange = null
				_messages.tryEmit(update.message)
			}
		}
	}

	private fun publishStatus(newStatus: ProStatus)
	{
		when(newStatus)
		{
			is ProStatus.Subscribed -> store.saveSubscription(newStatus.purchaseToken, newStatus.autoRenewing)
			// Yalnızca Play "abonelik yok" dediğinde silinir; geçici hatalarda son bilinen durum korunur.
			ProStatus.NotSubscribed, ProStatus.Pending -> store.clearSubscription()
			is ProStatus.Unavailable, is ProStatus.Failed -> {}
		}
		// Geçici hata önceki geçerli durumu ezmesin (ör. abone ekranı açıkken ağ koptu).
		if(newStatus is ProStatus.Failed && (status is ProStatus.Subscribed || status is ProStatus.NotSubscribed || status is ProStatus.Pending))
		{
			_messages.tryEmit(newStatus.message)
			return
		}
		status = newStatus
		publish()
	}

	private fun publish()
	{
		_state.value = when(val s = status)
		{
			null -> State.Loading
			is ProStatus.Subscribed -> State.Subscribed(s.purchaseToken, s.autoRenewing, productDetails, plans, yearlyPlans)
			ProStatus.Pending -> State.NotSubscribed(productDetails, plans, pending = true, yearlyPlans = yearlyPlans)
			ProStatus.NotSubscribed -> State.NotSubscribed(productDetails, plans, yearlyPlans = yearlyPlans)
			is ProStatus.Unavailable -> State.NotConfigured(s.message)
			is ProStatus.Failed -> State.Error(s.message)
		}
	}

	private fun onMain(block: () -> Unit)
	{
		if(Looper.myLooper() == Looper.getMainLooper())
			block()
		else
			main.post(block)
	}

	private fun codeOf(result: BillingResult): BillingCode = when(result.responseCode)
	{
		BillingClient.BillingResponseCode.OK -> BillingCode.OK
		BillingClient.BillingResponseCode.USER_CANCELED -> BillingCode.USER_CANCELED
		BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> BillingCode.SERVICE_UNAVAILABLE
		BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> BillingCode.BILLING_UNAVAILABLE
		BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> BillingCode.ITEM_UNAVAILABLE
		BillingClient.BillingResponseCode.DEVELOPER_ERROR -> BillingCode.DEVELOPER_ERROR
		BillingClient.BillingResponseCode.ERROR -> BillingCode.ERROR
		BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> BillingCode.ITEM_ALREADY_OWNED
		BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> BillingCode.ITEM_NOT_OWNED
		BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> BillingCode.SERVICE_DISCONNECTED
		BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> BillingCode.FEATURE_NOT_SUPPORTED
		BillingClient.BillingResponseCode.NETWORK_ERROR -> BillingCode.NETWORK_ERROR
		else -> BillingCode.OTHER
	}

	private fun infoOf(p: Purchase) = PurchaseInfo(
		products = p.products.orEmpty().toList(),
		state = when(p.purchaseState)
		{
			Purchase.PurchaseState.PURCHASED -> PurchaseStateInfo.PURCHASED
			Purchase.PurchaseState.PENDING -> PurchaseStateInfo.PENDING
			else -> PurchaseStateInfo.UNSPECIFIED
		},
		purchaseToken = p.purchaseToken.orEmpty(),
		acknowledged = p.isAcknowledged,
		autoRenewing = p.isAutoRenewing
	)

	private fun offersOf(details: ProductDetails): List<OfferInfo> =
		details.subscriptionOfferDetails.orEmpty().map { o ->
			OfferInfo(
				basePlanId = o.basePlanId.orEmpty(),
				offerId = o.offerId,
				offerToken = o.offerToken.orEmpty(),
				phases = o.pricingPhases.pricingPhaseList.orEmpty().map { ph ->
					PricingPhaseInfo(
						formattedPrice = ph.formattedPrice.orEmpty(),
						priceMicros = ph.priceAmountMicros,
						currencyCode = ph.priceCurrencyCode.orEmpty(),
						billingPeriod = ph.billingPeriod.orEmpty(),
						billingCycleCount = ph.billingCycleCount,
						recurrence = when(ph.recurrenceMode)
						{
							ProductDetails.RecurrenceMode.INFINITE_RECURRING -> PricingPhaseInfo.Recurrence.INFINITE
							ProductDetails.RecurrenceMode.FINITE_RECURRING -> PricingPhaseInfo.Recurrence.FINITE
							else -> PricingPhaseInfo.Recurrence.NONE
						}
					)
				}
			)
		}.filter { it.offerToken.isNotEmpty() }
}
