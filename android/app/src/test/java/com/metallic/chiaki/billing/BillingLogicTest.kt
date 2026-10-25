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
	private val basePlan = OfferInfo("aylik", null, "tok-base", listOf(monthly))
	private val trialOffer = OfferInfo("aylik", "deneme-7", "tok-trial",
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
		val intro = OfferInfo("aylik", "indirim", "tok-intro",
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
		assertEquals("Bu ay 1.234 / 30.000 satır · 1 Kasım tarihinde sıfırlanır", PriceText.quotaLine(1234, 30000, nov1, now, utc))
		assertEquals("Bu ay 0 / 30.000 satır", PriceText.quotaLine(0, 30000, 0, now, utc))
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
		assertTrue(ProProduct.MANAGE_URL.contains("sku=altyazitr_pro&package=com.altyazitr.oyun"))
	}
}
