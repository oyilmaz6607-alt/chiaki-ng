// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.billing

import com.metallic.chiaki.billing.PricingPhaseInfo.Recurrence
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class BillingLogicTest
{
	private fun phase(price: String, micros: Long, period: String, cycles: Int, rec: Recurrence) =
		PricingPhaseInfo(price, micros, "TRY", period, cycles, rec)

	private val monthly = phase("₺49,99", 49_990_000, "P1M", 0, Recurrence.INFINITE)
	private val basePlan = OfferInfo("pro", null, "tok-base", listOf(monthly))
	private val trialOffer = OfferInfo("pro", "deneme-7", "tok-trial",
		listOf(phase("Ücretsiz", 0, "P1W", 1, Recurrence.FINITE), monthly))

	@Test
	fun selectsTrialOfferWhenEligible()
	{
		val c = OfferSelector.select(listOf(basePlan, trialOffer))!!
		assertEquals("tok-trial", c.offer.offerToken)
		assertTrue(c.hasTrial)
		assertEquals("P1W", c.trialPeriod)
		assertEquals(monthly, c.price)
		assertEquals("7 gün ücretsiz dene", PriceText.buttonLabel(c))
		assertEquals("7 gün ücretsiz, sonra ₺49,99 / ay", PriceText.trialLine(c))
		assertEquals("₺49,99 / ay", PriceText.priceLine(c.price))
	}

	@Test
	fun selectsBasePlanWithoutTrial()
	{
		val c = OfferSelector.select(listOf(basePlan))!!
		assertEquals("tok-base", c.offer.offerToken)
		assertFalse(c.hasTrial)
		assertEquals("Abone ol", PriceText.buttonLabel(c))
		assertNull(PriceText.trialLine(c))
		assertEquals("Abone ol", PriceText.buttonLabel(null))
	}

	@Test
	fun prefersConfiguredBasePlan()
	{
		val yearly = OfferInfo("yillik", null, "tok-year", listOf(phase("₺399", 399_000_000, "P1Y", 0, Recurrence.INFINITE)))
		val yearlyTrial = OfferInfo("yillik", "t", "tok-year-trial",
			listOf(phase("Ücretsiz", 0, "P1M", 1, Recurrence.FINITE), phase("₺399", 399_000_000, "P1Y", 0, Recurrence.INFINITE)))
		assertEquals("tok-base", OfferSelector.select(listOf(yearly, yearlyTrial, basePlan))!!.offer.offerToken)
		// Plan bulunamazsa eldeki teklifler kullanılır.
		assertEquals("tok-year-trial", OfferSelector.select(listOf(yearly, yearlyTrial))!!.offer.offerToken)
		assertNull(OfferSelector.select(emptyList()))
	}

	@Test
	fun discountedIntroIsNotTrial()
	{
		val intro = OfferInfo("pro", "indirim", "tok-intro",
			listOf(phase("₺9,99", 9_990_000, "P1M", 1, Recurrence.FINITE), monthly))
		val c = OfferSelector.select(listOf(intro, basePlan))!!
		assertEquals("tok-base", c.offer.offerToken)
	}

	@Test
	fun periodTexts()
	{
		assertEquals("7 gün", PriceText.duration("P1W"))
		assertEquals("3 gün", PriceText.duration("P3D"))
		assertEquals("14 gün", PriceText.duration("P1W", 2))
		assertEquals("1 ay", PriceText.duration("P1M"))
		assertEquals("1 yıl", PriceText.duration("p1y"))
		assertNull(PriceText.duration("P0D"))
		assertNull(PriceText.duration("1M"))
		assertNull(PriceText.duration(null))
		assertEquals("ay", PriceText.perPeriod("P1M"))
		assertEquals("3 ay", PriceText.perPeriod("P3M"))
		assertEquals("hafta", PriceText.perPeriod("P1W"))
		assertEquals("yıl", PriceText.perPeriod("P1Y"))
		assertEquals("1 ay ücretsiz dene", PriceText.buttonLabel(
			OfferChoice(basePlan, "P1M", 1, monthly)))
		// Dönemi okunamayan fiyat yalnızca fiyat olarak gösterilir.
		assertEquals("₺5", PriceText.priceLine(phase("₺5", 5_000_000, "?", 0, Recurrence.INFINITE)))
	}

	@Test
	fun quotaAndDates()
	{
		val utc = TimeZone.getTimeZone("UTC")
		val now = 1_793_000_000_000L // 2026-10-26
		val nov1 = 1_793_491_200_000L // 2026-11-01T00:00Z
		assertEquals("1 Kasım", PriceText.date(nov1, now, utc))
		assertEquals("1 Ocak 2027", PriceText.date(1_798_761_600_000L, now, utc))
		assertEquals("30.000", PriceText.count(30000))
		assertEquals("123", PriceText.count(123))
		assertEquals("1.234.567", PriceText.count(1234567))
		assertEquals("Bu ay: 3.120 / 11.000 satır, yenilenme: 1 Kasım", PriceText.quotaLine(3120, 11000, nov1, now, utc))
		assertEquals("Bu ay: 0 / 30.000 satır", PriceText.quotaLine(0, 30000, 0, now, utc))
		val oct28 = 1_793_145_600_000L // 2026-10-28T00:00Z
		assertEquals("Deneme: 320 / 1.500 satır, deneme bitişi: 28 Ekim", PriceText.quotaLine(320, 1500, oct28, now, utc, trial = true))
		assertEquals(283, PriceText.quotaPermille(3120, 11000))
		assertEquals(1000, PriceText.quotaPermille(12000, 11000))
		assertEquals(0, PriceText.quotaPermille(-5, 11000))
		assertEquals(0, PriceText.quotaPermille(5, 0))
	}

	private fun purchase(state: PurchaseStateInfo, token: String = "pt", ack: Boolean = false, renew: Boolean = true,
		product: String = ProProduct.PRODUCT_ID) = PurchaseInfo(listOf(product), state, token, ack, renew)

	@Test
	fun purchaseStatus()
	{
		assertEquals(ProStatus.Subscribed("pt", true), BillingLogic.status(BillingCode.OK, listOf(purchase(PurchaseStateInfo.PURCHASED))))
		assertEquals(ProStatus.Subscribed("pt", false),
			BillingLogic.status(BillingCode.OK, listOf(purchase(PurchaseStateInfo.PURCHASED, renew = false))))
		assertEquals(ProStatus.Pending, BillingLogic.status(BillingCode.OK, listOf(purchase(PurchaseStateInfo.PENDING))))
		assertEquals(ProStatus.NotSubscribed, BillingLogic.status(BillingCode.OK, emptyList()))
		assertEquals(ProStatus.NotSubscribed, BillingLogic.status(BillingCode.OK, null))
		assertEquals(ProStatus.NotSubscribed,
			BillingLogic.status(BillingCode.OK, listOf(purchase(PurchaseStateInfo.PURCHASED, product = "baska"))))
		assertTrue(BillingLogic.status(BillingCode.BILLING_UNAVAILABLE, null) is ProStatus.Unavailable)
		assertTrue(BillingLogic.status(BillingCode.FEATURE_NOT_SUPPORTED, null) is ProStatus.Unavailable)
		assertTrue(BillingLogic.status(BillingCode.SERVICE_UNAVAILABLE, null) is ProStatus.Failed)
		assertTrue(BillingLogic.status(BillingCode.NETWORK_ERROR, null) is ProStatus.Failed)
	}

	@Test
	fun acknowledgeOnlyPurchasedUnacknowledged()
	{
		val list = listOf(
			purchase(PurchaseStateInfo.PURCHASED, "a"),
			purchase(PurchaseStateInfo.PURCHASED, "b", ack = true),
			purchase(PurchaseStateInfo.PENDING, "c"),
			purchase(PurchaseStateInfo.PURCHASED, "d", product = "baska"),
			purchase(PurchaseStateInfo.PURCHASED, "a")
		)
		assertEquals(listOf("a"), BillingLogic.tokensToAcknowledge(list))
		assertTrue(BillingLogic.tokensToAcknowledge(null).isEmpty())
	}

	@Test
	fun purchaseUpdates()
	{
		assertEquals(PurchaseUpdate.Status(ProStatus.Subscribed("pt", true)),
			BillingLogic.onPurchasesUpdated(BillingCode.OK, listOf(purchase(PurchaseStateInfo.PURCHASED))))
		assertEquals(PurchaseUpdate.Status(ProStatus.Pending),
			BillingLogic.onPurchasesUpdated(BillingCode.OK, listOf(purchase(PurchaseStateInfo.PENDING))))
		assertEquals(PurchaseUpdate.Requery, BillingLogic.onPurchasesUpdated(BillingCode.OK, null))
		assertEquals(PurchaseUpdate.Requery,
			BillingLogic.onPurchasesUpdated(BillingCode.OK, listOf(purchase(PurchaseStateInfo.PURCHASED, product = "baska"))))
		assertEquals(PurchaseUpdate.Requery, BillingLogic.onPurchasesUpdated(BillingCode.ITEM_ALREADY_OWNED, null))
		assertEquals(PurchaseUpdate.Canceled(BillingLogic.MSG_CANCELED), BillingLogic.onPurchasesUpdated(BillingCode.USER_CANCELED, null))
		assertEquals(PurchaseUpdate.Failed(BillingLogic.MSG_UNAVAILABLE), BillingLogic.onPurchasesUpdated(BillingCode.BILLING_UNAVAILABLE, null))
		assertEquals(PurchaseUpdate.Failed(BillingLogic.MSG_ERROR), BillingLogic.onPurchasesUpdated(BillingCode.ERROR, null))
	}

	@Test
	fun messagesAndRetry()
	{
		BillingCode.values().filter { it != BillingCode.OK }.forEach { assertNotNull(it.name, BillingLogic.message(it)) }
		assertNull(BillingLogic.message(BillingCode.OK))
		assertEquals(2000L, BillingLogic.retryDelayMs(0))
		assertEquals(4000L, BillingLogic.retryDelayMs(1))
		assertEquals(60_000L, BillingLogic.retryDelayMs(10))
		assertTrue(ProProduct.MANAGE_URL.contains("sku=altyazitr_abonelik&package=com.altyazitr.oyun"))
	}

	// ---------------------------------------------------------------- paketler

	private fun planOffers(id: String, price: String, micros: Long, trial: Boolean): List<OfferInfo>
	{
		val m = phase(price, micros, "P1M", 0, Recurrence.INFINITE)
		val base = OfferInfo(id, null, "tok-$id", listOf(m))
		return if(trial) listOf(base, OfferInfo(id, ProProduct.TRIAL_OFFER_ID, "tok-$id-trial",
			listOf(phase("Ücretsiz", 0, "P3D", 1, Recurrence.FINITE), m))) else listOf(base)
	}

	@Test
	fun planConfig()
	{
		assertEquals(listOf("standart", "pro", "premium"), Plans.ALL.map { it.id })
		assertEquals(listOf(10, 25, 60), Plans.ALL.map { it.hoursPerMonth })
		assertEquals(listOf(4_500L, 11_000L, 27_000L), Plans.ALL.map { it.monthlyLines })
		assertEquals(1_500L, Plans.TRIAL_LINES)
		assertEquals(Plans.PRO, Plans.recommended)
		assertEquals(listOf(Plans.PRO), Plans.ALL.filter { it.recommended })
		// ~450 satır/saat varsayımıyla tutarlı (±%10)
		Plans.ALL.forEach { assertTrue(it.id, kotlin.math.abs(it.hoursPerMonth * Plans.LINES_PER_HOUR - it.monthlyLines) <= it.monthlyLines / 10) }
		assertEquals(Plans.PRO, Plans.next("standart"))
		assertEquals(Plans.PREMIUM, Plans.next("pro"))
		assertNull(Plans.next("premium"))
		assertNull(Plans.next("deneme"))
		assertNull(Plans.next(null))
		assertEquals(Plans.STANDART, Plans.forHabit(PlayHabit.FEW_HOURS_WEEKLY))
		assertEquals(Plans.PRO, Plans.forHabit(PlayHabit.MOST_EVENINGS))
		assertEquals(Plans.PREMIUM, Plans.forHabit(PlayHabit.LONG_DAILY))
		assertEquals("Deneme", Plans.name("deneme"))
		assertEquals("Premium", Plans.name("premium"))
		assertNull(Plans.name("aylik"))
	}

	@Test
	fun selectsOfferPerPlan()
	{
		// Deneme yalnızca uygun kullanıcıya döner: Standart ve Premium'da var, Pro'da yok (ör. Play göndermedi).
		val offers = planOffers("standart", "₺79,99", 79_990_000, true) + planOffers("pro", "₺149,99", 149_990_000, false) +
				planOffers("premium", "₺299,99", 299_990_000, true)
		val plans = OfferSelector.selectPlans(offers)
		assertEquals(Plans.ALL, plans.map { it.spec })
		assertEquals(listOf("tok-standart-trial", "tok-pro", "tok-premium-trial"), plans.map { it.choice!!.offer.offerToken })
		assertEquals("3 gün ücretsiz dene", PlanText.trial(plans[0].choice))
		assertNull(PlanText.trial(plans[1].choice))
		assertEquals("₺149,99 / ay", PriceText.priceLine(plans[1].choice!!.price))
		assertEquals("₺299,99 / ay", PriceText.priceLine(plans[2].choice!!.price))
		assertEquals("3 gün ücretsiz, sonra ₺299,99 / ay", PriceText.trialLine(plans[2].choice!!))
		// Play'de bulunmayan temel plan başka planın teklifiyle doldurulmaz.
		val partial = OfferSelector.selectPlans(planOffers("pro", "₺149,99", 149_990_000, true))
		assertNull(partial[0].choice)
		assertEquals("tok-pro-trial", partial[1].choice!!.offer.offerToken)
		assertNull(partial[2].choice)
		assertTrue(OfferSelector.selectPlans(emptyList()).all { it.choice == null })
		assertNull(OfferSelector.select(planOffers("pro", "₺1", 1, false), "premium", strict = true))
	}

	@Test
	fun planChanges()
	{
		assertEquals(PlanChange.NEW, PlanChanges.of(false, null, "pro"))
		assertEquals(PlanChange.NEW, PlanChanges.of(false, "pro", "pro"))
		assertEquals(PlanChange.SAME, PlanChanges.of(true, "pro", "pro"))
		assertEquals(PlanChange.UPGRADE, PlanChanges.of(true, "standart", "premium"))
		assertEquals(PlanChange.UPGRADE, PlanChanges.of(true, "pro", "premium"))
		assertEquals(PlanChange.DOWNGRADE, PlanChanges.of(true, "premium", "standart"))
		assertEquals(PlanChange.UNKNOWN, PlanChanges.of(true, null, "pro"))
		assertEquals(PlanChange.UNKNOWN, PlanChanges.of(true, "aylik", "pro"))
		assertEquals("Abone ol", PlanText.action(PlanChange.NEW))
		assertEquals("Mevcut paketin", PlanText.action(PlanChange.SAME))
		assertEquals("Yükselt", PlanText.action(PlanChange.UPGRADE))
		assertEquals("Düşür", PlanText.action(PlanChange.DOWNGRADE))
	}

	@Test
	fun planTexts()
	{
		assertEquals("Ayda ~10 saat oyun", PlanText.hours(Plans.STANDART))
		assertEquals("Ayda ~60 saat oyun", PlanText.hours(Plans.PREMIUM))
		assertEquals("4.500 satır yapay zekâ çevirisi", PlanText.lines(Plans.STANDART))
		assertEquals("11.000 satır yapay zekâ çevirisi", PlanText.lines(Plans.PRO))
		assertEquals("27.000 satır yapay zekâ çevirisi", PlanText.lines(Plans.PREMIUM))
		assertEquals("Pro paketi", PlanText.badge("pro"))
		assertEquals("Ücretsiz deneme", PlanText.badge("deneme"))
		assertNull(PlanText.badge(null))
		assertNull(PlanText.badge("aylik"))
		assertNull(PlanText.trial(null))
	}

	@Test
	fun quotaNotice()
	{
		val utc = TimeZone.getTimeZone("UTC")
		val now = 1_793_000_000_000L // 2026-10-26
		val nov1 = 1_793_491_200_000L
		assertTrue(QuotaNotice.isExhausted(11000, 11000, nov1, now))
		assertTrue(QuotaNotice.isExhausted(11005, 11000, nov1, now))
		assertFalse(QuotaNotice.isExhausted(10999, 11000, nov1, now))
		assertFalse("dönem bitti", QuotaNotice.isExhausted(11000, 11000, now - 1, now))
		assertFalse(QuotaNotice.isExhausted(0, 0, nov1, now))
		assertEquals("Pro paketinin bu ayki yapay zekâ çeviri hakkı doldu; 1 Kasım tarihine kadar cihaz içi çeviri kullanılıyor." +
				" Daha çok oynuyorsan Premium paketine geçebilirsin (Ayarlar → AltyazıTR Abonelik).",
			QuotaNotice.message("pro", "pro", nov1, now, utc))
		assertEquals("Premium paketinin bu ayki yapay zekâ çeviri hakkı doldu; 1 Kasım tarihine kadar cihaz içi çeviri kullanılıyor.",
			QuotaNotice.message("premium", "premium", nov1, now, utc))
		assertTrue(QuotaNotice.message("standart", null, nov1, now, utc).endsWith("Pro paketine geçebilirsin (Ayarlar → AltyazıTR Abonelik)."))
		val oct28 = 1_793_145_600_000L
		assertEquals("Ücretsiz denemenin çeviri hakkı doldu; şimdilik cihaz içi çeviri kullanılıyor." +
				" Paketin 28 Ekim tarihinde başlayınca yapay zekâ çevirisi kaldığı yerden devam eder.",
			QuotaNotice.message("deneme", "premium", oct28, now, utc))
		assertEquals("Bu ayki yapay zekâ çeviri hakkın doldu; ay sonuna kadar cihaz içi çeviri kullanılıyor.",
			QuotaNotice.message(null, null, 0, now, utc))
	}
}
