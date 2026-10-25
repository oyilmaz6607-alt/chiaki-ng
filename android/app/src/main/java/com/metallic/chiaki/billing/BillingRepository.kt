// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AltyazıTR Pro aboneliği için Google Play Faturalandırma (Billing Library 8).
 *
 * Uygulama ömrü boyunca tek örnek ([get]); tüm durum değişiklikleri ana iş parçacığında yapılır.
 * Bağlantı koparsa kütüphane kendisi yeniden bağlanır (enableAutoServiceReconnection); ilk kurulum
 * geçici bir hatayla biterse artan aralıklarla yeniden denenir. Play'siz cihazlarda (yan yüklenmiş test
 * sürümü vb.) çökme yerine [State.NotConfigured] ile Türkçe açıklama verilir.
 * Doğrulanmış abonelik [ProStore]'a yazılır; altyazı servisi oradan okur.
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
			val offer: OfferChoice?,
			/** Ödeme onay bekliyor (Pro henüz açılmaz). */
			val pending: Boolean = false
		): State()
		data class Subscribed(val purchaseToken: String, val autoRenewing: Boolean): State()
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
	private var offer: OfferChoice? = null
	private var status: ProStatus? = null
	private val retryRunnable = Runnable { start() }

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
				offer = product?.let { OfferSelector.select(offersOf(it)) }
				publish()
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

	/** Satın alma ekranını açar. false = başlatılamadı (sebebi [messages] ile bildirilir). */
	fun launchPurchase(activity: Activity): Boolean
	{
		val c = client
		val product = productDetails
		val choice = offer
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
		val flowParams = BillingFlowParams.newBuilder()
			.setProductDetailsParamsList(listOf(
				BillingFlowParams.ProductDetailsParams.newBuilder()
					.setProductDetails(product)
					.setOfferToken(choice.offer.offerToken)
					.build()
			))
			.build()
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
			return true
		Log.w(TAG, "launchBillingFlow: ${result.responseCode} ${result.debugMessage}")
		handleUpdate(BillingLogic.onPurchasesUpdated(code, null), emptyList())
		return false
	}

	override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?)
	{
		val list = purchases?.toList().orEmpty()
		val code = codeOf(result)
		onMain { handleUpdate(BillingLogic.onPurchasesUpdated(code, list.map { infoOf(it) }), list) }
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
			}
			is PurchaseUpdate.Canceled -> _messages.tryEmit(update.message)
			PurchaseUpdate.Requery ->
			{
				queryPurchases()
			}
			is PurchaseUpdate.Failed -> _messages.tryEmit(update.message)
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
			is ProStatus.Subscribed -> State.Subscribed(s.purchaseToken, s.autoRenewing)
			ProStatus.Pending -> State.NotSubscribed(productDetails, offer, pending = true)
			ProStatus.NotSubscribed -> State.NotSubscribed(productDetails, offer)
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
