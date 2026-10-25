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
		GEMINI_MODEL: "gemini-3.5-flash-lite", MONTHLY_LINE_LIMIT: "30000", MAX_TEXT_CHARS: "600", UPSTREAM_TIMEOUT_MS: "150", ...over };
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
	if (url.host === "generativelanguage.googleapis.com") {
		assert.equal(req.headers.get("x-goog-api-key"), GEMINI_KEY);
		const n = F.calls.filter((c) => c.url.includes("generativelanguage")).length;
		return F.gemini(req, JSON.parse(bodyText), n, init.signal, url);
	}
	throw new Error("unexpected fetch " + req.url);
};

function activeSub(productId = "altyazitr_pro", state = "SUBSCRIPTION_STATE_ACTIVE", expiry = FUTURE()) {
	return { status: 200, body: { kind: "androidpublisher#subscriptionPurchaseV2", subscriptionState: state, acknowledgementState: "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED", lineItems: [{ productId, expiryTime: expiry, autoRenewingPlan: { autoRenewEnabled: true } }] } };
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
const sessionBody = (purchaseToken) => ({ packageName: "com.altyazitr.oyun", productId: "altyazitr_pro", purchaseToken, appVersion: "1.0.0" });

async function newSession(env, token = "tok_" + Math.random().toString(36).slice(2) + "_abcdefgh") {
	F.purchases[token] = activeSub();
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
	assert.deepEqual(r.j, { ok: true, version: "1" });
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
	assert.equal(r.j.quota.limit, 30000);
	const payload = JSON.parse(Buffer.from(r.j.sessionToken.split(".")[0], "base64url").toString());
	assert.equal(payload.v, 1);
	assert.equal(payload.sub, (await S.sha256Hex("tok_active_1234567")).slice(0, 32));
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
	F.purchases["tok_grace_1234567"] = activeSub("altyazitr_pro", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD");
	assert.equal((await call(env, "POST", "/v1/session", { body: sessionBody("tok_grace_1234567") })).status, 200);
	for (const st of ["SUBSCRIPTION_STATE_EXPIRED", "SUBSCRIPTION_STATE_CANCELED", "SUBSCRIPTION_STATE_PAUSED", "SUBSCRIPTION_STATE_ON_HOLD", "SUBSCRIPTION_STATE_PENDING"]) {
		const t = "tok_" + st.toLowerCase();
		F.purchases[t] = activeSub("altyazitr_pro", st);
		const r = await call(env, "POST", "/v1/session", { body: sessionBody(t) });
		assert.equal(r.status, 403, st);
		assert.equal(r.j.code, "not_subscribed");
		assert.ok(r.j.message.length > 0);
	}
});

test("session: active state but expiryTime in past → not_subscribed", async () => {
	const env = makeEnv();
	F.purchases["tok_pastexp_12345"] = activeSub("altyazitr_pro", "SUBSCRIPTION_STATE_ACTIVE", PAST());
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
	const put = env.ALTYAZI_KV.puts.find((p) => p.k.startsWith("pv:v1:"));
	assert.equal(put.k, "pv:v1:" + (await S.sha256Hex("tok_cache_1234567")));
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
	assert.deepEqual(await S.verifySession(SECRET, t, Date.now()), { sub, exp: JSON.parse(Buffer.from(t.split(".")[0], "base64url")).exp });
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
	assert.equal(r.j.quota.limit, 30000);
	assert.equal(r.j.quota.resetAt, S.nextMonthStart(Date.now()));
	assert.ok(seen.url.pathname.endsWith("/models/gemini-3.5-flash-lite:generateContent"));
	assert.deepEqual(seen.body.generationConfig, { maxOutputTokens: 200, candidateCount: 1, thinkingConfig: { thinkingLevel: "minimal" } });
	assert.equal(seen.body.systemInstruction.parts[0].text, S.SYSTEM_PROMPT);
	const u = seen.body.contents[0].parts[0].text;
	assert.ok(u.includes("EN: Who are you?\nTR: Sen kimsin?"));
	assert.ok(u.includes("Speaker: Joel"));
	assert.ok(u.endsWith("LINE: Hello, buddy."));
	assert.ok(env.ALTYAZI_KV.puts.some((p) => p.k.startsWith("tr:v1:") && p.v === "Merhaba, dostum." && p.o.expirationTtl === 30 * 24 * 3600));
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

test("cache key: short lines ignore context, long lines include last pair", async () => {
	const m = "gemini-3.5-flash-lite";
	const c1 = [{ en: "a", tr: "b" }], c2 = [{ en: "c", tr: "d" }];
	assert.equal(await S.cacheKey(m, "Open the door now", c1, null), await S.cacheKey(m, "Open the door now", c2, null));
	const long = "I told you we should never have come back to this town";
	assert.notEqual(await S.cacheKey(m, long, c1, null), await S.cacheKey(m, long, c2, null));
	assert.equal(await S.cacheKey(m, long, [], null), await S.cacheKey(m, long, [], null));
	assert.notEqual(await S.cacheKey(m, "Hi", [], "A"), await S.cacheKey(m, "Hi", [], "B"));
	assert.notEqual(await S.cacheKey("gemini-3.6-flash", "Hi", [], null), await S.cacheKey(m, "Hi", [], null));
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
	const env = makeEnv({ MONTHLY_LINE_LIMIT: "2" });
	const tok = await newSession(env);
	assert.equal((await tr(env, tok, { text: "One" })).status, 200);
	// KV yazımları ~1,5 sn'de bir; bellek sayacı yine de doğru sayar
	assert.equal((await tr(env, tok, { text: "Two" })).j.quota.used, 2);
	const r = await tr(env, tok, { text: "Three" });
	assert.equal(r.status, 429);
	assert.equal(r.j.code, "quota_exceeded");
	assert.match(r.j.message, /sıfırlanır/);
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
