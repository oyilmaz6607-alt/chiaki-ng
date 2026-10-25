// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.billing

import java.util.Calendar
import java.util.TimeZone

/**
 * AltyazıTR Pro aboneliğinin Android'den bağımsız mantığı: teklif seçimi, fiyat/süre metinleri,
 * satın alma durumunun özeti ve Google Play yanıt kodlarının Türkçe karşılıkları.
 * Billing Library türleri BillingRepository'de bu sade modellere çevrilir (birim testleri için).
 */
object ProProduct
{
	/** PRO_API.md ile aynı olmalı. */
	const val PACKAGE_NAME = "com.altyazitr.oyun"
	const val PRODUCT_ID = "altyazitr_pro"
	const val BASE_PLAN_ID = "aylik"
	const val MANAGE_URL = "https://play.google.com/store/account/subscriptions?sku=$PRODUCT_ID&package=$PACKAGE_NAME"
	const val PRIVACY_URL = "https://github.com/oyilmaz6607-alt/chiaki-ng/blob/main/store/GIZLILIK.md"
}

/** ProductDetails.PricingPhase'in sade karşılığı. */
data class PricingPhaseInfo(
	val formattedPrice: String,
	val priceMicros: Long,
	val currencyCode: String,
	/** ISO 8601 süre: P1W, P7D, P1M, P1Y… */
	val billingPeriod: String,
	val billingCycleCount: Int,
	val recurrence: Recurrence
)
{
	enum class Recurrence { INFINITE, FINITE, NONE }

	val isFree get() = priceMicros == 0L
}

/** ProductDetails.SubscriptionOfferDetails'in sade karşılığı. */
data class OfferInfo(
	val basePlanId: String,
	/** null = teklifsiz temel plan. */
	val offerId: String?,
	val offerToken: String,
	val phases: List<PricingPhaseInfo>
)
{
	/** Ücretsiz deneme aşaması (fiyatı 0 olan ilk aşama). */
	val freePhase: PricingPhaseInfo? get() = phases.firstOrNull { it.isFree }

	/** Denemeden sonra her dönem ödenen fiyat. */
	val recurringPhase: PricingPhaseInfo?
		get() = phases.lastOrNull { it.recurrence == PricingPhaseInfo.Recurrence.INFINITE && !it.isFree }
			?: phases.lastOrNull { !it.isFree }
}

/** Satın alma için seçilen teklif ve ekranda gösterilecek metinlerin kaynağı. */
data class OfferChoice(
	val offer: OfferInfo,
	/** Ücretsiz deneme süresi (ISO 8601, döngü sayısıyla çarpılmış hâli [trialCycles]); deneme yoksa null. */
	val trialPeriod: String?,
	val trialCycles: Int,
	val price: PricingPhaseInfo?
)
{
	val hasTrial get() = trialPeriod != null
}

object OfferSelector
{
	/**
	 * Google Play yalnızca kullanıcının uygun olduğu teklifleri döndürür (deneme daha önce kullanıldıysa gelmez).
	 * Önce [basePlanId] planına ait teklifler; içlerinden ücretsiz denemesi olan teklif, yoksa temel plan.
	 */
	fun select(offers: List<OfferInfo>, basePlanId: String = ProProduct.BASE_PLAN_ID): OfferChoice?
	{
		if(offers.isEmpty())
			return null
		val plan = offers.filter { it.basePlanId == basePlanId }.ifEmpty { offers }
		val chosen = plan.firstOrNull { it.offerId != null && it.freePhase != null }
			?: plan.firstOrNull { it.offerId == null }
			?: plan.first()
		val free = chosen.freePhase
		return OfferChoice(
			offer = chosen,
			trialPeriod = free?.billingPeriod,
			trialCycles = free?.billingCycleCount?.coerceAtLeast(1) ?: 0,
			price = chosen.recurringPhase
		)
	}
}

/** ISO 8601 süreleri ve fiyatları Türkçe metne çevirir. */
object PriceText
{
	private val PERIOD = Regex("^P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?$")

	data class Period(val years: Int, val months: Int, val weeks: Int, val days: Int)

	fun parse(iso: String?): Period?
	{
		val m = PERIOD.matchEntire(iso?.trim()?.uppercase() ?: return null) ?: return null
		fun g(i: Int) = m.groupValues[i].toIntOrNull() ?: 0
		val p = Period(g(1), g(2), g(3), g(4))
		return if(p.years + p.months + p.weeks + p.days == 0) null else p
	}

	/** "P1W" ×1 → "7 gün", "P1M" → "1 ay", "P3D" → "3 gün", "P1Y" → "1 yıl". Haftalar gün olarak yazılır. */
	fun duration(iso: String?, cycles: Int = 1): String?
	{
		val p = parse(iso) ?: return null
		val n = cycles.coerceAtLeast(1)
		val parts = ArrayList<String>()
		if(p.years > 0) parts.add("${p.years * n} yıl")
		if(p.months > 0) parts.add("${p.months * n} ay")
		val days = (p.weeks * 7 + p.days) * n
		if(days > 0) parts.add("$days gün")
		return parts.joinToString(" ")
	}

	/** Fiyatın yanındaki dönem: "P1M" → "ay", "P3M" → "3 ay", "P1W" → "hafta", "P1Y" → "yıl". */
	fun perPeriod(iso: String?): String?
	{
		val p = parse(iso) ?: return null
		return when
		{
			p == Period(0, 1, 0, 0) -> "ay"
			p == Period(1, 0, 0, 0) -> "yıl"
			p == Period(0, 0, 1, 0) || p == Period(0, 0, 0, 7) -> "hafta"
			else -> duration(iso)
		}
	}

	/** "₺49,99 / ay". */
	fun priceLine(price: PricingPhaseInfo?): String?
	{
		price ?: return null
		val per = perPeriod(price.billingPeriod) ?: return price.formattedPrice
		return price.formattedPrice + " / " + per
	}

	/** "7 gün ücretsiz, sonra ₺49,99 / ay"; deneme yoksa null. */
	fun trialLine(choice: OfferChoice): String?
	{
		val trial = duration(choice.trialPeriod, choice.trialCycles) ?: return null
		val price = priceLine(choice.price)
		return if(price != null) "$trial ücretsiz, sonra $price" else "$trial ücretsiz"
	}

	/** Satın alma düğmesi: "7 gün ücretsiz dene" veya "Abone ol". */
	fun buttonLabel(choice: OfferChoice?): String
	{
		val trial = choice?.let { duration(it.trialPeriod, it.trialCycles) }
		return if(trial != null) "$trial ücretsiz dene" else "Abone ol"
	}

	private val MONTHS = arrayOf("Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran",
		"Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık")

	/** "1 Kasım" (yıl bu yıl değilse "1 Ocak 2027"). */
	fun date(unixMs: Long, nowMs: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): String
	{
		val c = Calendar.getInstance(tz).also { it.timeInMillis = unixMs }
		val now = Calendar.getInstance(tz).also { it.timeInMillis = nowMs }
		val base = "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]}"
		return if(c.get(Calendar.YEAR) == now.get(Calendar.YEAR)) base else "$base ${c.get(Calendar.YEAR)}"
	}

	/** 30000 → "30.000". */
	fun count(n: Long): String
	{
		val s = kotlin.math.abs(n).toString()
		val sb = StringBuilder()
		for((i, ch) in s.withIndex())
		{
			if(i > 0 && (s.length - i) % 3 == 0)
				sb.append('.')
			sb.append(ch)
		}
		return (if(n < 0) "-" else "") + sb
	}

	/** "Bu ay 1.234 / 30.000 satır · 1 Kasım'da sıfırlanır". */
	fun quotaLine(used: Long, limit: Long, resetAt: Long, nowMs: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): String
	{
		val base = "Bu ay ${count(used)} / ${count(limit)} satır"
		return if(resetAt > 0) "$base · ${date(resetAt, nowMs, tz)} tarihinde sıfırlanır" else base
	}
}

/** Purchase.getPurchaseState() karşılığı. */
enum class PurchaseStateInfo { PURCHASED, PENDING, UNSPECIFIED }

/** Purchase'ın sade karşılığı. */
data class PurchaseInfo(
	val products: List<String>,
	val state: PurchaseStateInfo,
	val purchaseToken: String,
	val acknowledged: Boolean,
	val autoRenewing: Boolean
)

/** BillingClient.BillingResponseCode karşılığı (BillingRepository eşler). */
enum class BillingCode
{
	OK, USER_CANCELED, SERVICE_UNAVAILABLE, BILLING_UNAVAILABLE, ITEM_UNAVAILABLE, DEVELOPER_ERROR, ERROR,
	ITEM_ALREADY_OWNED, ITEM_NOT_OWNED, SERVICE_DISCONNECTED, FEATURE_NOT_SUPPORTED, NETWORK_ERROR, OTHER
}

/** Satın alma sorgusunun özeti. */
sealed class ProStatus
{
	data class Subscribed(val purchaseToken: String, val autoRenewing: Boolean): ProStatus()
	/** Ödeme bekleniyor (ör. nakit/havale): henüz Pro verilmez. */
	object Pending: ProStatus()
	object NotSubscribed: ProStatus()
	/** Bu cihazda Google Play faturalandırması yok (Play'siz/yan yüklenmiş sürüm, eski Play). */
	data class Unavailable(val message: String): ProStatus()
	/** Geçici hata: önceki bilinen durum korunur. */
	data class Failed(val message: String): ProStatus()
}

/** Satın alma akışından (PurchasesUpdatedListener) gelen sonucun yorumu. */
sealed class PurchaseUpdate
{
	data class Status(val status: ProStatus): PurchaseUpdate()
	/** Kullanıcı vazgeçti: durum değişmez, kısa bir bilgi gösterilir. */
	data class Canceled(val message: String): PurchaseUpdate()
	/** Zaten sahip: satın almalar yeniden sorgulanmalı. */
	object Requery: PurchaseUpdate()
	data class Failed(val message: String): PurchaseUpdate()
}

object BillingLogic
{
	const val MSG_UNAVAILABLE = "Google Play faturalandırması bu cihazda kullanılamıyor. Aboneliği kullanmak için uygulamayı Google Play'den yükleyin ve Play Store'da oturum açın."
	const val MSG_SUBS_UNSUPPORTED = "Bu cihazdaki Google Play sürümü abonelikleri desteklemiyor. Play Store'u güncelleyip tekrar deneyin."
	const val MSG_NETWORK = "Google Play'e ulaşılamadı. İnternet bağlantınızı kontrol edip tekrar deneyin."
	const val MSG_ITEM_UNAVAILABLE = "AltyazıTR Pro şu anda satın alınamıyor (ürün bulunamadı)."
	const val MSG_DEVELOPER = "Satın alma başlatılamadı. Uygulamayı Google Play'den yüklediğinizden emin olun."
	const val MSG_ERROR = "Google Play bir hata bildirdi. Lütfen biraz sonra tekrar deneyin."
	const val MSG_CANCELED = "Satın alma iptal edildi."
	const val MSG_PENDING = "Ödemeniz onay bekliyor. Onaylanınca AltyazıTR Pro kendiliğinden açılır."
	const val MSG_ALREADY_OWNED = "Bu aboneliğe zaten sahipsiniz; durum yenileniyor."

	/** Yanıt kodunun kullanıcıya gösterilecek Türkçe açıklaması (OK için null). */
	fun message(code: BillingCode): String? = when(code)
	{
		BillingCode.OK -> null
		BillingCode.USER_CANCELED -> MSG_CANCELED
		BillingCode.BILLING_UNAVAILABLE -> MSG_UNAVAILABLE
		BillingCode.FEATURE_NOT_SUPPORTED -> MSG_SUBS_UNSUPPORTED
		BillingCode.SERVICE_UNAVAILABLE, BillingCode.SERVICE_DISCONNECTED, BillingCode.NETWORK_ERROR -> MSG_NETWORK
		BillingCode.ITEM_UNAVAILABLE -> MSG_ITEM_UNAVAILABLE
		BillingCode.DEVELOPER_ERROR -> MSG_DEVELOPER
		BillingCode.ITEM_ALREADY_OWNED -> MSG_ALREADY_OWNED
		BillingCode.ITEM_NOT_OWNED, BillingCode.ERROR, BillingCode.OTHER -> MSG_ERROR
	}

	/** Bu cihazda faturalandırma hiç yok mu (yeniden denemenin anlamı yok)? */
	fun isUnavailable(code: BillingCode) = code == BillingCode.BILLING_UNAVAILABLE || code == BillingCode.FEATURE_NOT_SUPPORTED

	/** Bağlantı kurulamadıysa yeniden deneme beklemesi: 2, 4, 8 … en fazla 60 sn. */
	fun retryDelayMs(attempt: Int): Long = (2000L shl (attempt.coerceIn(0, 5))).coerceAtMost(60_000L)

	/** queryPurchasesAsync sonucu → durum. */
	fun status(code: BillingCode, purchases: List<PurchaseInfo>?, productId: String = ProProduct.PRODUCT_ID): ProStatus
	{
		if(code != BillingCode.OK)
		{
			val msg = message(code) ?: MSG_ERROR
			return if(isUnavailable(code)) ProStatus.Unavailable(msg) else ProStatus.Failed(msg)
		}
		val ours = purchases.orEmpty().filter { productId in it.products && it.purchaseToken.isNotEmpty() }
		ours.firstOrNull { it.state == PurchaseStateInfo.PURCHASED }?.let {
			return ProStatus.Subscribed(it.purchaseToken, it.autoRenewing)
		}
		if(ours.any { it.state == PurchaseStateInfo.PENDING })
			return ProStatus.Pending
		return ProStatus.NotSubscribed
	}

	/** Onaylanması (acknowledge) gereken satın almaların jetonları: yalnızca PURCHASED ve henüz onaylanmamış. */
	fun tokensToAcknowledge(purchases: List<PurchaseInfo>?, productId: String = ProProduct.PRODUCT_ID): List<String> =
		purchases.orEmpty()
			.filter { productId in it.products && it.state == PurchaseStateInfo.PURCHASED && !it.acknowledged && it.purchaseToken.isNotEmpty() }
			.map { it.purchaseToken }
			.distinct()

	/** onPurchasesUpdated → ne yapılmalı. */
	fun onPurchasesUpdated(code: BillingCode, purchases: List<PurchaseInfo>?, productId: String = ProProduct.PRODUCT_ID): PurchaseUpdate = when(code)
	{
		BillingCode.OK ->
			// Güncelleme yalnızca değişen satın almaları içerir: bizim ürünümüz yoksa tam liste yeniden sorgulanır.
			if(purchases.orEmpty().none { productId in it.products }) PurchaseUpdate.Requery
			else PurchaseUpdate.Status(status(code, purchases, productId))
		BillingCode.USER_CANCELED -> PurchaseUpdate.Canceled(MSG_CANCELED)
		BillingCode.ITEM_ALREADY_OWNED -> PurchaseUpdate.Requery
		else -> PurchaseUpdate.Failed(message(code) ?: MSG_ERROR)
	}
}
