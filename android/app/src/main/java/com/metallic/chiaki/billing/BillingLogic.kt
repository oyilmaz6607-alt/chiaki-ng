// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.billing

import java.util.Calendar
import java.util.TimeZone

/**
 * AltyazıTR aboneliğinin (Standart / Pro / Premium paketleri + 3 gün ücretsiz deneme) Android'den bağımsız
 * mantığı: paket bilgileri, paket başına teklif seçimi, yükseltme/düşürme kararı, fiyat/süre/kota metinleri,
 * satın alma durumunun özeti ve Google Play yanıt kodlarının Türkçe karşılıkları.
 * Billing Library türleri BillingRepository'de bu sade modellere çevrilir (birim testleri için).
 */
object ProProduct
{
	/** PRO_API.md (v1.1) ile aynı olmalı. */
	const val PACKAGE_NAME = "com.altyazitr.oyun"
	/** Tek abonelik ürünü; paketler bunun temel planlarıdır ([Plans]). */
	const val PRODUCT_ID = "altyazitr_abonelik"
	/** Her temel planda aynı kimlikli 3 günlük ücretsiz deneme teklifi (yalnızca yeni müşteriler). */
	const val TRIAL_OFFER_ID = "deneme-3gun"
	const val MANAGE_URL = "https://play.google.com/store/account/subscriptions?sku=$PRODUCT_ID&package=$PACKAGE_NAME"
	const val PRIVACY_URL = "https://github.com/oyilmaz6607-alt/chiaki-ng/blob/main/store/GIZLILIK.md"
}

/**
 * Bir abonelik paketi = Play Console'daki bir temel plan.
 * [monthlyLines] yalnızca gösterim içindir; asıl sınırı sunucu uygular (wrangler.toml → PLAN_LIMITS).
 */
data class PlanSpec(
	/** Play temel plan kimliği (sunucunun "plan" değeri). */
	val id: String,
	val name: String,
	/** Kullanıcıya gösterilen yaklaşık aylık oyun saati. */
	val hoursPerMonth: Int,
	val monthlyLines: Long,
	val recommended: Boolean = false
)

/** "Ne kadar oynuyorsun?" seçenekleri (paket önerisi). */
enum class PlayHabit { FEW_HOURS_WEEKLY, MOST_EVENINGS, LONG_DAILY }

/**
 * Paketlerin uygulamadaki TEK yapılandırma yeri. Sayılar sunucudaki PLAN_LIMITS ile aynı tutulmalı;
 * fiyatlar burada değil Play Console'dadır (ekranda Play'in biçimlendirdiği fiyat gösterilir).
 * Varsayım: diyaloğu yoğun oyunda saatte ~[LINES_PER_HOUR] altyazı satırı.
 */
object Plans
{
	const val LINES_PER_HOUR = 450
	val STANDART = PlanSpec("standart", "Standart", 10, 4_500)
	val PRO = PlanSpec("pro", "Pro", 25, 11_000, recommended = true)
	val PREMIUM = PlanSpec("premium", "Premium", 60, 27_000)
	/** Ucuzdan pahalıya (yükseltme sırası). */
	val ALL = listOf(STANDART, PRO, PREMIUM)

	/** Sunucunun deneme dönemi için verdiği "plan" değeri. */
	const val TRIAL_ID = "deneme"
	const val TRIAL_NAME = "Deneme"
	const val TRIAL_LINES = 1_500L

	val recommended: PlanSpec get() = ALL.firstOrNull { it.recommended } ?: ALL.first()

	fun byId(id: String?): PlanSpec? = ALL.firstOrNull { it.id == id }

	/** Sıra (0 = en ucuz); bilinmeyen → -1. */
	fun rank(id: String?): Int = ALL.indexOfFirst { it.id == id }

	/** Bir üst paket (en üstteyse veya bilinmiyorsa null). */
	fun next(id: String?): PlanSpec?
	{
		val r = rank(id)
		return if(r < 0) null else ALL.getOrNull(r + 1)
	}

	fun forHabit(habit: PlayHabit): PlanSpec = when(habit)
	{
		PlayHabit.FEW_HOURS_WEEKLY -> STANDART
		PlayHabit.MOST_EVENINGS -> PRO
		PlayHabit.LONG_DAILY -> PREMIUM
	}

	/** Sunucunun plan kimliğinin adı ("deneme" → "Deneme"); bilinmiyorsa null. */
	fun name(id: String?): String? = if(id == TRIAL_ID) TRIAL_NAME else byId(id)?.name
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

/** Bir paket ve onun için seçilen teklif (ürün/plan Play'de bulunamadıysa [choice] null). */
data class PlanOffer(val spec: PlanSpec, val choice: OfferChoice?)

/** Seçilen pakete geçişin türü (Play'deki değiştirme modu buna göre seçilir). */
enum class PlanChange
{
	/** Abonelik yok: yeni satın alma (uygunsa ücretsiz deneme). */
	NEW,
	SAME,
	/** Daha pahalı pakete: hemen geçerli, kalan süre orantılı olarak düşülür (WITH_TIME_PRORATION). */
	UPGRADE,
	/** Daha ucuz pakete: mevcut dönem sonunda geçerli (DEFERRED). */
	DOWNGRADE,
	/** Abone ama mevcut paket henüz bilinmiyor (sunucuya ulaşılamadı): Play'in varsayılanı (WITH_TIME_PRORATION). */
	UNKNOWN
}

object PlanChanges
{
	/** [currentPlan] sunucunun bildirdiği temel plan (denemede de temel plan). */
	fun of(subscribed: Boolean, currentPlan: String?, target: String): PlanChange
	{
		if(!subscribed)
			return PlanChange.NEW
		val from = Plans.rank(currentPlan)
		val to = Plans.rank(target)
		return when
		{
			from < 0 || to < 0 -> PlanChange.UNKNOWN
			to == from -> PlanChange.SAME
			to > from -> PlanChange.UPGRADE
			else -> PlanChange.DOWNGRADE
		}
	}
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
	 * [strict] = false ise plan bulunamazsa eldeki tüm teklifler arasından seçilir.
	 */
	fun select(offers: List<OfferInfo>, basePlanId: String = Plans.recommended.id, strict: Boolean = false): OfferChoice?
	{
		val plan = offers.filter { it.basePlanId == basePlanId }.ifEmpty { if(strict) emptyList() else offers }
		if(plan.isEmpty())
			return null
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

	/** Her paket için (sırayla) kendi temel planının teklifi; Play'de olmayan paketin teklifi null. */
	fun selectPlans(offers: List<OfferInfo>, plans: List<PlanSpec> = Plans.ALL): List<PlanOffer> =
		plans.map { PlanOffer(it, select(offers, it.id, strict = true)) }
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

	/**
	 * "Bu ay: 3.120 / 11.000 satır, yenilenme: 1 Kasım"; denemede
	 * "Deneme: 320 / 1.500 satır, deneme bitişi: 28 Ekim".
	 */
	fun quotaLine(used: Long, limit: Long, resetAt: Long, nowMs: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault(),
		trial: Boolean = false): String
	{
		val base = (if(trial) "Deneme: " else "Bu ay: ") + "${count(used)} / ${count(limit)} satır"
		if(resetAt <= 0)
			return base
		return base + (if(trial) ", deneme bitişi: " else ", yenilenme: ") + date(resetAt, nowMs, tz)
	}

	/** Kota çubuğu için binde oran (0..1000). */
	fun quotaPermille(used: Long, limit: Long): Int =
		if(limit <= 0) 0 else ((used.coerceAtLeast(0) * 1000) / limit).coerceIn(0, 1000).toInt()
}

/** Paket kartındaki metinler. */
object PlanText
{
	/** "Ayda ~25 saat oyun". */
	fun hours(spec: PlanSpec) = "Ayda ~${spec.hoursPerMonth} saat oyun"

	/** "11.000 satır yapay zekâ çevirisi". */
	fun lines(spec: PlanSpec) = "${PriceText.count(spec.monthlyLines)} satır yapay zekâ çevirisi"

	/** "3 gün ücretsiz dene" (yalnızca deneme teklifi varsa). */
	fun trial(choice: OfferChoice?): String? =
		choice?.takeIf { it.hasTrial }?.let { PriceText.buttonLabel(it) }

	/** Kartın alt satırı: işlemin adı. */
	fun action(change: PlanChange): String = when(change)
	{
		PlanChange.NEW -> "Abone ol"
		PlanChange.SAME -> "Mevcut paketin"
		PlanChange.UPGRADE -> "Yükselt"
		PlanChange.DOWNGRADE -> "Düşür"
		PlanChange.UNKNOWN -> "Bu pakete geç"
	}

	/** Ana ekran rozeti / telefon: "Pro paketi", "Ücretsiz deneme"; bilinmiyorsa null. */
	fun badge(plan: String?): String? = when
	{
		plan == Plans.TRIAL_ID -> "Ücretsiz deneme"
		Plans.byId(plan) != null -> "${Plans.byId(plan)!!.name} paketi"
		else -> null
	}
}

/**
 * Kota dolunca: o dönemin sonuna kadar cihaz içi çeviriye dönülür ve kullanıcıya bir kez (dönem başına)
 * bir üst paket önerilir.
 */
object QuotaNotice
{
	/** Kota bu dönem için dolu mu (sunucunun son bildirdiği kotaya göre)? */
	fun isExhausted(used: Long, limit: Long, resetAt: Long, nowMs: Long): Boolean =
		limit > 0 && used >= limit && resetAt > nowMs

	/**
	 * Tek seferlik bildirim. [plan] sunucunun "plan" değeri, [basePlan] temel plan (denemede de).
	 */
	fun message(plan: String?, basePlan: String?, resetAt: Long, nowMs: Long = System.currentTimeMillis(),
		tz: TimeZone = TimeZone.getDefault()): String
	{
		val whenText = if(resetAt > nowMs) PriceText.date(resetAt, nowMs, tz) else null
		if(plan == Plans.TRIAL_ID)
		{
			val start = whenText?.let { " Paketin $it tarihinde başlayınca yapay zekâ çevirisi kaldığı yerden devam eder." } ?: ""
			return "Ücretsiz denemenin çeviri hakkı doldu; şimdilik cihaz içi çeviri kullanılıyor.$start"
		}
		val name = Plans.name(plan ?: basePlan)
		val head = if(name != null) "$name paketinin bu ayki yapay zekâ çeviri hakkı doldu" else "Bu ayki yapay zekâ çeviri hakkın doldu"
		val until = whenText?.let { "; $it tarihine kadar cihaz içi çeviri kullanılıyor." } ?: "; ay sonuna kadar cihaz içi çeviri kullanılıyor."
		val up = Plans.next(plan ?: basePlan)?.let { " Daha çok oynuyorsan ${it.name} paketine geçebilirsin (Ayarlar → AltyazıTR Abonelik)." } ?: ""
		return head + until + up
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
	const val MSG_ITEM_UNAVAILABLE = "AltyazıTR aboneliği şu anda satın alınamıyor (ürün bulunamadı)."
	const val MSG_DEVELOPER = "Satın alma başlatılamadı. Uygulamayı Google Play'den yüklediğinizden emin olun."
	const val MSG_ERROR = "Google Play bir hata bildirdi. Lütfen biraz sonra tekrar deneyin."
	const val MSG_CANCELED = "Satın alma iptal edildi."
	const val MSG_PENDING = "Ödemeniz onay bekliyor. Onaylanınca aboneliğiniz kendiliğinden başlar."
	const val MSG_ALREADY_OWNED = "Bu aboneliğe zaten sahipsiniz; durum yenileniyor."
	const val MSG_SAME_PLAN = "Zaten bu paketi kullanıyorsunuz."
	const val MSG_UPGRADED = "Paketiniz yükseltildi; yeni satır hakkı birkaç dakika içinde görünür."
	const val MSG_DOWNGRADE_DEFERRED = "Paket değişikliği alındı; mevcut paketiniz dönem sonuna kadar geçerli kalır."

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
