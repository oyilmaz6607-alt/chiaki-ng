# AltyazıTR abonelik — sunucu ↔ uygulama sözleşmesi (v1.1)

Both the Cloudflare Worker (server/) and the Android app (subtitle/ + billing/) implement exactly this. Do not change it unilaterally.
v1.1 adds tiered plans (Standart / Pro / Premium) and a 3-day free trial. Paths stay under `/v1`.

## Constants
- Android package: `com.altyazitr.oyun`
- Play subscription product ID: **`altyazitr_abonelik`** (replaces `altyazitr_pro` of v1) with three monthly auto-renewing base plans:

| Base plan ID | Name shown | Hours/month shown in the app | Monthly AI line cap (default) |
|---|---|---|---|
| `standart` | Standart | ~10 saat | 4 500 |
| `pro` | Pro | ~25 saat | 11 000 |
| `premium` | Premium | ~60 saat | 27 000 |

  Assumption behind the hours: ~450 subtitle lines per hour of dialogue-heavy play.
- Free trial: an offer with ID **`deneme-3gun`** on every base plan (one 3-day free-trial phase, eligibility "new customer acquisition: never had any subscription"). During the trial the plan is `deneme` with **1 500 lines in total** for the whole trial (not per month).
- Prices are set in Play Console only; the app shows Play's formatted price. Yearly base plans are out of scope for v1.1 (if added later, give them their own base plan IDs and add them to `PLAN_LIMITS`).
- Base URL: configured in the app as BuildConfig `PRO_API_BASE_URL` (Gradle property `proApiBaseUrl`, default empty string = "subscription not configured yet").
- Caps live in ONE place per side: server env `PLAN_LIMITS` (JSON, see Quota) — the server is authoritative; the app's `billing/BillingLogic.kt → Plans` holds the display copy (hours + lines) and must be kept in sync by hand.

## Endpoints (JSON, UTF-8, HTTPS)

### GET /v1/health
→ `200 {"ok":true,"version":"1.1"}`

### POST /v1/session
Request: `{"packageName":"com.altyazitr.oyun","productId":"altyazitr_abonelik","purchaseToken":"<from Play Billing>","appVersion":"1.2.3"}`

Server verifies the token with Google Play Developer API `purchases.subscriptionsv2.get`
(`GET https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{packageName}/purchases/subscriptionsv2/tokens/{token}`, service-account auth).
Active states: `SUBSCRIPTION_STATE_ACTIVE`, `SUBSCRIPTION_STATE_IN_GRACE_PERIOD` (a trial is ACTIVE). Anything else → not active.

Plan resolution (fields of `SubscriptionPurchaseV2`):
- Of `lineItems[]` with `productId == "altyazitr_abonelik"` and `expiryTime` in the future, the one with the latest `expiryTime` is used.
- Base plan = that line item's `offerDetails.basePlanId`. Must be a key of `PLAN_LIMITS` (and not `deneme`), else → `invalid_purchase`.
- Trial = `lineItems[].offerPhase.freeTrial` is set (OfferPhase union: `freeTrial` | `introductoryPrice` | `prorationPeriod` | `basePrice`).
  Fallback only when `offerPhase` is absent: `offerDetails.offerId == "deneme-3gun"` AND the subscription's `startTime` is less than 3 days ago.
- Quota bucket: `linkedPurchaseToken` (set on upgrade/downgrade, re-signup of a cancelled-but-not-lapsed subscription, etc.) links the new token to the old one — see Quota.

Success →
```
200 {"ok":true,"sessionToken":"<opaque>","expiresAt":<unix ms>,
     "plan":"pro","planName":"Pro","basePlan":"pro","basePlanName":"Pro","trial":false,
     "quota":{"used":123,"limit":11000,"resetAt":<unix ms>}}
```
- `plan` is `"standart"|"pro"|"premium"|"deneme"`; during the trial `plan:"deneme"`, `trial:true` and `basePlan` is the base plan the trial converts into.
- `quota.resetAt` = start of next calendar month (UTC); during the trial = trial end (`expiryTime`).

Errors → `{"ok":false,"code":"<code>","message":"<Turkish user-facing text>"}` with codes:
- `not_subscribed` (403) — token valid but not active/expired/cancelled-and-ended
- `invalid_purchase` (400) — unknown/forged token, wrong package/product, **unknown base plan**
- `rate_limited` (429)
- `server_error` (500/502)

### POST /v1/translate
Headers: `Authorization: Bearer <sessionToken>`, `Content-Type: application/json`, optional `Accept: application/x-ndjson` for streaming.
Request: `{"text":"<English subtitle, ≤ 600 chars>","context":[{"en":"...","tr":"..."}],"speaker":null}` — context ≤ 4 pairs, each ≤ 600 chars.
Non-streaming response → `200 {"ok":true,"text":"<Turkish>","quota":{...},"cached":false}`
Streaming (Accept: application/x-ndjson) → `200`, body = newline-delimited JSON objects:
  `{"d":"<text delta>"}` … then final `{"done":true,"text":"<full Turkish>","quota":{...},"cached":false}` (or `{"error":"<code>","message":"..."}` as the last line on mid-stream failure).
Errors (before streaming starts) → JSON as above with codes:
- `unauthorized` (401) — missing/invalid/expired session token, v1 token, or the token's plan was removed from `PLAN_LIMITS` → app calls /v1/session again once
- `quota_exceeded` (429) — the plan's line cap is reached; Turkish message names the plan, the reset date and (except Premium/trial) the next plan up. The app then uses on-device ML Kit until `resetAt` and shows a one-time notice.
- `bad_request` (400), `upstream_error` (502), `rate_limited` (429)

## Session token
Stateless, HMAC-SHA256 signed by server secret: base64url(payload).base64url(sig).
Payload v2: `{"sub":"<sha256 hex of purchaseToken, first 32>","exp":<unix ms, ≤ now+1h and ≤ expiryTime>,"v":2,"plan":"pro","qb":"<quota bucket, 32 hex>","te":<trial end unix ms, trial only>}`.
The server enforces the cap of `plan` looked up in `PLAN_LIMITS` at request time. v1 tokens (no plan) are rejected with 401.
The app treats the token as opaque and refreshes when `expiresAt` is < 2 min away or on 401 — so a trial→paid transition or a plan change is picked up within ≤ 1 h.

## Quota
- Counted in translated lines; cached hits on the server are free. Stored in Cloudflare KV (eventually consistent, approximate).
- Caps: env `PLAN_LIMITS`, default `{"standart":4500,"pro":11000,"premium":27000,"deneme":1500}`. Write the full list; a base plan missing from it is `invalid_purchase`; `deneme` falls back to 1500 if omitted; malformed JSON → defaults.
- Keys: monthly `q:<qb>:YYYY-MM` (calendar month, UTC); trial `q:<qb>:trial` (whole trial, TTL 30 days). Trial usage does not count against the first paid month.
- Bucket `qb`: sha256(purchaseToken)[0:32], except when Google reports `linkedPurchaseToken`: then the bucket of the linked (older) token, looked up in KV `lk:<sha256(linkedToken)>` (falls back to sha256(linkedToken)[0:32]); the new mapping is stored as `lk:<sha256(newToken)>` (TTL 400 days). So within a month, used lines carry over across upgrades/downgrades/re-signups and are checked against the **new** plan's cap.
- Plan changes: the new purchase token is a new `sub` (new session, new verification cache entry); nothing else carries over (no credit of unused lines, no proration of lines). Play itself prorates money (upgrade `WITH_TIME_PRORATION`, downgrade `DEFERRED` → the old plan stays active until the period ends, so the server keeps reporting the old base plan until then).

## Privacy
Only subtitle text + up to 4 previous pairs are sent. No images, no device IDs. Server logs no subtitle text (only counts and error codes). Purchase tokens are stored only as SHA-256 hashes.
