// SPDX-License-Identifier: AGPL-3.0-only
//
// Bağımlılıksız testler: `node server/test/run.mjs` (Node 22+).
// Sahte KV (Map) ve sahte global fetch (Google OAuth, androidpublisher, Gemini) kullanır.

import assert from "node:assert/strict";
import worker, * as S from "../src/index.js";

// ---------------------------------------------------------------- sahte ortam

class FakeKV {
	constructor() { this.m = new Map(); this.puts = []; this.gets = 0; }
	async get(k) { this.gets++; const e = this.m.get(k); if (!e) return null; if (e.exp && e.exp < Date.now()) { this.m.delete(k); return null; } return e.v; }
	async put(k, v, o = {}) { if (typeof v !== "string") throw new Error("KV value must be string"); this.puts.push({ k, v, o }); this.m.set(k, { v, exp: o.expirationTtl ? Date.now() + o.expirationTtl * 1000 : 0 }); }
}

const keyPair = await crypto.subtle.generateKey(
	{ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"]);
const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", keyPair.privateKey));
const pem = "-----BEGIN PRIVATE KEY-----\n" + Buffer.from(pkcs8).toString("base64").match(/.{1,64}/g).join("\n") + "\n-----END PRIVATE KEY-----\n";
const SA = JSON.stringify({ type: "service_account", client_email: "play@test.iam.gserviceaccount.com", private_key: pem, token_uri: "https://evil.example/token" });

const SECRET = "test-session-secret-0123456789abcdef";
const GEMINI_KEY = "AIza-test-key";

function makeEnv(over = {}) {
	return { ALTYAZI_KV: new FakeKV(), GEMINI_API_KEY: GEMINI_KEY, SESSION_SECRET: SECRET, GOOGLE_SERVICE_ACCOUNT_JSON: SA,
		GEMINI_MODEL: "gemini-3.5-flash-lite", MAX_TEXT_CHARS: "600", UPSTREAM_TIMEOUT_MS: "150", ...over };
}
function makeCtx() { const ps = []; return { waitUntil: (p) => ps.push(p), all: () => Promise.all(ps) }; }

const FUTURE = () => new Date(Date.now() + 20 * 24 * 3600e3).toISOString();
const PAST = () => new Date(Date.now() - 3600e3).toISOString();

/** Sahte fetch durumu (her testte sıfırlanır). */
let F;
function resetFetch() {
	F = {
		calls: [],
		oauthCalls: 0,
		/** token → {status, body} */
		purchases: {},
		/** tek seferlik ürün: token → {status, body} (purchases.products.get) */
		products: {},
		productCalls: 0,
		/** (req, bodyObj, n) => Response */
		gemini: () => geminiJson("Merhaba dünya."),
	};
}
function geminiJson(text, extra = {}) {
	return new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: "thinking...", thought: true }, { text }] }, finishReason: "STOP" }], ...extra }), { status: 200, headers: { "content-type": "application/json" } });
}
/** SSE gövdesi: verilen ham parçalar sırayla (gecikmeli) gönderilir; hang=true ise sonda asılı kalır. */
function sseResponse(chunks, { hang = false, signal } = {}) {
	const te = new TextEncoder();
	const rs = new ReadableStream({
		async start(c) {
			for (const ch of chunks) { await new Promise((r) => setTimeout(r, 5)); c.enqueue(te.encode(ch)); }
			if (hang) { signal?.addEventListener("abort", () => c.error(new Error("aborted"))); return; }
			c.close();
		},
	});
	return new Response(rs, { status: 200, headers: { "content-type": "text/event-stream" } });
}
const sseEvent = (obj) => "data: " + JSON.stringify(obj) + "\r\n\r\n";
const sseText = (t, thought = false) => sseEvent({ candidates: [{ content: { role: "model", parts: [thought ? { text: t, thought: true } : { text: t }] } }] });

globalThis.fetch = async (input, init = {}) => {
	const req = new Request(input, init);
	const url = new URL(req.url);
	const bodyText = init.body ? String(init.body) : "";
	F.calls.push({ url: req.url, method: req.method, headers: req.headers, body: bodyText });
	if (url.href === "https://oauth2.googleapis.com/token") {
		F.oauthCalls++;
		const p = new URLSearchParams(bodyText);
		assert.equal(p.get("grant_type"), "urn:ietf:params:oauth:grant-type:jwt-bearer");
		const [h, c, s] = p.get("assertion").split(".");
		const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", keyPair.publicKey, S.b64urlDecode(s), new TextEncoder().encode(h + "." + c));
		assert.ok(ok, "JWT signature valid");
		const claims = JSON.parse(Buffer.from(c, "base64url").toString());
		assert.equal(claims.aud, "https://oauth2.googleapis.com/token");
		assert.equal(claims.scope, "https://www.googleapis.com/auth/androidpublisher");
		assert.equal(claims.iss, "play@test.iam.gserviceaccount.com");
		assert.equal(JSON.parse(Buffer.from(h, "base64url").toString()).alg, "RS256");
		return Response.json({ access_token: "ya29.fake", expires_in: 3599, token_type: "Bearer" });
	}
	const mp = /^\/androidpublisher\/v3\/applications\/([^/]+)\/purchases\/subscriptionsv2\/tokens\/([^/]+)$/.exec(url.pathname);
	if (url.host === "androidpublisher.googleapis.com" && mp) {
		assert.equal(req.headers.get("authorization"), "Bearer ya29.fake");
		assert.equal(decodeURIComponent(mp[1]), "com.altyazitr.oyun");
		const p = F.purchases[decodeURIComponent(mp[2])];
		if (!p) return Response.json({ error: { code: 404, message: "not found" } }, { status: 404 });
		return Response.json(p.body ?? {}, { status: p.status ?? 200 });
	}
	const pp = /^\/androidpublisher\/v3\/applications\/([^/]+)\/purchases\/products\/([^/]+)\/tokens\/([^/]+)$/.exec(url.pathname);
	if (url.host === "androidpublisher.googleapis.com" && pp) {
		F.productCalls++;
		assert.equal(req.headers.get("authorization"), "Bearer ya29.fake");
		assert.equal(decodeURIComponent(pp[1]), "com.altyazitr.oyun");
		const p = F.products[decodeURIComponent(pp[2]) + "/" + decodeURIComponent(pp[3])];
		if (!p) return Response.json({ error: { code: 404, message: "not found" } }, { status: 404 });
		return Response.json(p.body ?? {}, { status: p.status ?? 200 });
	}
	if (url.host === "generativelanguage.googleapis.com") {
		assert.equal(req.headers.get("x-goog-api-key"), GEMINI_KEY);
		const n = F.calls.filter((c) => c.url.includes("generativelanguage")).length;
		return F.gemini(req, JSON.parse(bodyText), n, init.signal, url);
	}
	throw new Error("unexpected fetch " + req.url);
};

/**
 * SubscriptionPurchaseV2 (alan adları: developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2).
 * Varsayılan: "pro" temel planı, ücretli (basePrice) aşama.
 */
function activeSub(productId = "altyazitr_abonelik", state = "SUBSCRIPTION_STATE_ACTIVE", expiry = FUTURE(),
	{ basePlanId = "pro", offerId, offerPhase = { basePrice: {} }, linkedPurchaseToken, startTime = new Date(Date.now() - 10 * 24 * 3600e3).toISOString(), extraItems = [] } = {}) {
	const li = { productId, expiryTime: expiry, autoRenewingPlan: { autoRenewEnabled: true }, offerDetails: { basePlanId, offerTags: [] } };
	if (offerId !== undefined) li.offerDetails.offerId = offerId;
	if (offerPhase !== null) li.offerPhase = offerPhase;
	const body = { kind: "androidpublisher#subscriptionPurchaseV2", startTime, subscriptionState: state, acknowledgementState: "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED", lineItems: [...extraItems, li] };
	if (linkedPurchaseToken) body.linkedPurchaseToken = linkedPurchaseToken;
	return { status: 200, body };
}

// ---------------------------------------------------------------- istek yardımcıları

const BASE = "https://api.test";
let ipN = 1;
async function call(env, method, path, { body, headers = {}, raw } = {}) {
	const ctx = makeCtx();
	const init = { method, headers: { "cf-connecting-ip": "10.0." + (ipN >> 8 & 255) + "." + (ipN++ & 255), ...headers } };
	if (raw !== undefined) init.body = raw;
	else if (body !== undefined) { init.body = JSON.stringify(body); init.headers["content-type"] ??= "application/json"; }
	const res = await worker.fetch(new Request(BASE + path, init), env, ctx);
	const text = await res.text();
	await ctx.all();
	let j = null;
	try { j = JSON.parse(text); } catch {}
	return { status: res.status, ct: res.headers.get("content-type"), text, j, lines: text.split("\n").filter(Boolean).map((l) => JSON.parse(l)) };
}
const sessionBody = (purchaseToken) => ({ packageName: "com.altyazitr.oyun", productId: "altyazitr_abonelik", purchaseToken, appVersion: "1.0.0" });

async function newSession(env, token = "tok_" + Math.random().toString(36).slice(2) + "_abcdefgh", opts = {}) {
	F.purchases[token] = activeSub(undefined, undefined, undefined, opts);
	const r = await call(env, "POST", "/v1/session", { body: sessionBody(token) });
	assert.equal(r.status, 200, r.text);
	return r.j.sessionToken;
}
const tr = (env, token, body, stream = false) =>
	call(env, "POST", "/v1/translate", { body, headers: { authorization: "Bearer " + token, ...(stream ? { accept: "application/x-ndjson" } : {}) } });

// ---------------------------------------------------------------- mini koşucu

const tests = [];
const test = (name, fn) => tests.push({ name, fn });
const logs = [];
const origLog = console.log;

// ---------------------------------------------------------------- testler

test("health", async () => {
	const r = await call(makeEnv(), "GET", "/v1/health");
	assert.equal(r.status, 200);
	assert.deepEqual(r.j, { ok: true, version: "1.2" });
});

test("routing: preflight 405, wrong method 405, unknown 404", async () => {
	const env = makeEnv();
	assert.equal((await call(env, "OPTIONS", "/v1/translate", { headers: { origin: "https://x", "access-control-request-method": "POST" } })).status, 405);
	assert.equal((await call(env, "GET", "/v1/session")).status, 405);
	assert.equal((await call(env, "POST", "/v1/health")).status, 405);
	const r = await call(env, "GET", "/nope");
	assert.equal(r.status, 404);
	assert.equal(r.j.ok, false);
});

test("session: active subscription → token, quota, expiresAt; JWT valid; access token cached", async () => {
	const env = makeEnv();
	F.purchases["tok_active_1234567"] = activeSub();
	const r = await call(env, "POST", "/v1/session", { body: sessionBody("tok_active_1234567") });
	assert.equal(r.status, 200, r.text);
	assert.equal(r.j.ok, true);
	assert.match(r.j.sessionToken, /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/);
	assert.ok(r.j.expiresAt > Date.now() + 59 * 60e3 && r.j.expiresAt <= Date.now() + 60 * 60e3);
	assert.deepEqual(Object.keys(r.j.quota).sort(), ["limit", "resetAt", "used"]);
	assert.equal(r.j.quota.used, 0);
	assert.equal(r.j.quota.limit, 10000);
	assert.equal(r.j.quota.resetAt, S.nextMonthStart(Date.now()));
	assert.equal(r.j.plan, "pro");
	assert.equal(r.j.planName, "Pro");
	assert.equal(r.j.basePlan, "pro");
	assert.equal(r.j.trial, false);
	const payload = JSON.parse(Buffer.from(r.j.sessionToken.split(".")[0], "base64url").toString());
	assert.equal(payload.v, 2);
	assert.equal(payload.plan, "pro");
	assert.equal(payload.sub, (await S.sha256Hex("tok_active_1234567")).slice(0, 32));
	assert.equal(payload.qb, payload.sub);
	assert.equal(payload.te, undefined);
	assert.equal(F.oauthCalls, 1);
	// İkinci, farklı jeton: OAuth tekrar çağrılmaz (bellek önbelleği)
	F.purchases["tok_active_2234567"] = activeSub();
	assert.equal((await call(env, "POST", "/v1/session", { body: sessionBody("tok_active_2234567") })).status, 200);
	assert.equal(F.oauthCalls, 1);
	// Bellek boşaltılınca KV'den okunur
	S._resetState();
	F.purchases["tok_active_3234567"] = activeSub();
	assert.equal((await call(env, "POST", "/v1/session", { body: sessionBody("tok_active_3234567") })).status, 200);
	assert.equal(F.oauthCalls, 1);
	assert.ok(env.ALTYAZI_KV.puts.some((p) => p.k === "gtoken:v1"));
});

test("session: grace period accepted; expired/canceled/paused/on-hold rejected", async () => {
	const env = makeEnv();
	F.purchases["tok_grace_1234567"] = activeSub("altyazitr_abonelik", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD");
	assert.equal((await call(env, "POST", "/v1/session", { body: sessionBody("tok_grace_1234567") })).status, 200);
	for (const st of ["SUBSCRIPTION_STATE_EXPIRED", "SUBSCRIPTION_STATE_CANCELED", "SUBSCRIPTION_STATE_PAUSED", "SUBSCRIPTION_STATE_ON_HOLD", "SUBSCRIPTION_STATE_PENDING"]) {
		const t = "tok_" + st.toLowerCase();
		F.purchases[t] = activeSub("altyazitr_abonelik", st);
		const r = await call(env, "POST", "/v1/session", { body: sessionBody(t) });
		assert.equal(r.status, 403, st);
		assert.equal(r.j.code, "not_subscribed");
		assert.ok(r.j.message.length > 0);
	}
});

test("session: active state but expiryTime in past → not_subscribed", async () => {
	const env = makeEnv();
	F.purchases["tok_pastexp_12345"] = activeSub("altyazitr_abonelik", "SUBSCRIPTION_STATE_ACTIVE", PAST());
	const r = await call(env, "POST", "/v1/session", { body: sessionBody("tok_pastexp_12345") });
	assert.equal(r.status, 403);
	assert.equal(r.j.code, "not_subscribed");
});

test("session: wrong product in lineItems → not_subscribed", async () => {
	const env = makeEnv();
	F.purchases["tok_wrongprod_123"] = activeSub("other_product");
	const r = await call(env, "POST", "/v1/session", { body: sessionBody("tok_wrongprod_123") });
	assert.equal(r.status, 403);
	assert.equal(r.j.code, "not_subscribed");
});

test("session: unknown token (Google 404) → invalid_purchase, cached", async () => {
	const env = makeEnv();
	const r = await call(env, "POST", "/v1/session", { body: sessionBody("tok_unknown_12345") });
	assert.equal(r.status, 400);
	assert.equal(r.j.code, "invalid_purchase");
	const n = F.calls.length;
	assert.equal((await call(env, "POST", "/v1/session", { body: sessionBody("tok_unknown_12345") })).status, 400);
	assert.equal(F.calls.length, n, "served from KV cache");
});

test("session: verification cached in KV (10 min) keyed by sha256(token)", async () => {
	const env = makeEnv();
	F.purchases["tok_cache_1234567"] = activeSub();
	await call(env, "POST", "/v1/session", { body: sessionBody("tok_cache_1234567") });
	const put = env.ALTYAZI_KV.puts.find((p) => p.k.startsWith("pv:v2:"));
	assert.equal(put.k, "pv:v2:" + (await S.sha256Hex("tok_cache_1234567")));
	assert.equal(put.o.expirationTtl, 600);
	assert.ok(!put.k.includes("tok_cache"), "raw purchase token not stored in key");
	const n = F.calls.length;
	assert.equal((await call(env, "POST", "/v1/session", { body: sessionBody("tok_cache_1234567") })).status, 200);
	assert.equal(F.calls.length, n);
});

test("session: wrong package/product → invalid_purchase; malformed → bad_request", async () => {
	const env = makeEnv();
	let r = await call(env, "POST", "/v1/session", { body: { ...sessionBody("tok_x_1234567890"), packageName: "com.evil" } });
	assert.equal(r.status, 400); assert.equal(r.j.code, "invalid_purchase");
	r = await call(env, "POST", "/v1/session", { body: { ...sessionBody("tok_x_1234567890"), productId: "free" } });
	assert.equal(r.j.code, "invalid_purchase");
	r = await call(env, "POST", "/v1/session", { body: sessionBody("bad token with spaces") });
	assert.equal(r.j.code, "invalid_purchase");
	r = await call(env, "POST", "/v1/session", { body: { packageName: "com.altyazitr.oyun" } });
	assert.equal(r.j.code, "bad_request");
	r = await call(env, "POST", "/v1/session", { raw: "{not json", headers: { "content-type": "application/json" } });
	assert.equal(r.status, 400); assert.equal(r.j.code, "bad_request");
	r = await call(env, "POST", "/v1/session", { raw: JSON.stringify(sessionBody("tok_x_1234567890")), headers: { "content-type": "text/plain" } });
	assert.equal(r.j.code, "bad_request");
	r = await call(env, "POST", "/v1/session", { raw: "[1,2]", headers: { "content-type": "application/json" } });
	assert.equal(r.j.code, "bad_request");
	r = await call(env, "POST", "/v1/session", { raw: JSON.stringify({ ...sessionBody("tok_x_1234567890"), pad: "x".repeat(9000) }), headers: { "content-type": "application/json" } });
	assert.equal(r.status, 400); assert.equal(r.j.code, "bad_request");
	assert.equal(F.calls.length, 0, "no upstream calls for invalid input");
});

test("session: Google 5xx / missing service account → server_error, no secrets echoed", async () => {
	let env = makeEnv();
	F.purchases["tok_g500_1234567"] = { status: 503, body: { error: { message: "backend" } } };
	let r = await call(env, "POST", "/v1/session", { body: sessionBody("tok_g500_1234567") });
	assert.equal(r.status, 502); assert.equal(r.j.code, "server_error");
	S._resetState();
	env = makeEnv({ GOOGLE_SERVICE_ACCOUNT_JSON: "" });
	r = await call(env, "POST", "/v1/session", { body: sessionBody("tok_nosa_12345678") });
	assert.equal(r.status, 500); assert.equal(r.j.code, "server_error");
	assert.ok(!r.text.includes(SECRET) && !r.text.includes("PRIVATE"));
});

test("session: per-IP rate limit", async () => {
	const env = makeEnv();
	let limited = 0;
	for (let i = 0; i < 10; i++) {
		const r = await call(env, "POST", "/v1/session", { body: sessionBody("tok_unknown_rl_" + i), headers: { "cf-connecting-ip": "1.2.3.4" } });
		if (r.status === 429) { limited++; assert.equal(r.j.code, "rate_limited"); }
	}
	assert.ok(limited >= 3, "limited " + limited);
});

test("translate: auth failures → 401 (missing, garbage, tampered, wrong secret, expired)", async () => {
	const env = makeEnv();
	const tok = await newSession(env);
	const body = { text: "Hello", context: [] };
	assert.equal((await call(env, "POST", "/v1/translate", { body })).status, 401);
	assert.equal((await tr(env, "garbage", body)).j.code, "unauthorized");
	const [p, s] = tok.split(".");
	const payload = JSON.parse(Buffer.from(p, "base64url").toString());
	const forged = Buffer.from(JSON.stringify({ ...payload, sub: "f".repeat(32) })).toString("base64url") + "." + s;
	assert.equal((await tr(env, forged, body)).status, 401);
	const flipped = p + "." + (s[0] === "A" ? "B" : "A") + s.slice(1);
	assert.equal((await tr(env, flipped, body)).status, 401);
	const other = await S.signSession("another-secret-0123456789abcdef", payload.sub, Date.now() + 60e3);
	assert.equal((await tr(env, other, body)).status, 401);
	const expired = await S.signSession(SECRET, payload.sub, Date.now() - 1);
	assert.equal((await tr(env, expired, body)).status, 401);
	const badSub = await S.signSession(SECRET, "not-hex", Date.now() + 60e3);
	assert.equal((await tr(env, badSub, body)).status, 401);
	assert.equal((await tr(env, tok, body)).status, 200);
});

test("verifySession unit", async () => {
	const sub = "a".repeat(32);
	const t = await S.signSession(SECRET, sub, Date.now() + 1000);
	assert.deepEqual(await S.verifySession(SECRET, t, Date.now()), { sub, exp: JSON.parse(Buffer.from(t.split(".")[0], "base64url")).exp, plan: "standart", qb: sub });
	const t2 = await S.signSession(SECRET, sub, Date.now() + 1000, "deneme", "c".repeat(32), 123);
	assert.deepEqual({ ...(await S.verifySession(SECRET, t2, Date.now())), exp: 0 }, { sub, exp: 0, plan: "deneme", qb: "c".repeat(32), te: 123 });
	assert.equal(await S.verifySession(SECRET, t + ".x", Date.now()), null);
	assert.equal(await S.verifySession(SECRET, t, Date.now() + 2000), null);
});

test("translate non-stream: request shape, cleaned output, quota counted", async () => {
	const env = makeEnv();
	const tok = await newSession(env);
	let seen;
	F.gemini = (req, body, n, sig, url) => { seen = { body, url }; return geminiJson("\"TR: Merhaba, dostum.\""); };
	const r = await tr(env, tok, { text: "  Hello,\n  buddy. ", context: [{ en: "Who are you?", tr: "Sen kimsin?" }], speaker: "Joel" });
	assert.equal(r.status, 200, r.text);
	assert.equal(r.j.ok, true);
	assert.equal(r.j.text, "Merhaba, dostum.");
	assert.equal(r.j.cached, false);
	assert.equal(r.j.quota.used, 1);
	assert.equal(r.j.quota.limit, 10000);
	assert.equal(r.j.quota.resetAt, S.nextMonthStart(Date.now()));
	assert.ok(seen.url.pathname.endsWith("/models/gemini-3.5-flash-lite:generateContent"));
	// 2 kelime → clamp(2×2+24, 32, 160) = 32
	assert.deepEqual(seen.body.generationConfig, { maxOutputTokens: 32, candidateCount: 1, thinkingConfig: { thinkingLevel: "minimal" } });
	assert.equal(seen.body.systemInstruction.parts[0].text, S.SYSTEM_PROMPT);
	let u = seen.body.contents[0].parts[0].text;
	assert.ok(!u.includes("Previous lines"), "short line: no context sent to the model");
	assert.ok(u.includes("Speaker: Joel"));
	assert.ok(u.endsWith("LINE: Hello, buddy."));
	assert.ok(env.ALTYAZI_KV.puts.some((p) => p.k.startsWith("tr:v2:") && p.v === "Merhaba, dostum." && p.o.expirationTtl === 90 * 24 * 3600));
	// Uzun, gönderme içeren satır: yalnızca son 2 çift, her biri 160 karaktere kırpılmış.
	const ctx4 = [1, 2, 3, 4].map((i) => ({ en: "Line " + i + " " + "x".repeat(300), tr: "Satır " + i }));
	await tr(env, tok, { text: "I told you we should never have trusted them", context: ctx4 });
	u = seen.body.contents[0].parts[0].text;
	assert.ok(!u.includes("Line 1") && !u.includes("Line 2"));
	assert.ok(u.includes("EN: Line 3 " + "x".repeat(160 - 7) + "\nTR: Satır 3"));
	assert.ok(!u.includes("x".repeat(160 - 6)));
	assert.ok(u.includes("TR: Satır 4"));
	assert.equal(seen.body.generationConfig.maxOutputTokens, 2 * 9 + 24);
	const q = env.ALTYAZI_KV.puts.find((p) => p.k.startsWith("q:"));
	assert.equal(q.v, "1");
	assert.match(q.k, /^q:[0-9a-f]{32}:\d{4}-\d{2}$/);
});

test("translate: cache hit → cached:true, no upstream, no quota", async () => {
	const env = makeEnv();
	const tok = await newSession(env);
	await tr(env, tok, { text: "Press X to continue", context: [] });
	const n = F.calls.length;
	const r = await tr(env, tok, { text: "Press  X to continue", context: [{ en: "a", tr: "b" }] });
	assert.equal(r.status, 200);
	assert.equal(r.j.cached, true);
	assert.equal(r.j.text, "Merhaba dünya.");
	assert.equal(r.j.quota.used, 1);
	assert.equal(F.calls.length, n);
	// Akışlı istemci: tek son satır
	const s = await tr(env, tok, { text: "Press X to continue" }, true);
	assert.equal(s.ct, "application/x-ndjson; charset=utf-8");
	assert.equal(s.lines.length, 1);
	assert.deepEqual(s.lines[0], { done: true, text: "Merhaba dünya.", quota: s.lines[0].quota, cached: true });
});

test("cache key: effective context only, model/speaker separate", async () => {
	const m = "gemini-3.5-flash-lite";
	const c1 = [{ en: "a", tr: "b" }], c2 = [{ en: "c", tr: "d" }];
	const key = (t, c, sp = null, model = m) => S.cacheKey(model, t, S.effectiveContext(t, c), sp);
	assert.equal(await key("Open the door now", c1), await key("Open the door now", c2));
	// Uzun ama gönderme yok → bağlam gönderilmez, anahtar aynı.
	const plain = "Welcome to the old harbor district of Boston";
	assert.equal(await key(plain, c1), await key(plain, c2));
	const long = "I told you we should never have come back to this town";
	assert.notEqual(await key(long, c1), await key(long, c2));
	assert.equal(await key(long, []), await key(long, []));
	assert.notEqual(await key("Hi", [], "A"), await key("Hi", [], "B"));
	assert.notEqual(await key("Hi", [], null, "gemini-3.6-flash"), await key("Hi", [], null));
});

test("cache key normalization: case, quotes, ellipsis, spacing, punctuation, OCR noise", async () => {
	const m = "gemini-3.5-flash-lite";
	const k = (t) => S.cacheKey(m, t, [], null);
	const base = await k("Don't go there... it's not safe!");
	for (const v of ["don’t go there… it’s not safe!", "Don't  go there ... it's not safe!!", "Don't go there... it's not safe! |",
		"| Don't go there... it's not safe!", "DON'T go there... it's not safe!"])
		assert.equal(await k(v), base, v);
	assert.equal(await k("We should go."), await k("We should go"));
	assert.equal(await k("We should go,"), await k("We should go"));
	assert.notEqual(await k("We should go?"), await k("We should go"), "question mark changes meaning");
	assert.notEqual(await k("We should go..."), await k("We should go"), "trailing ellipsis kept");
	assert.notEqual(await k("PRESS X TO CONTINUE"), await k("Press X to continue"), "all-caps kept separate");
	assert.equal(S.canonicalKeyText("“Hello” — she said…"), 'n|"hello" - she said...');
});

test("speaker label: stripped from key/prompt, re-attached to result (cache shared across speakers)", async () => {
	assert.deepEqual(S.splitSpeakerLabel("JOEL: Get down!"), { label: "JOEL:", body: "Get down!" });
	assert.deepEqual(S.splitSpeakerLabel("MARY JANE: Over here."), { label: "MARY JANE:", body: "Over here." });
	assert.deepEqual(S.splitSpeakerLabel("Note: this is mixed case"), { label: "", body: "Note: this is mixed case" });
	assert.deepEqual(S.splitSpeakerLabel("A: b"), { label: "", body: "A: b" });
	assert.deepEqual(S.splitSpeakerLabel("10: 30"), { label: "", body: "10: 30" });
	const env = makeEnv();
	const tok = await newSession(env);
	let prompt;
	F.gemini = (req, body) => { prompt = body.contents[0].parts[0].text; return geminiJson("Eğil!"); };
	const a = await tr(env, tok, { text: "JOEL: Get down!" });
	assert.equal(a.j.text, "JOEL: Eğil!");
	assert.ok(prompt.endsWith("LINE: Get down!") && !prompt.includes("JOEL"));
	const n = F.calls.length;
	const b = await tr(env, tok, { text: "ELLIE: Get down!" });
	assert.equal(b.j.cached, true);
	assert.equal(b.j.text, "ELLIE: Eğil!");
	assert.equal((await tr(env, tok, { text: "Get down!" })).j.text, "Eğil!");
	assert.equal(F.calls.length, n);
	assert.ok(env.ALTYAZI_KV.puts.some((p) => p.k.startsWith("tr:v2:") && p.v === "Eğil!"), "stored without label");
	// Akış: etiket ilk parça olarak gelir.
	F.gemini = () => sseResponse([sseText("Koş, hemen!")]);
	const s = await tr(env, tok, { text: "TESS: Run, now!" }, true);
	assert.equal(s.lines[0].d, "TESS: ");
	assert.equal(s.lines.at(-1).text, "TESS: Koş, hemen!");
	assert.equal(s.lines.filter((l) => "d" in l).map((l) => l.d).join(""), "TESS: Koş, hemen!");
});

test("context gate: references / lowercase / and-but-so, ≥ 6 words", () => {
	assert.equal(S.contextUseful("I told you we should never trust them"), true);
	assert.equal(S.contextUseful("That's what I was trying to say"), true);
	assert.equal(S.contextUseful("and then we ran to the car"), true);
	assert.equal(S.contextUseful("But we never made it out alive"), true);
	assert.equal(S.contextUseful("So we wait until the sun sets"), true);
	assert.equal(S.contextUseful("Welcome to the old harbor district today"), false);
	assert.equal(S.contextUseful("Give it to me"), false, "too short");
	assert.equal(S.contextUseful("Withering heathens hit the shore again"), false, "no substring matches");
	const ctx = [1, 2, 3, 4].map((i) => ({ en: "e" + i, tr: "t" + i }));
	assert.deepEqual(S.effectiveContext("Give it to me now please, they said", ctx), [{ en: "e3", tr: "t3" }, { en: "e4", tr: "t4" }]);
	assert.deepEqual(S.effectiveContext("Welcome to the old harbor district today", ctx), []);
});

test("output cap: clamp(2 × words + 24, 32, 160)", () => {
	assert.equal(S.maxOutputTokens("Hi"), 32);
	assert.equal(S.maxOutputTokens("one two three four five"), 34);
	assert.equal(S.maxOutputTokens(Array(50).fill("w").join(" ")), 124);
	assert.equal(S.maxOutputTokens(Array(100).fill("w").join(" ")), 160);
});

test("translate: MAX_TOKENS → non-stream retried once with 200; stream → error, nothing cached/counted", async () => {
	const env = makeEnv();
	const tok = await newSession(env);
	const caps = [];
	F.gemini = (req, body, n) => {
		caps.push(body.generationConfig.maxOutputTokens);
		if (caps.length === 1) return Response.json({ candidates: [{ content: { parts: [{ text: "Yarım çev" }] }, finishReason: "MAX_TOKENS" }] });
		return geminiJson("Tam çeviri burada.");
	};
	const r = await tr(env, tok, { text: "Hold on a second" });
	assert.equal(r.j.text, "Tam çeviri burada.");
	assert.deepEqual(caps, [32, 200]);
	F.gemini = () => sseResponse([sseText("Yarım ama uzun bir çeviri metni "), sseEvent({ candidates: [{ content: { parts: [{ text: "ve" }] }, finishReason: "MAX_TOKENS" }] })]);
	const before = env.ALTYAZI_KV.puts.length;
	const s = await tr(env, tok, { text: "Another line to translate" }, true);
	assert.equal(s.lines.at(-1).error, "upstream_error");
	assert.ok(!env.ALTYAZI_KV.puts.slice(before).some((p) => p.k.startsWith("tr:") || p.k.startsWith("q:")));
});

test("isolate LRU in front of KV + daily hit/miss counters (no text)", async () => {
	const env = makeEnv();
	const tok = await newSession(env);
	await tr(env, tok, { text: "Hot line here" });
	const gets = env.ALTYAZI_KV.gets;
	const r = await tr(env, tok, { text: "hot line here" });
	assert.equal(r.j.cached, true);
	// LRU isabeti: çeviri anahtarı için KV okunmaz (yalnızca kota okuması).
	assert.equal(env.ALTYAZI_KV.gets - gets, 1);
	S._resetState(); // yeni isolate: LRU boş → KV'den
	const tok2 = await newSession(env);
	assert.equal((await tr(env, tok2, { text: "Hot line here" })).j.cached, true);
	await tr(env, tok2, { text: "Hot line here" });
	await S._flushStats(env);
	const st = JSON.parse(env.ALTYAZI_KV.m.get("st:" + S.dayKey(Date.now())).v);
	assert.deepEqual(st, { mem: 1, kv: 1, miss: 0 }, JSON.stringify(st));
	for (const k of env.ALTYAZI_KV.m.keys()) if (k.startsWith("st:")) assert.ok(!env.ALTYAZI_KV.m.get(k).v.includes("Hot"));
	// LRU sınırı
	for (let i = 0; i < S.LRU_MAX + 10; i++) S.lruPut("k" + i, "v");
	assert.equal(S.lruGet("k0"), null);
	assert.equal(S.lruGet("k" + (S.LRU_MAX + 9)), "v");
});

test("translate stream: SSE → NDJSON deltas + done (split chunks, thoughts skipped)", async () => {
	const env = makeEnv();
	const tok = await newSession(env);
	let seenUrl;
	F.gemini = (req, body, n, signal, url) => {
		seenUrl = url;
		const all = sseText("hmm", true) + sseText("\"Bunu ") + sseText("hemen yapmalıyız, ") + sseText("yoksa geç kalacağız.\"\n") +
			sseEvent({ candidates: [{ content: { parts: [{ text: "" }] }, finishReason: "STOP" }], usageMetadata: { totalTokenCount: 50 } });
		// rastgele bölünmüş parçalar (olay sınırları dahil)
		const chunks = [];
		for (let i = 0; i < all.length; i += 7) chunks.push(all.slice(i, i + 7));
		return sseResponse(chunks);
	};
	const r = await tr(env, tok, { text: "We have to do this now or we'll be late.", context: [] }, true);
	assert.equal(r.status, 200);
	assert.equal(seenUrl.pathname.endsWith(":streamGenerateContent"), true);
	assert.equal(seenUrl.searchParams.get("alt"), "sse");
	const deltas = r.lines.filter((l) => "d" in l).map((l) => l.d);
	const last = r.lines.at(-1);
	assert.ok(deltas.length >= 2, JSON.stringify(r.lines));
	assert.equal(last.done, true);
	assert.equal(last.cached, false);
	assert.equal(last.text, "Bunu hemen yapmalıyız, yoksa geç kalacağız.");
	assert.equal(deltas.join(""), last.text);
	assert.ok(!deltas.join("").includes("hmm"));
	assert.equal(last.quota.used, 1);
	// sonraki istek önbellekten
	const again = await tr(env, tok, { text: "We have to do this now or we'll be late.", context: [] }, true);
	assert.equal(again.lines.length, 1);
	assert.equal(again.lines[0].cached, true);
});

test("translate stream: short reply flushed at finish", async () => {
	const env = makeEnv();
	const tok = await newSession(env);
	F.gemini = () => sseResponse([sseText("Evet.")]);
	const r = await tr(env, tok, { text: "Yes." }, true);
	assert.deepEqual(r.lines.map((l) => l.d ?? l.text), ["Evet.", "Evet."]);
});

test("translate stream: upstream non-200 → 502 JSON before streaming", async () => {
	const env = makeEnv();
	const tok = await newSession(env);
	F.gemini = () => Response.json({ error: { code: 503, message: "overloaded" } }, { status: 503 });
	const r = await tr(env, tok, { text: "Hello there" }, true);
	assert.equal(r.status, 502);
	assert.equal(r.j.code, "upstream_error");
	assert.equal(r.j.quota, undefined);
});

test("translate stream: mid-stream timeout → error line last, nothing counted", async () => {
	const env = makeEnv();
	const tok = await newSession(env);
	F.gemini = (req, body, n, signal) => sseResponse([sseText("Bunu hemen yapmalıyız ve ")], { hang: true, signal });
	const t0 = Date.now();
	const r = await tr(env, tok, { text: "Hang on a second my friend" }, true);
	assert.ok(Date.now() - t0 < 1500);
	assert.equal(r.status, 200);
	assert.deepEqual(r.lines.at(-1), { error: "upstream_error", message: S.MESSAGES.upstream_error });
	assert.ok(!env.ALTYAZI_KV.puts.some((p) => p.k.startsWith("tr:") || p.k.startsWith("q:")));
});

test("translate stream: blocked prompt → error line", async () => {
	const env = makeEnv();
	const tok = await newSession(env);
	F.gemini = () => sseResponse([sseEvent({ promptFeedback: { blockReason: "SAFETY" } })]);
	const r = await tr(env, tok, { text: "Something bad" }, true);
	assert.equal(r.lines.at(-1).error, "upstream_error");
});

test("translate non-stream: timeout → 502 upstream_error", async () => {
	const env = makeEnv();
	const tok = await newSession(env);
	F.gemini = (req, body, n, signal) => new Promise((_, rej) => signal.addEventListener("abort", () => rej(new DOMException("aborted", "AbortError"))));
	const t0 = Date.now();
	const r = await tr(env, tok, { text: "Hello there" });
	assert.ok(Date.now() - t0 < 1500);
	assert.equal(r.status, 502);
	assert.equal(r.j.code, "upstream_error");
});

test("translate non-stream: empty / malformed upstream → 502", async () => {
	const env = makeEnv();
	const tok = await newSession(env);
	F.gemini = () => new Response("not json", { status: 200 });
	assert.equal((await tr(env, tok, { text: "Hello A" })).j.code, "upstream_error");
	F.gemini = () => geminiJson("   ");
	assert.equal((await tr(env, tok, { text: "Hello B" })).j.code, "upstream_error");
	F.gemini = () => Response.json({ promptFeedback: { blockReason: "SAFETY" } });
	assert.equal((await tr(env, tok, { text: "Hello C" })).j.code, "upstream_error");
});

test("translate: thinkingConfig rejected → retried once without it, remembered", async () => {
	const env = makeEnv({ GEMINI_MODEL: "gemini-9-flash-lite" });
	const tok = await newSession(env);
	const bodies = [];
	F.gemini = (req, body) => {
		bodies.push(body);
		if (body.generationConfig.thinkingConfig) return Response.json({ error: { code: 400, message: "thinking_level is not supported" } }, { status: 400 });
		return geminiJson("Tamam.");
	};
	assert.equal((await tr(env, tok, { text: "Okay." })).j.text, "Tamam.");
	assert.equal(bodies.length, 2);
	assert.equal((await tr(env, tok, { text: "Okay then." })).j.text, "Tamam.");
	assert.equal(bodies.length, 3);
	assert.equal(bodies[2].generationConfig.thinkingConfig, undefined);
});

test("thinkingConfig mapping", () => {
	assert.deepEqual(S.thinkingConfig("gemini-3.5-flash-lite"), { thinkingLevel: "minimal" });
	assert.deepEqual(S.thinkingConfig("gemini-3.6-flash"), { thinkingLevel: "minimal" });
	assert.deepEqual(S.thinkingConfig("gemini-3.8-flash"), { thinkingLevel: "low" });
	assert.deepEqual(S.thinkingConfig("gemini-2.5-flash-lite"), { thinkingBudget: 0 });
	assert.equal(S.thinkingConfig("gemini-2.0-flash"), null);
});

test("translate: quota exhaustion → 429 with reset message; cached still served", async () => {
	const env = makeEnv({ PLAN_LIMITS: '{"standart":1,"pro":2,"premium":3,"deneme":1}' });
	const tok = await newSession(env);
	assert.equal((await tr(env, tok, { text: "One" })).status, 200);
	// KV yazımları ~1,5 sn'de bir; bellek sayacı yine de doğru sayar
	assert.equal((await tr(env, tok, { text: "Two" })).j.quota.used, 2);
	const r = await tr(env, tok, { text: "Three" });
	assert.equal(r.status, 429);
	assert.equal(r.j.code, "quota_exceeded");
	assert.match(r.j.message, /^Pro paketinin bu ayki 2 satırlık çeviri hakkı doldu\. Hak \d\d\.\d\d\.\d{4} tarihinde yenilenir\. Daha fazlası için Premium paketine geçebilirsiniz\. İsterseniz ay sonuna kadar geçerli 2\.000 satırlık ek paket de alabilirsiniz\.$/);
	const c = await tr(env, tok, { text: "One" });
	assert.equal(c.status, 200);
	assert.equal(c.j.cached, true);
});

test("quota: pending increments flushed to KV at most every 1.5 s, persisted across isolates", async () => {
	const env = makeEnv();
	const sub = "b".repeat(32);
	const now = Date.now();
	const a = S.incrementQuota(env, sub, now);
	await a.flush;
	const b = S.incrementQuota(env, sub, now + 100);
	assert.equal(b.flush, null);
	assert.equal(b.used, 2);
	assert.equal(await S.readQuotaUsed(env, sub, now + 200), 2);
	const c = S.incrementQuota(env, sub, now + 1700);
	await c.flush;
	assert.equal(env.ALTYAZI_KV.puts.filter((p) => p.k.startsWith("q:")).length, 2);
	S._resetState(); // yeni isolate
	assert.equal(await S.readQuotaUsed(env, sub, now + 1800), 3);
});

test("translate: validation (types, sizes, context limits, MAX_TEXT_CHARS)", async () => {
	const env = makeEnv();
	const tok = await newSession(env);
	const bad = [
		{}, { text: 5 }, { text: "   " }, { text: "x".repeat(601) },
		{ text: "a", context: "x" }, { text: "a", context: [1] }, { text: "a", context: [{ en: "a" }] },
		{ text: "a", context: Array.from({ length: 5 }, () => ({ en: "a", tr: "b" })) },
		{ text: "a", context: [{ en: "x".repeat(601), tr: "b" }] },
		{ text: "a", speaker: 42 }, { text: "a", speaker: "x".repeat(61) },
	];
	for (const b of bad) {
		S._resetState(); // hız sınırına takılmasın
		const r = await tr(env, tok, b);
		assert.equal(r.status, 400, JSON.stringify(b).slice(0, 80));
		assert.equal(r.j.code, "bad_request");
	}
	const env2 = makeEnv({ MAX_TEXT_CHARS: "10" });
	const tok2 = await newSession(env2);
	assert.equal((await tr(env2, tok2, { text: "This is longer than ten" })).status, 400);
	assert.equal(F.calls.filter((c) => c.url.includes("generativelanguage")).length, 0);
});

test("translate: per-sub burst rate limit", async () => {
	const env = makeEnv();
	const tok = await newSession(env);
	await tr(env, tok, { text: "Cached line" });
	let limited = 0;
	for (let i = 0; i < 25; i++) if ((await tr(env, tok, { text: "Cached line" })).status === 429) limited++;
	assert.ok(limited >= 5, "limited " + limited);
});

test("logs never contain subtitle text or secrets", async () => {
	const env = makeEnv();
	const tok = await newSession(env);
	F.gemini = () => geminiJson("GİZLİ_ÇEVİRİ");
	await tr(env, tok, { text: "SECRET_SUBTITLE_LINE", context: [{ en: "CTX_EN_SECRET", tr: "CTX_TR_SECRET" }] });
	F.gemini = () => sseResponse([sseText("GİZLİ_AKIŞ_ÇEVİRİSİ")]);
	await tr(env, tok, { text: "SECRET_STREAM_LINE" }, true);
	const all = logs.join("\n");
	for (const s of ["SECRET_SUBTITLE_LINE", "CTX_EN_SECRET", "CTX_TR_SECRET", "GİZLİ", "SECRET_STREAM_LINE", SECRET, GEMINI_KEY, "ya29", tok])
		assert.ok(!all.includes(s), "leaked " + s);
	assert.ok(all.includes("/v1/translate"));
});

test("cleanOutput & StreamCleaner", () => {
	assert.equal(S.cleanOutput("```\nMerhaba\n```"), "Merhaba");
	assert.equal(S.cleanOutput("Türkçe: «Selam»"), "Selam");
	assert.equal(S.cleanOutput("Bir\n iki"), "Bir iki");
	const c = new S.StreamCleaner();
	const out = ["Translation: \"Ne", " yapıyorsun", " burada", " sen?\""].map((d) => c.push(d)).join("") + c.finish();
	assert.equal(out, "Ne yapıyorsun burada sen?");
});

test("helpers: b64url roundtrip, month math", () => {
	const b = new Uint8Array([0, 255, 128, 1, 2, 3]);
	assert.deepEqual(S.b64urlDecode(S.b64urlEncode(b)), b);
	assert.equal(S.b64urlDecode("a+b"), null);
	assert.equal(S.monthKey(Date.UTC(2026, 11, 31, 23)), "2026-12");
	assert.equal(S.nextMonthStart(Date.UTC(2026, 11, 31, 23)), Date.UTC(2027, 0, 1));
});

// ---------------------------------------------------------------- paketler (v1.1)

test("plans: per-plan caps and names in /v1/session (defaults)", async () => {
	const env = makeEnv();
	for (const [plan, limit, name] of [["standart", 4500, "Standart"], ["pro", 10000, "Pro"], ["premium", 24000, "Premium"]]) {
		const t = "tok_plan_" + plan + "_123456";
		F.purchases[t] = activeSub(undefined, undefined, undefined, { basePlanId: plan });
		const r = await call(env, "POST", "/v1/session", { body: sessionBody(t) });
		assert.equal(r.status, 200, r.text);
		assert.equal(r.j.plan, plan);
		assert.equal(r.j.planName, name);
		assert.equal(r.j.basePlan, plan);
		assert.equal(r.j.basePlanName, name);
		assert.equal(r.j.trial, false);
		assert.equal(r.j.quota.limit, limit);
		const payload = JSON.parse(Buffer.from(r.j.sessionToken.split(".")[0], "base64url").toString());
		assert.equal(payload.plan, plan);
	}
});

test("plans: server enforces the session plan's cap (standart vs premium)", async () => {
	const env = makeEnv({ PLAN_LIMITS: '{"standart":1,"pro":2,"premium":3,"deneme":1}' });
	const std = await newSession(env, "tok_std_cap_1234567", { basePlanId: "standart" });
	assert.equal((await tr(env, std, { text: "Line one" })).j.quota.limit, 1);
	const r = await tr(env, std, { text: "Line two" });
	assert.equal(r.status, 429);
	assert.equal(r.j.code, "quota_exceeded");
	assert.match(r.j.message, /^Standart paketinin .* Daha fazlası için Pro paketine geçebilirsiniz\. İsterseniz .* ek paket de alabilirsiniz\.$/);
	const prem = await newSession(env, "tok_prem_cap_123456", { basePlanId: "premium" });
	for (const t of ["P1", "P2", "P3"]) assert.equal((await tr(env, prem, { text: "Premium " + t })).status, 200);
	const last = await tr(env, prem, { text: "Premium P4" });
	assert.equal(last.status, 429);
	assert.ok(!last.j.message.includes("geçebilirsiniz"), "no upsell on top plan");
});

test("plans: PLAN_LIMITS override, invalid JSON falls back to defaults", () => {
	assert.deepEqual(S.planLimits({}), { standart: 4500, pro: 10000, premium: 24000, deneme: 1500 });
	assert.deepEqual(S.planLimits({ PLAN_LIMITS: '{"standart":100,"pro":200}' }), { standart: 100, pro: 200, deneme: 1500 });
	for (const bad of ["{oops", "[1,2]", '{"pro":-1}', '{"pro":"9"}', '{"Pro X":5}', '{"deneme":5}', "null"])
		assert.deepEqual(S.planLimits({ PLAN_LIMITS: bad }), S.DEFAULT_PLAN_LIMITS, bad);
	const L = S.DEFAULT_PLAN_LIMITS;
	assert.equal(S.nextPlan(L, "standart"), "pro");
	assert.equal(S.nextPlan(L, "pro"), "premium");
	assert.equal(S.nextPlan(L, "premium"), null);
	assert.equal(S.nextPlan(L, "deneme"), null);
	assert.equal(S.planName("yillik-pro"), "Yillik-pro");
	assert.equal(S.fmtCount(27000), "27.000");
	assert.equal(S.fmtCount(1234567), "1.234.567");
	assert.equal(S.fmtCount(450), "450");
});

test("plans: unknown / missing base plan → invalid_purchase", async () => {
	const env = makeEnv();
	F.purchases["tok_unknown_plan_1"] = activeSub(undefined, undefined, undefined, { basePlanId: "aylik" });
	let r = await call(env, "POST", "/v1/session", { body: sessionBody("tok_unknown_plan_1") });
	assert.equal(r.status, 400);
	assert.equal(r.j.code, "invalid_purchase");
	F.purchases["tok_deneme_plan_1"] = activeSub(undefined, undefined, undefined, { basePlanId: "deneme" });
	r = await call(env, "POST", "/v1/session", { body: sessionBody("tok_deneme_plan_1") });
	assert.equal(r.j.code, "invalid_purchase", "deneme is not a base plan");
	const noOffer = activeSub();
	delete noOffer.body.lineItems[0].offerDetails;
	F.purchases["tok_no_offer_det_1"] = noOffer;
	r = await call(env, "POST", "/v1/session", { body: sessionBody("tok_no_offer_det_1") });
	assert.equal(r.j.code, "invalid_purchase");
	// Plan sonradan PLAN_LIMITS'ten kaldırılırsa açık oturum 401 alır (yeniden oturum → invalid_purchase).
	const tok = await newSession(env, "tok_removed_plan_12", { basePlanId: "premium" });
	const env2 = { ...env, PLAN_LIMITS: '{"standart":4500,"pro":11000}' };
	assert.equal((await tr(env2, tok, { text: "Hello" })).status, 401);
});

test("trial: offerPhase.freeTrial → plan deneme, trial cap, separate trial quota key, resetAt = trial end", async () => {
	const env = makeEnv({ PLAN_LIMITS: '{"standart":4500,"pro":11000,"premium":27000,"deneme":2}' });
	const trialEnd = new Date(Date.now() + 2 * 24 * 3600e3).toISOString();
	F.purchases["tok_trial_phase_12"] = activeSub(undefined, undefined, trialEnd,
		{ basePlanId: "premium", offerId: "deneme-3gun", offerPhase: { freeTrial: {} }, startTime: new Date(Date.now() - 24 * 3600e3).toISOString() });
	const r = await call(env, "POST", "/v1/session", { body: sessionBody("tok_trial_phase_12") });
	assert.equal(r.status, 200, r.text);
	assert.equal(r.j.plan, "deneme");
	assert.equal(r.j.planName, "Deneme");
	assert.equal(r.j.basePlan, "premium");
	assert.equal(r.j.trial, true);
	assert.equal(r.j.quota.limit, 2);
	assert.equal(r.j.quota.resetAt, Date.parse(trialEnd));
	const payload = JSON.parse(Buffer.from(r.j.sessionToken.split(".")[0], "base64url").toString());
	assert.equal(payload.plan, "deneme");
	assert.equal(payload.te, Date.parse(trialEnd));
	const tok = r.j.sessionToken;
	const a = await tr(env, tok, { text: "Trial line A" });
	assert.equal(a.j.quota.resetAt, Date.parse(trialEnd));
	await tr(env, tok, { text: "Trial line B" });
	const c = await tr(env, tok, { text: "Trial line C" });
	assert.equal(c.status, 429);
	assert.match(c.j.message, /^Ücretsiz denemenin 2 satırlık/);
	const qk = env.ALTYAZI_KV.puts.find((p) => p.k.startsWith("q:"));
	assert.match(qk.k, /^q:[0-9a-f]{32}:trial$/);
	assert.equal(qk.o.expirationTtl, 30 * 24 * 3600);
	// Aynı abone deneme bitip ücretli aşamaya geçince aylık kova sıfırdan başlar.
	S._resetState();
	env.ALTYAZI_KV.m.forEach((v, k) => { if (k.startsWith("pv:")) env.ALTYAZI_KV.m.delete(k); });
	F.purchases["tok_trial_phase_12"] = activeSub(undefined, undefined, FUTURE(), { basePlanId: "premium", offerId: "deneme-3gun", offerPhase: { basePrice: {} } });
	const paid = await call(env, "POST", "/v1/session", { body: sessionBody("tok_trial_phase_12") });
	assert.equal(paid.j.plan, "premium");
	assert.equal(paid.j.quota.used, 0);
	assert.equal(paid.j.quota.limit, 27000);
});

test("trial: fallback detection without offerPhase (offerId deneme-3gun + startTime < 3 days)", () => {
	const now = Date.now();
	const mk = (o) => activeSub(undefined, undefined, undefined, { offerPhase: null, ...o }).body;
	assert.equal(S.evaluateSubscription(mk({ offerId: "deneme-3gun", startTime: new Date(now - 24 * 3600e3).toISOString() }), now).trial, true);
	assert.equal(S.evaluateSubscription(mk({ offerId: "deneme-3gun", startTime: new Date(now - 4 * 24 * 3600e3).toISOString() }), now).trial, false);
	assert.equal(S.evaluateSubscription(mk({ offerId: "baska-teklif", startTime: new Date(now - 3600e3).toISOString() }), now).trial, false);
	assert.equal(S.evaluateSubscription(mk({ offerId: "deneme-3gun", startTime: "garbage" }), now).trial, false);
	// offerPhase varsa o belirleyicidir.
	const paid = activeSub(undefined, undefined, undefined, { offerId: "deneme-3gun", offerPhase: { basePrice: {} }, startTime: new Date(now - 3600e3).toISOString() }).body;
	assert.equal(S.evaluateSubscription(paid, now).trial, false);
	const intro = activeSub(undefined, undefined, undefined, { offerPhase: { introductoryPrice: {} } }).body;
	assert.equal(S.evaluateSubscription(intro, now).trial, false);
});

test("evaluateSubscription: latest-expiry line item decides plan (deferred downgrade keeps current plan)", () => {
	const now = Date.now();
	const soon = new Date(now + 5 * 24 * 3600e3).toISOString();
	const later = new Date(now + 25 * 24 * 3600e3).toISOString();
	const body = activeSub(undefined, undefined, later, {
		basePlanId: "premium",
		extraItems: [
			{ productId: "altyazitr_abonelik", expiryTime: soon, offerDetails: { basePlanId: "standart" }, offerPhase: { basePrice: {} } },
			{ productId: "altyazitr_abonelik", expiryTime: new Date(now - 1000).toISOString(), offerDetails: { basePlanId: "pro" } },
			{ productId: "other", expiryTime: new Date(now + 90 * 24 * 3600e3).toISOString(), offerDetails: { basePlanId: "x" } },
		],
	}).body;
	const ev = S.evaluateSubscription(body, now);
	assert.equal(ev.active, true);
	assert.equal(ev.basePlanId, "premium");
	assert.equal(ev.expiryMs, Date.parse(later));
	assert.equal(ev.linkedPurchaseToken, null);
});

test("upgrade: linkedPurchaseToken keeps the same monthly quota bucket (chain resolves to oldest)", async () => {
	const env = makeEnv();
	const t0 = "tok_chain_zero_1234567";
	const tok0 = await newSession(env, t0, { basePlanId: "standart" });
	assert.equal((await tr(env, tok0, { text: "Chain line one" })).j.quota.used, 1);
	const h0 = (await S.sha256Hex(t0)).slice(0, 32);
	// Yükseltme: Play yeni jeton verir, eskisini linkedPurchaseToken ile bildirir.
	const t1 = "tok_chain_one_12345678";
	F.purchases[t1] = activeSub(undefined, undefined, undefined, { basePlanId: "pro", linkedPurchaseToken: t0 });
	const r1 = await call(env, "POST", "/v1/session", { body: sessionBody(t1) });
	assert.equal(r1.status, 200, r1.text);
	assert.equal(r1.j.plan, "pro");
	assert.equal(r1.j.quota.used, 1, "usage carried within the month");
	assert.equal(r1.j.quota.limit, 10000);
	const p1 = JSON.parse(Buffer.from(r1.j.sessionToken.split(".")[0], "base64url").toString());
	assert.equal(p1.sub, (await S.sha256Hex(t1)).slice(0, 32));
	assert.equal(p1.qb, h0);
	assert.equal((await tr(env, r1.j.sessionToken, { text: "Chain line two" })).j.quota.used, 2);
	// İkinci değişiklik: t2 → t1 → t0; kova yine t0.
	const t2 = "tok_chain_two_12345678";
	F.purchases[t2] = activeSub(undefined, undefined, undefined, { basePlanId: "premium", linkedPurchaseToken: t1 });
	const r2 = await call(env, "POST", "/v1/session", { body: sessionBody(t2) });
	const p2 = JSON.parse(Buffer.from(r2.j.sessionToken.split(".")[0], "base64url").toString());
	assert.equal(p2.qb, h0);
	assert.equal(r2.j.quota.used, 2);
	const lk = env.ALTYAZI_KV.puts.filter((p) => p.k.startsWith("lk:"));
	assert.equal(lk.length, 2);
	assert.ok(lk.every((p) => p.v === h0 && p.o.expirationTtl === 400 * 24 * 3600));
	assert.ok(!lk.some((p) => p.k.includes("tok_")), "raw tokens never stored");
	// Bağlantısız (bilinmeyen eski jeton): kova eski jetonun özeti.
	const t3 = "tok_chain_three_123456";
	F.purchases[t3] = activeSub(undefined, undefined, undefined, { linkedPurchaseToken: "tok_never_seen_123456" });
	const r3 = await call(env, "POST", "/v1/session", { body: sessionBody(t3) });
	const p3 = JSON.parse(Buffer.from(r3.j.sessionToken.split(".")[0], "base64url").toString());
	assert.equal(p3.qb, (await S.sha256Hex("tok_never_seen_123456")).slice(0, 32));
});

test("session token: plan is signed (tampering → 401), v1 tokens rejected", async () => {
	const env = makeEnv();
	const tok = await newSession(env, "tok_tamper_plan_123", { basePlanId: "standart" });
	const [p, sig] = tok.split(".");
	const payload = JSON.parse(Buffer.from(p, "base64url").toString());
	const forged = Buffer.from(JSON.stringify({ ...payload, plan: "premium" })).toString("base64url") + "." + sig;
	assert.equal((await tr(env, forged, { text: "Hello" })).status, 401);
	// Eski (v1) biçim: doğru imzalı olsa da reddedilir.
	const v1p = Buffer.from(JSON.stringify({ sub: payload.sub, exp: Date.now() + 60e3, v: 1 })).toString("base64url");
	const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
	const v1 = v1p + "." + S.b64urlEncode(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(v1p)));
	assert.equal(await S.verifySession(SECRET, v1, Date.now()), null);
	assert.equal((await tr(env, v1, { text: "Hello" })).j.code, "unauthorized");
});

// ---------------------------------------------------------------- yıllık planlar ve ek paket (v1.2)

test("yearly base plans map to the same monthly caps", async () => {
	const env = makeEnv();
	for (const [bp, plan, limit, bpName] of [["standart-yillik", "standart", 4500, "Standart (yıllık)"], ["pro-yillik", "pro", 10000, "Pro (yıllık)"], ["premium-yillik", "premium", 24000, "Premium (yıllık)"]]) {
		const t = "tok_year_" + bp + "_1234";
		F.purchases[t] = activeSub(undefined, undefined, undefined, { basePlanId: bp });
		const r = await call(env, "POST", "/v1/session", { body: sessionBody(t) });
		assert.equal(r.status, 200, r.text);
		assert.equal(r.j.plan, plan);
		assert.equal(r.j.basePlan, bp);
		assert.equal(r.j.basePlanName, bpName);
		assert.equal(r.j.quota.limit, limit);
		assert.equal(r.j.quota.resetAt, S.nextMonthStart(Date.now()), "quota still resets monthly");
		assert.equal(JSON.parse(Buffer.from(r.j.sessionToken.split(".")[0], "base64url").toString()).plan, plan);
	}
	const L = S.DEFAULT_PLAN_LIMITS;
	assert.equal(S.resolvePlan(L, "pro"), "pro");
	assert.equal(S.resolvePlan(L, "pro-yillik"), "pro");
	assert.equal(S.resolvePlan(L, "deneme-yillik"), null);
	assert.equal(S.resolvePlan(L, "deneme"), null);
	assert.equal(S.resolvePlan(L, "ultra-yillik"), null);
	assert.equal(S.resolvePlan(L, "-yillik"), null);
	assert.equal(S.resolvePlan({ ...L, "pro-yillik": 12000 }, "pro-yillik"), "pro-yillik", "explicit entry wins");
	// Aylık → yıllık geçiş (linkedPurchaseToken): aynı ay içindeki kullanım taşınır.
	const t0 = "tok_m2y_monthly_123456";
	const tok0 = await newSession(env, t0, { basePlanId: "pro" });
	await tr(env, tok0, { text: "Monthly to yearly" });
	F.purchases["tok_m2y_yearly_1234567"] = activeSub(undefined, undefined, undefined, { basePlanId: "pro-yillik", linkedPurchaseToken: t0 });
	const y = await call(env, "POST", "/v1/session", { body: sessionBody("tok_m2y_yearly_1234567") });
	assert.equal(y.j.quota.used, 1);
	// Deneme yıllık planda da çalışır.
	F.purchases["tok_year_trial_12345"] = activeSub(undefined, undefined, undefined, { basePlanId: "premium-yillik", offerId: "deneme-3gun", offerPhase: { freeTrial: {} } });
	const tri = await call(env, "POST", "/v1/session", { body: sessionBody("tok_year_trial_12345") });
	assert.equal(tri.j.plan, "deneme");
	assert.equal(tri.j.basePlan, "premium-yillik");
});

const topupBody = (purchaseToken, productId = "ek_satir_2000") => ({ packageName: "com.altyazitr.oyun", productId, purchaseToken });
const product = (over = {}) => ({ status: 200, body: { kind: "androidpublisher#productPurchase", purchaseTimeMillis: String(Date.now()), purchaseState: 0,
	consumptionState: 0, orderId: "GPA.1234-5678-9012-34567", purchaseType: 0, acknowledgementState: 0, productId: "ek_satir_2000", quantity: 1, regionCode: "TR", ...over } });
const topup = (env, tok, body) => call(env, "POST", "/v1/topup", { body, headers: { authorization: "Bearer " + tok } });

test("topup: valid purchase credits +2000 once; quota check uses cap + bonus; idempotent", async () => {
	const env = makeEnv({ PLAN_LIMITS: '{"standart":1,"pro":2,"premium":3,"deneme":1}' });
	const tok = await newSession(env, "tok_topup_sub_1234567", { basePlanId: "pro" });
	await tr(env, tok, { text: "Line one" });
	await tr(env, tok, { text: "Line two" });
	assert.equal((await tr(env, tok, { text: "Line three" })).j.code, "quota_exceeded");
	F.products["ek_satir_2000/tok_pack_aaaaaaaaaa"] = product();
	const r = await topup(env, tok, topupBody("tok_pack_aaaaaaaaaa"));
	assert.equal(r.status, 200, r.text);
	assert.equal(r.j.credited, 2000);
	assert.equal(r.j.alreadyCredited, false);
	assert.deepEqual(r.j.quota, { used: 2, limit: 2002, resetAt: S.nextMonthStart(Date.now()), bonus: 2000 });
	const bk = env.ALTYAZI_KV.puts.find((p) => p.k.endsWith(":bonus"));
	assert.match(bk.k, /^q:[0-9a-f]{32}:\d{4}-\d{2}:bonus$/);
	assert.equal(bk.v, "2000");
	const rec = env.ALTYAZI_KV.puts.find((p) => p.k.startsWith("tu:"));
	assert.equal(rec.k, "tu:" + (await S.sha256Hex("tok_pack_aaaaaaaaaa")));
	assert.ok(!rec.v.includes("tok_pack"), "raw token never stored");
	const ok = await tr(env, tok, { text: "Line three" });
	assert.equal(ok.status, 200, ok.text);
	assert.equal(ok.j.quota.limit, 2002);
	assert.equal(ok.j.quota.bonus, 2000);
	// Aynı jeton tekrar: kredi yok, 200 (uygulama tüketmeyi yeniden deneyebilir); Google'a tekrar sorulmaz.
	const n = F.productCalls;
	const again = await topup(env, tok, topupBody("tok_pack_aaaaaaaaaa"));
	assert.equal(again.status, 200);
	assert.equal(again.j.credited, 0);
	assert.equal(again.j.alreadyCredited, true);
	assert.equal(again.j.quota.limit, 2002);
	assert.equal(F.productCalls, n);
	// Yeni isolate + yeni oturum: kredi KV'den okunur.
	S._resetState();
	const s2 = await call(env, "POST", "/v1/session", { body: sessionBody("tok_topup_sub_1234567") });
	assert.equal(s2.j.quota.limit, 2002);
	// İkinci, farklı paket birikir.
	F.products["ek_satir_2000/tok_pack_bbbbbbbbbb"] = product();
	assert.equal((await topup(env, s2.j.sessionToken, topupBody("tok_pack_bbbbbbbbbb"))).j.quota.limit, 4002);
});

test("topup: wrong product, refunded/canceled, pending, already consumed, unknown → rejected, nothing credited", async () => {
	const env = makeEnv();
	const tok = await newSession(env, "tok_topup_bad_1234567");
	let r = await topup(env, tok, topupBody("tok_pack_cccccccccc", "altyazitr_abonelik"));
	assert.equal(r.status, 400); assert.equal(r.j.code, "invalid_purchase");
	r = await topup(env, tok, { ...topupBody("tok_pack_cccccccccc"), packageName: "com.evil" });
	assert.equal(r.j.code, "invalid_purchase");
	r = await topup(env, tok, { packageName: "com.altyazitr.oyun" });
	assert.equal(r.j.code, "bad_request");
	F.products["ek_satir_2000/tok_pack_refunded1"] = product({ purchaseState: 1 });
	r = await topup(env, tok, topupBody("tok_pack_refunded1"));
	assert.equal(r.status, 400); assert.equal(r.j.code, "invalid_purchase");
	F.products["ek_satir_2000/tok_pack_pending11"] = product({ purchaseState: 2 });
	r = await topup(env, tok, topupBody("tok_pack_pending11"));
	assert.equal(r.status, 409); assert.equal(r.j.code, "purchase_pending");
	assert.ok(r.j.message.length > 0);
	F.products["ek_satir_2000/tok_pack_consumed1"] = product({ consumptionState: 1 });
	r = await topup(env, tok, topupBody("tok_pack_consumed1"));
	assert.equal(r.j.code, "invalid_purchase");
	F.products["ek_satir_2000/tok_pack_otherprod"] = product({ productId: "baska_urun" });
	r = await topup(env, tok, topupBody("tok_pack_otherprod"));
	assert.equal(r.j.code, "invalid_purchase");
	r = await topup(env, tok, topupBody("tok_pack_unknown11"));
	assert.equal(r.status, 400); assert.equal(r.j.code, "invalid_purchase");
	assert.ok(!env.ALTYAZI_KV.puts.some((p) => p.k.endsWith(":bonus") || p.k.startsWith("tu:")));
	// Oturumsuz → 401
	assert.equal((await call(env, "POST", "/v1/topup", { body: topupBody("tok_pack_cccccccccc") })).status, 401);
	assert.equal((await call(env, "GET", "/v1/topup")).status, 405);
	// Pending olan sonra onaylanınca kredilenir (kayıt yazılmamıştı).
	F.products["ek_satir_2000/tok_pack_pending11"] = product();
	S._resetState();
	r = await topup(env, tok, topupBody("tok_pack_pending11"));
	assert.equal(r.j.credited, 2000);
});

test("evaluateProductPurchase unit (ProductPurchase fields)", () => {
	const ok = product().body;
	assert.deepEqual(S.evaluateProductPurchase(ok, "ek_satir_2000"), { ok: true, quantity: 1 });
	assert.deepEqual(S.evaluateProductPurchase({ ...ok, quantity: 3 }, "ek_satir_2000"), { ok: true, quantity: 3 });
	const noQty = { ...ok }; delete noQty.quantity; delete noQty.productId;
	assert.deepEqual(S.evaluateProductPurchase(noQty, "ek_satir_2000"), { ok: true, quantity: 1 });
	assert.equal(S.evaluateProductPurchase({ ...ok, purchaseState: 1 }, "ek_satir_2000").ok, false);
	assert.equal(S.evaluateProductPurchase({ ...ok, purchaseState: undefined }, "ek_satir_2000").ok, false);
	assert.equal(S.evaluateProductPurchase(null, "ek_satir_2000").ok, false);
});

// ---------------------------------------------------------------- çalıştır

let pass = 0, failN = 0;
for (const t of tests) {
	S._resetState();
	resetFetch();
	logs.length = 0;
	console.log = (...a) => logs.push(a.join(" "));
	try {
		await t.fn();
		console.log = origLog;
		pass++;
		console.log("ok   - " + t.name);
	} catch (e) {
		console.log = origLog;
		failN++;
		console.log("FAIL - " + t.name + "\n" + (e && e.stack || e));
	}
}
console.log(`\n${pass} geçti, ${failN} başarısız (${tests.length} test)`);
process.exit(failN ? 1 : 0);
