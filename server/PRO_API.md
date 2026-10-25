# AltyazıTR Pro — sunucu ↔ uygulama sözleşmesi (v1)

Both the Cloudflare Worker (server/) and the Android app (subtitle/ + billing/) implement exactly this. Do not change it unilaterally.

## Constants
- Android package: `com.altyazitr.oyun`
- Play subscription product ID: `altyazitr_pro` (base plan `aylik`, monthly; free trial is configured as a Play offer, e.g. 7 days — the server treats trial like an active subscription).
- Base URL: configured in the app as BuildConfig `PRO_API_BASE_URL` (Gradle property `proApiBaseUrl`, default empty string = "Pro not configured yet").

## Endpoints (JSON, UTF-8, HTTPS)

### GET /v1/health
→ `200 {"ok":true,"version":"1"}`

### POST /v1/session
Request: `{"packageName":"com.altyazitr.oyun","productId":"altyazitr_pro","purchaseToken":"<from Play Billing>","appVersion":"1.2.3"}`
Server verifies the token with Google Play Developer API `purchases.subscriptionsv2.get` (service-account auth). Active states: `SUBSCRIPTION_STATE_ACTIVE`, `SUBSCRIPTION_STATE_IN_GRACE_PERIOD` (trial is ACTIVE). Anything else → not active.
Success → `200 {"ok":true,"sessionToken":"<opaque>","expiresAt":<unix ms>,"quota":{"used":123,"limit":30000,"resetAt":<unix ms>}}`
Errors → `{"ok":false,"code":"<code>","message":"<Turkish user-facing text>"}` with codes:
- `not_subscribed` (403) — token valid but not active/expired/cancelled-and-ended
- `invalid_purchase` (400) — unknown/forged token, wrong package/product
- `rate_limited` (429)
- `server_error` (500/502)

### POST /v1/translate
Headers: `Authorization: Bearer <sessionToken>`, `Content-Type: application/json`, optional `Accept: application/x-ndjson` for streaming.
Request: `{"text":"<English subtitle, ≤ 600 chars>","context":[{"en":"...","tr":"..."}],"speaker":null}` — context ≤ 4 pairs, each ≤ 600 chars.
Non-streaming response → `200 {"ok":true,"text":"<Turkish>","quota":{...},"cached":false}`
Streaming (Accept: application/x-ndjson) → `200`, body = newline-delimited JSON objects:
  `{"d":"<text delta>"}` … then final `{"done":true,"text":"<full Turkish>","quota":{...},"cached":false}` (or `{"error":"<code>","message":"..."}` as the last line on mid-stream failure).
Errors (before streaming starts) → JSON as above with codes:
- `unauthorized` (401) — missing/invalid/expired session token → app calls /v1/session again once
- `quota_exceeded` (429) — monthly line limit reached; message tells when it resets
- `bad_request` (400), `upstream_error` (502), `rate_limited` (429)

## Session token
Stateless, HMAC-SHA256 signed by server secret: base64url(payload).base64url(sig), payload `{"sub":"<sha256 hex of purchaseToken, first 32 chars>","exp":<unix ms, now+1h>,"v":1}`. The app treats it as opaque and refreshes when `expiresAt` is < 2 min away or on 401.

## Quota
Counted in translated lines per subscriber (`sub`) per calendar month (UTC), limit from env `MONTHLY_LINE_LIMIT` (default 30000). Cached hits on the server are free (do not count). Stored in Cloudflare KV.

## Privacy
Only subtitle text + up to 4 previous pairs are sent. No images, no device IDs. Server logs no subtitle text (only counts and error codes).
