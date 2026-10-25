# AltyazıTR abonelik — sunucu ↔ uygulama sözleşmesi (v1.2)

Both the Cloudflare Worker (server/) and the Android app (subtitle/ + billing/) implement exactly this. Do not change it unilaterally.
v1.1 adds tiered plans (Standart / Pro / Premium) and a 3-day free trial. Paths stay under `/v1`.
v1.2 adds yearly base plans, the one-time top-up pack (`POST /v1/topup`), re-tuned caps, short-line routing, context gating,
a smarter shared cache and proportional output caps. v1.1 clients keep working (extra fields are ignored; a v1.1 app that
sends 4 context pairs is accepted, the server just uses the last 2).

## Constants
- Android package: `com.altyazitr.oyun`
- Play subscription product ID: **`altyazitr_abonelik`** (replaces `altyazitr_pro` of v1) with three monthly and three yearly auto-renewing base plans:

| Base plan ID (monthly / yearly) | Name shown | Hours/month shown in the app | Monthly AI line cap (default) |
|---|---|---|---|
| `standart` / `standart-yillik` | Standart | ~10 saat | 4 500 |
| `pro` / `pro-yillik` | Pro | ~22 saat | 10 000 |
| `premium` / `premium-yillik` | Premium | ~50 saat | 24 000 |

  Assumption behind the hours: ~450 subtitle lines per hour of dialogue-heavy play (conservative: short lines routed on-device, see below, do not count).
  **Yearly** base plans (`<plan>-yillik`, billed yearly) use the **same monthly cap** as their monthly plan; the quota still resets every calendar month.
  The server maps `<plan>-yillik` → `<plan>` automatically (an explicit `PLAN_LIMITS` entry for the yearly ID wins).
- One-time top-up: in-app product (consumable, `INAPP`) **`ek_satir_2000`** = **+2 000 AI lines** valid until the end of the current quota month (see `POST /v1/topup`).
- Free trial: an offer with ID **`deneme-3gun`** on every base plan (one 3-day free-trial phase, eligibility "new customer acquisition: never had any subscription"). During the trial the plan is `deneme` with **1 500 lines in total** for the whole trial (not per month).
- Prices are set in Play Console only; the app shows Play's formatted price and computes the yearly "x ay bedava" saving from Play's prices.
- Base URL: configured in the app as BuildConfig `PRO_API_BASE_URL` (Gradle property `proApiBaseUrl`, default empty string = "subscription not configured yet").
- Caps live in ONE place per side: server env `PLAN_LIMITS` (JSON, see Quota) — the server is authoritative; the app's `billing/BillingLogic.kt → Plans` holds the display copy (hours + lines) and must be kept in sync by hand.

## Endpoints (JSON, UTF-8, HTTPS)

### GET /v1/health
→ `200 {"ok":true,"version":"1.2"}`

### POST /v1/session
Request: `{"packageName":"com.altyazitr.oyun","productId":"altyazitr_abonelik","purchaseToken":"<from Play Billing>","appVersion":"1.2.3"}`

Server verifies the token with Google Play Developer API `purchases.subscriptionsv2.get`
(`GET https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{packageName}/purchases/subscriptionsv2/tokens/{token}`, service-account auth).
Active states: `SUBSCRIPTION_STATE_ACTIVE`, `SUBSCRIPTION_STATE_IN_GRACE_PERIOD` (a trial is ACTIVE). Anything else → not active.

Plan resolution (fields of `SubscriptionPurchaseV2`):
- Of `lineItems[]` with `productId == "altyazitr_abonelik"` and `expiryTime` in the future, the one with the latest `expiryTime` is used.
- Base plan = that line item's `offerDetails.basePlanId`. Must be a key of `PLAN_LIMITS` (and not `deneme`) or `<key>-yillik`, else → `invalid_purchase`.
- Trial = `lineItems[].offerPhase.freeTrial` is set (OfferPhase union: `freeTrial` | `introductoryPrice` | `prorationPeriod` | `basePrice`).
  Fallback only when `offerPhase` is absent: `offerDetails.offerId == "deneme-3gun"` AND the subscription's `startTime` is less than 3 days ago.
- Quota bucket: `linkedPurchaseToken` (set on upgrade/downgrade, re-signup of a cancelled-but-not-lapsed subscription, etc.) links the new token to the old one — see Quota.

Success →
```
200 {"ok":true,"sessionToken":"<opaque>","expiresAt":<unix ms>,
     "plan":"pro","planName":"Pro","basePlan":"pro-yillik","basePlanName":"Pro (yıllık)","trial":false,
     "quota":{"used":123,"limit":10000,"resetAt":<unix ms>}}
```
- `plan` is `"standart"|"pro"|"premium"|"deneme"` (the cap tier, also for yearly plans); during the trial `plan:"deneme"`, `trial:true` and `basePlan` is the base plan the trial converts into. `basePlan` is the raw Play base plan ID (`pro` or `pro-yillik`).
- `quota.limit` = plan cap + top-up bonus of the current quota period; when a bonus exists the quota object also carries `"bonus":<lines>`.
- `quota.resetAt` = start of next calendar month (UTC); during the trial = trial end (`expiryTime`).

Errors → `{"ok":false,"code":"<code>","message":"<Turkish user-facing text>"}` with codes:
- `not_subscribed` (403) — token valid but not active/expired/cancelled-and-ended
- `invalid_purchase` (400) — unknown/forged token, wrong package/product, **unknown base plan**
- `rate_limited` (429)
- `server_error` (500/502)

### POST /v1/translate
Headers: `Authorization: Bearer <sessionToken>`, `Content-Type: application/json`, optional `Accept: application/x-ndjson` for streaming.
Request: `{"text":"<English subtitle, ≤ 600 chars>","context":[{"en":"...","tr":"..."}],"speaker":null}` — context ≤ 4 pairs accepted, each ≤ 600 chars.

Cost rules (both sides implement them; the server enforces them regardless of the client):
- **Short-line routing (app):** lines of ≤ 3 words (tokens containing a letter/digit) or ≤ 18 characters, all-caps UI labels of ≤ 5 words,
  and lines without letters (numbers) are translated on-device (ML Kit) and never sent — no server call, no quota use.
  (Only when the on-device model is ready; otherwise they go to the server as before.) Thresholds: `ProRouting.kt`.
- **Context only when useful:** the app sends previous pairs only for lines of ≥ 6 words that contain a pronoun/reference
  (`it they he she him her this that there them his its those these`) or start lowercase / with `and`/`but`/`so`;
  at most **2** pairs, each text truncated to **160** chars. The server applies the same rule (`contextUseful`) and uses
  at most the last 2 pairs × 160 chars; context the server would not use is ignored (not in the prompt, not in the cache key).
- **Speaker label:** a leading all-caps label (`JOEL: …`, ≤ 3 words) is stripped before translation/caching and re-attached to the result unchanged.
- **Output cap:** `maxOutputTokens = clamp(2 × input words + 24, 32, 160)`. If Gemini stops with `MAX_TOKENS`, a non-streaming
  request is retried once with 200; a streaming one ends with an `upstream_error` line (nothing cached or counted; the app falls back on-device).
Non-streaming response → `200 {"ok":true,"text":"<Turkish>","quota":{...},"cached":false}`
Streaming (Accept: application/x-ndjson) → `200`, body = newline-delimited JSON objects:
  `{"d":"<text delta>"}` … then final `{"done":true,"text":"<full Turkish>","quota":{...},"cached":false}` (or `{"error":"<code>","message":"..."}` as the last line on mid-stream failure).
Errors (before streaming starts) → JSON as above with codes:
- `unauthorized` (401) — missing/invalid/expired session token, v1 token, or the token's plan was removed from `PLAN_LIMITS` → app calls /v1/session again once
- `quota_exceeded` (429) — the plan's line cap (+ top-up bonus) is reached; Turkish message names the plan, the reset date, (except Premium/trial) the next plan up and (paid plans) the top-up. The app then uses on-device ML Kit until `resetAt` and shows a one-time notice. Translation never stops.
- `bad_request` (400), `upstream_error` (502), `rate_limited` (429)

### POST /v1/topup  (v1.2)
Headers: `Authorization: Bearer <sessionToken>` (decides the quota bucket), `Content-Type: application/json`.
Request: `{"packageName":"com.altyazitr.oyun","productId":"ek_satir_2000","purchaseToken":"<from Play Billing, INAPP>"}`

Server verifies with Google Play Developer API **`purchases.products.get`**
(`GET https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{packageName}/purchases/products/{productId}/tokens/{token}`,
same service account; https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.products/get). `ProductPurchase` fields used
(https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.products):
- `purchaseState` (int): 0 Purchased · 1 Canceled (refunded/voided) · 2 Pending → only **0** is credited; 2 → `purchase_pending` (409).
- `consumptionState` (int): 0 yet to be consumed · 1 consumed → must be **0** (the app consumes only after the server credited it).
- `productId` (string, may be absent): if present must be `ek_satir_2000`. `quantity` (int, absent = 1): credit = 2 000 × quantity (clamped 1–10).
- `purchaseType` 0 (license test) is accepted like a real purchase.

Idempotency: `tu:<sha256(purchaseToken)>` (TTL 400 days) is written after crediting; a token already recorded returns
`200 {"ok":true,"credited":0,"alreadyCredited":true,"quota":{…}}` without asking Google (so the app can retry consuming).
Success → `200 {"ok":true,"credited":2000,"alreadyCredited":false,"quota":{"used":10000,"limit":12000,"resetAt":…,"bonus":2000}}`.
Credit is added to `q:<qb>:YYYY-MM:bonus` (trial: `q:<qb>:trial:bonus`) — valid until the end of that quota period; quota checks use cap + bonus.
Errors: `unauthorized` (401), `invalid_purchase` (400: wrong package/product, unknown/refunded/canceled/consumed token), `purchase_pending` (409),
`bad_request` (400), `rate_limited` (429), `server_error` (500/502).

App flow: the offer is shown only when the user hits the cap (text of the existing one-time cap notice) and on the plan screen when ≥ 80 % is used
— never as a pop-up during play. Purchase via Billing (`ProductType.INAPP`); after `/v1/topup` returns `ok` the app calls `consumeAsync`.
Unconsumed purchases are re-sent on the next Billing refresh (no money is lost if the app dies mid-way).

## Session token
Stateless, HMAC-SHA256 signed by server secret: base64url(payload).base64url(sig).
Payload v2: `{"sub":"<sha256 hex of purchaseToken, first 32>","exp":<unix ms, ≤ now+1h and ≤ expiryTime>,"v":2,"plan":"pro","qb":"<quota bucket, 32 hex>","te":<trial end unix ms, trial only>}`.
The server enforces the cap of `plan` looked up in `PLAN_LIMITS` at request time. v1 tokens (no plan) are rejected with 401.
The app treats the token as opaque and refreshes when `expiresAt` is < 2 min away or on 401 — so a trial→paid transition or a plan change is picked up within ≤ 1 h.

## Quota
- Counted in translated lines; cached hits on the server are free. Stored in Cloudflare KV (eventually consistent, approximate).
- Caps: env `PLAN_LIMITS`, default `{"standart":4500,"pro":10000,"premium":24000,"deneme":1500}`. Write the full list; a base plan missing from it (and not `<plan>-yillik` of a listed plan) is `invalid_purchase`; `deneme` falls back to 1500 if omitted; malformed JSON → defaults.
- Top-up bonus: `q:<qb>:YYYY-MM:bonus` (see `/v1/topup`); read only when usage ≥ 80 % of the cap (no extra KV read on normal requests).
- 80 % warning (app only, outside gameplay): once per quota period on the home screen and on the phone companion page; never during play.
- Keys: monthly `q:<qb>:YYYY-MM` (calendar month, UTC); trial `q:<qb>:trial` (whole trial, TTL 30 days). Trial usage does not count against the first paid month.
- Bucket `qb`: sha256(purchaseToken)[0:32], except when Google reports `linkedPurchaseToken`: then the bucket of the linked (older) token, looked up in KV `lk:<sha256(linkedToken)>` (falls back to sha256(linkedToken)[0:32]); the new mapping is stored as `lk:<sha256(newToken)>` (TTL 400 days). So within a month, used lines carry over across upgrades/downgrades/re-signups and are checked against the **new** plan's cap.
- Plan changes: the new purchase token is a new `sub` (new session, new verification cache entry); nothing else carries over (no credit of unused lines, no proration of lines). Play itself prorates money (upgrade `WITH_TIME_PRORATION`, downgrade `DEFERRED` → the old plan stays active until the period ends, so the server keeps reporting the old base plan until then).
  Order for upgrade/downgrade: tier first, then period (monthly < yearly within the same tier). Monthly → yearly of the same tier = upgrade (immediate); yearly → monthly = downgrade (at period end); a higher tier is an upgrade whatever the period. Top-up bonus lines stay with the bucket for the rest of the month.

## Translation cache (server)
- Key `tr:v2:<sha256("v2", model, speaker, canonical(text)[, canonical(last context en), last context tr])>`, TTL **90 days**; value = Turkish without speaker label.
- `canonical`: NFKC; curly → straight quotes; `…` → `...`; dashes → `-`; whitespace collapsed; stray leading/trailing OCR tokens (`| _ ~ • · * ^ = < > \ /` or a lone `.`) dropped;
  space before punctuation removed; `!!`/`??` collapsed; a single trailing `.`/`,` dropped (`?`, `!`, `...` kept); lowercased; all-caps lines keep a separate key (prefix `U|`).
- Context is part of the key only if it was actually sent to the model (context gate above).
- In front of KV, each isolate keeps an LRU of the 2 000 hottest lines.
- Metrics (no text): `st:YYYY-MM-DD` = `{"mem":<isolate LRU hits>,"kv":<KV hits>,"miss":<Gemini calls>}`, flushed at most once a minute per isolate, TTL 400 days.

## Privacy
Only subtitle text + (only when useful) up to 2 previous pairs are sent; short lines are not sent at all. No images, no device IDs. Server logs no subtitle text (only counts and error codes). Purchase tokens are stored only as SHA-256 hashes.
