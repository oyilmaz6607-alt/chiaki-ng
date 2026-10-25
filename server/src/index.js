// SPDX-License-Identifier: AGPL-3.0-only
//
// AltyazıTR Pro çeviri sunucusu (Cloudflare Worker).
// Sözleşme: ../../PRO_API.md (v1). Bağımlılık yok; yalnızca Workers Web API'leri.
//
// Gizli değerler (wrangler secret): GEMINI_API_KEY, SESSION_SECRET, GOOGLE_SERVICE_ACCOUNT_JSON
// Değişkenler (wrangler.toml [vars]): GEMINI_MODEL, MONTHLY_LINE_LIMIT, MAX_TEXT_CHARS
// KV bağlaması: ALTYAZI_KV
//
// Gizlilik: altyazı metni hiçbir zaman log'lanmaz; yalnızca yol, durum, süre ve hata kodu.

/**
 * @typedef {Object} KVLike
 * @property {(key: string, opts?: any) => Promise<string|null>} get
 * @property {(key: string, value: string, opts?: {expirationTtl?: number}) => Promise<void>} put
 *
 * @typedef {Object} Env
 * @property {KVLike} ALTYAZI_KV
 * @property {string} [GEMINI_API_KEY]
 * @property {string} [SESSION_SECRET]
 * @property {string} [GOOGLE_SERVICE_ACCOUNT_JSON]
 * @property {string} [GEMINI_MODEL]
 * @property {string} [MONTHLY_LINE_LIMIT]
 * @property {string} [MAX_TEXT_CHARS]
 * @property {string} [UPSTREAM_TIMEOUT_MS] yalnızca test/ayar için (varsayılan 4000)
 *
 * @typedef {{waitUntil: (p: Promise<any>) => void}} Ctx
 * @typedef {{en: string, tr: string}} Pair
 * @typedef {{used: number, limit: number, resetAt: number}} Quota
 */

// ---------------------------------------------------------------- sabitler

export const API_VERSION = "1";
export const PACKAGE_NAME = "com.altyazitr.oyun";
export const PRODUCT_ID = "altyazitr_pro";
export const MAX_BODY_BYTES = 8 * 1024;
export const MAX_TEXT_HARD = 600; // sözleşme üst sınırı
export const MAX_CONTEXT = 4;
export const MAX_SPEAKER = 60;
export const SESSION_TTL_MS = 60 * 60 * 1000;
export const VERIFY_CACHE_TTL_S = 600; // aktif / geçersiz sonuçlar
export const VERIFY_NEG_CACHE_TTL_S = 60; // "abone değil" kısa tutulur (yeni satın alma hemen geçsin)
export const TR_CACHE_TTL_S = 30 * 24 * 3600;
export const DEFAULT_MODEL = "gemini-3.5-flash-lite";
export const DEFAULT_LIMIT = 30000;
export const DEFAULT_TIMEOUT_MS = 4000;
export const MAX_OUTPUT_TOKENS = 200;
export const SHORT_LINE_WORDS = 6;

const GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
const GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
const PLAY_SCOPE = "https://www.googleapis.com/auth/androidpublisher";
const PLAY_BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/";
const ACTIVE_STATES = new Set(["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"]);

/** Kısa sistem talimatı (uygulamadakinin sıkıştırılmış hali: daha az jeton = daha hızlı ve ucuz). */
export const SYSTEM_PROMPT = [
	"You localize English video game subtitles into Turkish.",
	"Translate only the text after LINE:. Output only the Turkish translation: no quotes, notes or alternatives.",
	"Write natural, fluent Turkish like a well-localized game; keep the tone and keep it short. Translate meaning and idioms, not word by word.",
	"Keep names, places, brands and other proper nouns unchanged. Keep a leading speaker label like \"NAME:\" unchanged.",
	"Earlier lines are context only (speaker, tone, terms); never output them.",
	"The text is from OCR: silently fix obvious recognition errors. If the line is only a name, a number or not English, return it unchanged.",
].join("\n");

/** Kullanıcıya gösterilen Türkçe hata metinleri. */
export const MESSAGES = {
	bad_request: "Geçersiz istek.",
	unauthorized: "Oturum süresi doldu, yeniden bağlanılıyor.",
	not_subscribed: "AltyazıTR Pro aboneliğiniz etkin değil.",
	invalid_purchase: "Satın alma doğrulanamadı.",
	rate_limited: "Çok fazla istek. Lütfen biraz bekleyin.",
	quota_exceeded: "Bu ayki çeviri sınırına ulaşıldı.",
	upstream_error: "Çeviri servisi şu an yanıt vermiyor.",
	server_error: "Sunucu hatası. Lütfen daha sonra tekrar deneyin.",
	not_found: "Bulunamadı.",
	method_not_allowed: "Bu yöntem desteklenmiyor.",
};

// ---------------------------------------------------------------- küçük yardımcılar

const enc = new TextEncoder();
const dec = new TextDecoder();

/** @param {Uint8Array|ArrayBuffer} buf */
export function b64urlEncode(buf) {
	const bytes = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
	let s = "";
	for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, Array.from(bytes.subarray(i, i + 0x8000)));
	return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** @param {string} s @returns {Uint8Array|null} */
export function b64urlDecode(s) {
	if (typeof s !== "string" || !/^[A-Za-z0-9_-]*$/.test(s)) return null;
	try {
		const b = atob(s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4));
		const out = new Uint8Array(b.length);
		for (let i = 0; i < b.length; i++) out[i] = b.charCodeAt(i);
		return out;
	} catch {
		return null;
	}
}

/** @param {string} s */
export async function sha256Hex(s) {
	const d = new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(s)));
	let h = "";
	for (const b of d) h += b.toString(16).padStart(2, "0");
	return h;
}

/** @param {string|undefined} v @param {number} def @param {number} min @param {number} max */
export function intEnv(v, def, min, max) {
	const n = Number.parseInt(v ?? "", 10);
	if (!Number.isFinite(n)) return def;
	return Math.min(max, Math.max(min, n));
}

/** "YYYY-MM" (UTC) */
export function monthKey(nowMs) {
	const d = new Date(nowMs);
	return d.getUTCFullYear() + "-" + String(d.getUTCMonth() + 1).padStart(2, "0");
}

/** Sonraki UTC ayın başı (ms). */
export function nextMonthStart(nowMs) {
	const d = new Date(nowMs);
	return Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1);
}

/** Boşlukları tekleştir, kırp, Unicode NFC. */
export function normalizeLine(s) {
	return s.normalize("NFC").replace(/\s+/g, " ").trim();
}

export function wordCount(s) {
	const t = s.trim();
	return t ? t.split(/\s+/).length : 0;
}

/**
 * @param {number} status
 * @param {any} obj
 */
function json(status, obj) {
	return new Response(JSON.stringify(obj), {
		status,
		headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
	});
}

/** @param {string} code @param {number} status @param {string} [message] */
function fail(status, code, message) {
	return json(status, { ok: false, code, message: message ?? MESSAGES[code] ?? MESSAGES.server_error });
}

class HttpError extends Error {
	/** @param {number} status @param {string} code @param {string} [msg] */
	constructor(status, code, msg) {
		super(code);
		this.status = status;
		this.code = code;
		this.userMessage = msg;
	}
}

// ---------------------------------------------------------------- istek gövdesi

/**
 * Gövdeyi en fazla [limit] bayt okur; aşarsa bad_request. JSON nesnesi döner.
 * @param {Request} request
 * @param {number} [limit]
 */
export async function readJsonBody(request, limit = MAX_BODY_BYTES) {
	const ct = (request.headers.get("content-type") || "").toLowerCase();
	if (!ct.startsWith("application/json")) throw new HttpError(400, "bad_request");
	const cl = request.headers.get("content-length");
	if (cl && Number(cl) > limit) throw new HttpError(400, "bad_request");
	if (!request.body) throw new HttpError(400, "bad_request");
	const reader = request.body.getReader();
	const chunks = [];
	let total = 0;
	for (;;) {
		const { done, value } = await reader.read();
		if (done) break;
		total += value.byteLength;
		if (total > limit) {
			try { await reader.cancel(); } catch {}
			throw new HttpError(400, "bad_request");
		}
		chunks.push(value);
	}
	const buf = new Uint8Array(total);
	let o = 0;
	for (const c of chunks) { buf.set(c, o); o += c.byteLength; }
	let obj;
	try {
		obj = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(buf));
	} catch {
		throw new HttpError(400, "bad_request");
	}
	if (!obj || typeof obj !== "object" || Array.isArray(obj)) throw new HttpError(400, "bad_request");
	return obj;
}

/**
 * /v1/session gövdesi.
 * @param {any} b
 * @returns {{ok: true, purchaseToken: string} | {ok: false, status: number, code: string}}
 */
export function validateSessionBody(b) {
	if (typeof b.packageName !== "string" || typeof b.productId !== "string" || typeof b.purchaseToken !== "string")
		return { ok: false, status: 400, code: "bad_request" };
	if (b.appVersion !== undefined && b.appVersion !== null && (typeof b.appVersion !== "string" || b.appVersion.length > 40))
		return { ok: false, status: 400, code: "bad_request" };
	if (b.packageName !== PACKAGE_NAME || b.productId !== PRODUCT_ID)
		return { ok: false, status: 400, code: "invalid_purchase" };
	// Play satın alma jetonları: harf, rakam, nokta, alt çizgi, tire (birkaç yüz karakter).
	if (b.purchaseToken.length < 10 || b.purchaseToken.length > 1024 || !/^[A-Za-z0-9._:-]+$/.test(b.purchaseToken))
		return { ok: false, status: 400, code: "invalid_purchase" };
	return { ok: true, purchaseToken: b.purchaseToken };
}

/**
 * /v1/translate gövdesi.
 * @param {any} b
 * @param {number} maxChars
 * @returns {{ok: true, text: string, context: Pair[], speaker: string|null} | {ok: false}}
 */
export function validateTranslateBody(b, maxChars) {
	if (typeof b.text !== "string") return { ok: false };
	const text = normalizeLine(b.text);
	if (!text || b.text.length > maxChars) return { ok: false };
	/** @type {Pair[]} */
	const context = [];
	if (b.context !== undefined && b.context !== null) {
		if (!Array.isArray(b.context) || b.context.length > MAX_CONTEXT) return { ok: false };
		for (const p of b.context) {
			if (!p || typeof p !== "object" || typeof p.en !== "string" || typeof p.tr !== "string") return { ok: false };
			if (p.en.length > MAX_TEXT_HARD || p.tr.length > MAX_TEXT_HARD) return { ok: false };
			const en = normalizeLine(p.en), tr = normalizeLine(p.tr);
			if (en && tr) context.push({ en, tr });
		}
	}
	let speaker = null;
	if (b.speaker !== undefined && b.speaker !== null) {
		if (typeof b.speaker !== "string" || b.speaker.length > MAX_SPEAKER) return { ok: false };
		speaker = normalizeLine(b.speaker) || null;
	}
	return { ok: true, text, context, speaker };
}

// ---------------------------------------------------------------- oturum jetonu (HMAC)

/** @type {Map<string, CryptoKey>} */
const hmacKeys = new Map();

/** @param {string} secret */
async function hmacKey(secret) {
	let k = hmacKeys.get(secret);
	if (!k) {
		k = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
		hmacKeys.clear();
		hmacKeys.set(secret, k);
	}
	return k;
}

/**
 * base64url(payload).base64url(HMAC-SHA256(payload_b64))
 * @param {string} secret @param {string} sub @param {number} exp
 */
export async function signSession(secret, sub, exp) {
	const p = b64urlEncode(enc.encode(JSON.stringify({ sub, exp, v: 1 })));
	const sig = await crypto.subtle.sign("HMAC", await hmacKey(secret), enc.encode(p));
	return p + "." + b64urlEncode(sig);
}

/**
 * İmza sabit zamanlı (crypto.subtle.verify) doğrulanır. Geçersiz/süresi dolmuş → null.
 * @param {string} secret @param {string} token @param {number} nowMs
 * @returns {Promise<{sub: string, exp: number}|null>}
 */
export async function verifySession(secret, token, nowMs) {
	if (typeof token !== "string" || token.length > 512) return null;
	const parts = token.split(".");
	if (parts.length !== 2) return null;
	const sig = b64urlDecode(parts[1]);
	if (!sig || sig.length !== 32) return null;
	const ok = await crypto.subtle.verify("HMAC", await hmacKey(secret), sig, enc.encode(parts[0]));
	if (!ok) return null;
	const raw = b64urlDecode(parts[0]);
	if (!raw) return null;
	let p;
	try { p = JSON.parse(dec.decode(raw)); } catch { return null; }
	if (!p || p.v !== 1 || typeof p.sub !== "string" || !/^[0-9a-f]{32}$/.test(p.sub)) return null;
	if (typeof p.exp !== "number" || !(p.exp > nowMs)) return null;
	return { sub: p.sub, exp: p.exp };
}

// ---------------------------------------------------------------- hız sınırı (isolate başına bellek)

/** @type {Map<string, {tokens: number, at: number}>} */
const buckets = new Map();

/**
 * Jeton kovası. Aynı isolate içinde geçerlidir (Cloudflare birden çok isolate çalıştırabilir — kaba bir koruma).
 * @param {string} key @param {number} capacity @param {number} perSec @param {number} nowMs
 */
export function rateLimit(key, capacity, perSec, nowMs) {
	let b = buckets.get(key);
	if (!b) {
		if (buckets.size > 20000) buckets.clear();
		b = { tokens: capacity, at: nowMs };
		buckets.set(key, b);
	}
	b.tokens = Math.min(capacity, b.tokens + ((nowMs - b.at) / 1000) * perSec);
	b.at = nowMs;
	if (b.tokens < 1) return false;
	b.tokens -= 1;
	return true;
}

// ---------------------------------------------------------------- Google OAuth (hizmet hesabı)

/** @type {{token: string, exp: number}|null} */
let gTokenMem = null;
/** @type {{key: CryptoKey, pem: string}|null} */
let rsaKeyMem = null;

/** @param {string} pem */
export async function importPkcs8Pem(pem) {
	const b64 = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s+/g, "");
	const der = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
	return crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
}

/**
 * RS256 JWT (hizmet hesabı onayı).
 * @param {{client_email: string, private_key: string}} sa @param {number} nowMs
 */
export async function buildServiceJwt(sa, nowMs) {
	if (!rsaKeyMem || rsaKeyMem.pem !== sa.private_key) rsaKeyMem = { key: await importPkcs8Pem(sa.private_key), pem: sa.private_key };
	const iat = Math.floor(nowMs / 1000);
	const head = b64urlEncode(enc.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
	const claims = b64urlEncode(enc.encode(JSON.stringify({ iss: sa.client_email, scope: PLAY_SCOPE, aud: GOOGLE_TOKEN_URL, iat, exp: iat + 3600 })));
	const input = head + "." + claims;
	const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", rsaKeyMem.key, enc.encode(input));
	return input + "." + b64urlEncode(sig);
}

/**
 * androidpublisher erişim jetonu; bellekte ve KV'de süresi dolana kadar saklanır.
 * @param {Env} env @param {number} nowMs
 */
export async function getGoogleAccessToken(env, nowMs) {
	if (gTokenMem && gTokenMem.exp - 60_000 > nowMs) return gTokenMem.token;
	try {
		const kv = await env.ALTYAZI_KV.get("gtoken:v1");
		if (kv) {
			const o = JSON.parse(kv);
			if (o && typeof o.token === "string" && o.exp - 60_000 > nowMs) {
				gTokenMem = o;
				return o.token;
			}
		}
	} catch {}
	let sa;
	try {
		sa = JSON.parse(env.GOOGLE_SERVICE_ACCOUNT_JSON || "");
	} catch {
		throw new HttpError(500, "server_error");
	}
	if (!sa || typeof sa.client_email !== "string" || typeof sa.private_key !== "string") throw new HttpError(500, "server_error");
	const assertion = await buildServiceJwt(sa, nowMs);
	const res = await fetch(GOOGLE_TOKEN_URL, {
		method: "POST",
		headers: { "content-type": "application/x-www-form-urlencoded" },
		body: "grant_type=" + encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer") + "&assertion=" + assertion,
	});
	if (!res.ok) throw new HttpError(502, "server_error", undefined);
	const j = /** @type {any} */ (await res.json());
	if (!j || typeof j.access_token !== "string") throw new HttpError(502, "server_error");
	const ttl = Math.max(120, Math.min(3600, Number(j.expires_in) || 3600));
	gTokenMem = { token: j.access_token, exp: nowMs + ttl * 1000 };
	try {
		await env.ALTYAZI_KV.put("gtoken:v1", JSON.stringify(gTokenMem), { expirationTtl: Math.max(60, ttl - 120) });
	} catch {}
	return gTokenMem.token;
}

// ---------------------------------------------------------------- Play abonelik doğrulama

/**
 * SubscriptionPurchaseV2 yanıtını değerlendirir (saf).
 * https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2
 * @param {any} sub @param {number} nowMs
 * @returns {{active: true, expiryMs: number} | {active: false}}
 */
export function evaluateSubscription(sub, nowMs) {
	if (!sub || typeof sub !== "object" || !ACTIVE_STATES.has(sub.subscriptionState)) return { active: false };
	const items = Array.isArray(sub.lineItems) ? sub.lineItems : [];
	let best = 0;
	for (const li of items) {
		if (!li || li.productId !== PRODUCT_ID || typeof li.expiryTime !== "string") continue;
		const t = Date.parse(li.expiryTime);
		if (Number.isFinite(t) && t > nowMs && t > best) best = t;
	}
	return best ? { active: true, expiryMs: best } : { active: false };
}

/**
 * Sonuç KV'de sha256(token) anahtarıyla önbelleğe alınır.
 * @param {Env} env @param {string} purchaseToken @param {string} tokenHash @param {number} nowMs
 * @returns {Promise<{state: "active", expiryMs: number} | {state: "inactive"} | {state: "invalid"}>}
 */
export async function verifyPurchase(env, purchaseToken, tokenHash, nowMs) {
	const ck = "pv:v1:" + tokenHash;
	try {
		const c = await env.ALTYAZI_KV.get(ck);
		if (c) {
			const o = JSON.parse(c);
			if (o.state === "active" && o.expiryMs > nowMs) return o;
			if (o.state === "inactive" || o.state === "invalid") return o;
		}
	} catch {}
	const at = await getGoogleAccessToken(env, nowMs);
	const url = PLAY_BASE + encodeURIComponent(PACKAGE_NAME) + "/purchases/subscriptionsv2/tokens/" + encodeURIComponent(purchaseToken);
	const res = await fetch(url, { headers: { authorization: "Bearer " + at } });
	/** @type {any} */
	let result;
	let ttl = VERIFY_CACHE_TTL_S;
	if (res.status === 200) {
		const ev = evaluateSubscription(await res.json(), nowMs);
		if (ev.active) result = { state: "active", expiryMs: ev.expiryMs };
		else { result = { state: "inactive" }; ttl = VERIFY_NEG_CACHE_TTL_S; }
	} else if (res.status === 400 || res.status === 404) {
		result = { state: "invalid" };
	} else if (res.status === 410) {
		// Süresi çok önce dolmuş abonelik jetonu.
		result = { state: "inactive" };
	} else {
		if (res.status === 401) gTokenMem = null;
		throw new HttpError(502, "server_error");
	}
	if (result.state === "active") ttl = Math.max(60, Math.min(ttl, Math.floor((result.expiryMs - nowMs) / 1000)));
	try { await env.ALTYAZI_KV.put(ck, JSON.stringify(result), { expirationTtl: ttl }); } catch {}
	return result;
}

// ---------------------------------------------------------------- kota (KV, sonunda tutarlı)

/** İsolate içi bekleyen artışlar: KV aynı anahtara saniyede 1 yazmaya izin verir. */
/** @type {Map<string, {pending: number, lastFlush: number, base: number}>} */
const quotaMem = new Map();
const QUOTA_FLUSH_MS = 1500;

/** @param {string} sub @param {number} nowMs */
export function quotaKey(sub, nowMs) {
	return "q:" + sub + ":" + monthKey(nowMs);
}

/** @param {Env} env @param {string} sub @param {number} nowMs */
export async function readQuotaUsed(env, sub, nowMs) {
	const key = quotaKey(sub, nowMs);
	let base = 0;
	try { base = Number.parseInt((await env.ALTYAZI_KV.get(key)) || "0", 10) || 0; } catch {}
	const m = quotaMem.get(key);
	if (m) {
		m.base = Math.max(m.base, base);
		return m.base + m.pending;
	}
	return base;
}

/**
 * Bir satır sayar. KV yazımı en fazla ~1,5 sn'de bir; yazılamayanlar bellekte bekler.
 * @param {Env} env @param {string} sub @param {number} nowMs
 * @returns {{used: number, flush: Promise<void>|null}}
 */
export function incrementQuota(env, sub, nowMs) {
	const key = quotaKey(sub, nowMs);
	let m = quotaMem.get(key);
	if (!m) {
		if (quotaMem.size > 20000) quotaMem.clear();
		m = { pending: 0, lastFlush: 0, base: 0 };
		quotaMem.set(key, m);
	}
	m.pending++;
	const used = m.base + m.pending;
	if (nowMs - m.lastFlush < QUOTA_FLUSH_MS) return { used, flush: null };
	m.lastFlush = nowMs;
	const add = m.pending;
	m.pending = 0;
	const entry = m;
	const flush = (async () => {
		try {
			const cur = Number.parseInt((await env.ALTYAZI_KV.get(key)) || "0", 10) || 0;
			const next = Math.max(cur, entry.base) + add;
			await env.ALTYAZI_KV.put(key, String(next), { expirationTtl: 62 * 24 * 3600 });
			entry.base = next;
		} catch {
			entry.pending += add; // sonraki istekte yeniden denenir
		}
	})();
	return { used, flush };
}

/** @param {number} used @param {number} limit @param {number} nowMs @returns {Quota} */
function quotaObj(used, limit, nowMs) {
	return { used, limit, resetAt: nextMonthStart(nowMs) };
}

// ---------------------------------------------------------------- Gemini

/** Modele göre en düşük düşünme ayarı (uygulamadaki eşleme ile aynı). null = gönderme. */
export function thinkingConfig(model) {
	const m = model.toLowerCase();
	if (m.startsWith("gemini-2.5-flash")) return { thinkingBudget: 0 };
	if (m.startsWith("gemini-2.") || m.startsWith("gemini-1.")) return null;
	if (m.includes("flash-lite")) return { thinkingLevel: "minimal" };
	if (m.startsWith("gemini-3.6-flash") || m === "gemini-3-flash" || m.startsWith("gemini-3-flash-")) return { thinkingLevel: "minimal" };
	return { thinkingLevel: "low" };
}

/** @param {string} text @param {Pair[]} context @param {string|null} speaker */
export function userPrompt(text, context, speaker) {
	let s = "";
	const ctx = context.slice(-MAX_CONTEXT);
	if (ctx.length) {
		s += "Previous lines (context only):\n";
		for (const p of ctx) s += "EN: " + p.en + "\nTR: " + p.tr + "\n";
		s += "\n";
	}
	if (speaker) s += "Speaker: " + speaker + "\n";
	return s + "LINE: " + text;
}

/** @param {string} model @param {string} text @param {Pair[]} context @param {string|null} speaker @param {boolean} withThinking */
export function geminiBody(model, text, context, speaker, withThinking) {
	/** @type {any} */
	const gc = { maxOutputTokens: MAX_OUTPUT_TOKENS, candidateCount: 1 };
	const t = withThinking ? thinkingConfig(model) : null;
	if (t) gc.thinkingConfig = t;
	return JSON.stringify({
		systemInstruction: { parts: [{ text: SYSTEM_PROMPT }] },
		contents: [{ role: "user", parts: [{ text: userPrompt(text, context, speaker) }] }],
		generationConfig: gc,
	});
}

const PREFIXES = ["TR:", "Turkish:", "Translation:", "Türkçe:", "Çeviri:", "LINE:"];
const QUOTES = [["\"", "\""], ["“", "”"], ["«", "»"], ["'", "'"]];

/** Modelin eklediği tırnak, önek, kod çiti ve satır sonlarını temizler (uygulamadaki cleanOutput). */
export function cleanOutput(raw) {
	let s = raw.trim();
	if (s.startsWith("```")) {
		s = s.slice(3);
		const nl = s.indexOf("\n");
		if (nl >= 0) s = s.slice(nl + 1);
		if (s.endsWith("```")) s = s.slice(0, -3);
		s = s.trim();
	}
	// İki tur: hem «"TR: x"» hem «TR: "x"» biçimleri temizlensin.
	for (let round = 0; round < 2; round++) {
		for (const p of PREFIXES) if (s.toLowerCase().startsWith(p.toLowerCase())) s = s.slice(p.length).trim();
		for (const [o, c] of QUOTES) if (s.length >= 2 && s.startsWith(o) && s.endsWith(c)) s = s.slice(o.length, s.length - c.length).trim();
	}
	return s.replace(/\s*\n\s*/g, " ").trim();
}

/**
 * Yanıt (ya da SSE parçası) içindeki düşünce olmayan metin.
 * @param {any} r
 * @returns {{text: string, blocked?: string, error?: string}}
 */
export function extractText(r) {
	if (!r || typeof r !== "object") return { text: "", error: "bad_json" };
	if (r.error) return { text: "", error: "api_error" };
	const c = Array.isArray(r.candidates) ? r.candidates[0] : null;
	if (!c) {
		const b = r.promptFeedback && r.promptFeedback.blockReason;
		return b ? { text: "", blocked: String(b) } : { text: "" };
	}
	const parts = c.content && Array.isArray(c.content.parts) ? c.content.parts : [];
	let t = "";
	for (const p of parts) if (p && p.thought !== true && typeof p.text === "string") t += p.text;
	return { text: t };
}

/**
 * Akışta ilk birkaç karakteri tamponlayıp önek/tırnak/kod çitini atar; sondaki tırnak/boşlukları
 * bir sonraki parçaya kadar tutar. Nihai metin her zaman cleanOutput(tam ham metin)'dir.
 */
export class StreamCleaner {
	constructor() {
		this.head = "";
		this.started = false;
		this.tail = "";
	}
	/** @param {string} delta @returns {string} gönderilecek parça */
	push(delta) {
		let s = delta.replace(/\s*\n\s*/g, " ");
		if (!this.started) {
			this.head += s;
			if (this.head.length < 16) return "";
			this.started = true;
			s = this.cleanHead(this.head);
			this.head = "";
		}
		s = this.tail + s;
		const m = /[\s"'”»`]+$/.exec(s);
		this.tail = m ? m[0] : "";
		return m ? s.slice(0, m.index) : s;
	}
	/** Akış bitti: tamponda kalan başlangıç (kısa yanıtlar). */
	finish() {
		if (this.started) return "";
		this.started = true;
		const s = this.cleanHead(this.head).replace(/[\s"'”»`]+$/, "");
		this.head = "";
		return s;
	}
	/** @param {string} h */
	cleanHead(h) {
		let s = h.replace(/^\s+/, "");
		if (s.startsWith("```")) s = s.replace(/^```[a-zA-Z]*\s*/, "");
		for (const p of PREFIXES) if (s.toLowerCase().startsWith(p.toLowerCase())) s = s.slice(p.length).replace(/^\s+/, "");
		s = s.replace(/^["“«']\s*/, "");
		for (const p of PREFIXES) if (s.toLowerCase().startsWith(p.toLowerCase())) s = s.slice(p.length).replace(/^\s+/, "");
		return s;
	}
}

/** thinkingConfig'i reddeden modeller (isolate ömrü boyunca). */
const noThinking = new Set();

/**
 * Gemini'ye istek; thinkingConfig reddedilirse onsuz bir kez daha dener.
 * @param {Env} env @param {string} model @param {boolean} stream @param {string} text @param {Pair[]} context @param {string|null} speaker @param {AbortSignal} signal
 */
export async function callGemini(env, model, stream, text, context, speaker, signal) {
	const url = GEMINI_BASE + encodeURIComponent(model) + (stream ? ":streamGenerateContent?alt=sse" : ":generateContent");
	const headers = { "content-type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY || "" };
	const withT = !noThinking.has(model);
	let res = await fetch(url, { method: "POST", headers, body: geminiBody(model, text, context, speaker, withT), signal });
	if (res.status === 400 && withT && thinkingConfig(model)) {
		const body = await res.text();
		if (/thinking/i.test(body)) {
			noThinking.add(model);
			res = await fetch(url, { method: "POST", headers, body: geminiBody(model, text, context, speaker, false), signal });
		} else {
			return new Response(body, { status: 400 });
		}
	}
	return res;
}

/**
 * Gemini SSE gövdesini olaylara ayırır; her "data:" JSON'unu verir.
 * @param {ReadableStream<Uint8Array>} body
 * @returns {AsyncGenerator<any>}
 */
export async function* parseSse(body) {
	const reader = body.getReader();
	const td = new TextDecoder();
	let buf = "";
	try {
		for (;;) {
			const { done, value } = await reader.read();
			if (done) break;
			buf += td.decode(value, { stream: true });
			let mm;
			while ((mm = /\r?\n\r?\n/.exec(buf))) {
				const ev = buf.slice(0, mm.index);
				buf = buf.slice(mm.index + mm[0].length);
				const data = sseData(ev);
				if (data !== null) yield data;
			}
		}
		buf += td.decode();
		const data = sseData(buf);
		if (data !== null) yield data;
	} finally {
		try { reader.releaseLock(); } catch {}
	}
}

/** @param {string} ev */
function sseData(ev) {
	const lines = ev.split(/\r?\n/).filter((l) => l.startsWith("data:")).map((l) => l.slice(5).replace(/^ /, ""));
	if (!lines.length) return null;
	const d = lines.join("\n");
	if (d === "[DONE]") return null;
	try { return JSON.parse(d); } catch { return { error: "bad_json" }; }
}

// ---------------------------------------------------------------- çeviri önbelleği

/**
 * Önbellek anahtarı. Kısa satırlar (≤ 6 kelime: menü/arayüz/kısa replikler) ve bağlamsız satırlar yalnızca
 * metne göre; uzun satırlar son bağlam çiftinin özetini de içerir (aynı cümle farklı sahnede farklı çevrilebilir).
 * @param {string} model @param {string} text @param {Pair[]} context @param {string|null} speaker
 */
export async function cacheKey(model, text, context, speaker) {
	let material = "v1\n" + model + "\n" + (speaker || "") + "\n" + text;
	if (context.length && wordCount(text) > SHORT_LINE_WORDS) {
		const last = context[context.length - 1];
		material += "\n" + last.en + "\n" + last.tr;
	}
	return "tr:v1:" + (await sha256Hex(material));
}

// ---------------------------------------------------------------- yol işleyicileri

/** @param {Request} request @param {Env} env @param {number} now */
async function handleSession(request, env, now) {
	const ip = request.headers.get("cf-connecting-ip") || "unknown";
	if (!rateLimit("s:" + ip, 6, 0.2, now)) throw new HttpError(429, "rate_limited");
	const v = validateSessionBody(await readJsonBody(request));
	if (!v.ok) throw new HttpError(v.status, v.code);
	if (!env.SESSION_SECRET || env.SESSION_SECRET.length < 16) throw new HttpError(500, "server_error");
	const tokenHash = await sha256Hex(v.purchaseToken);
	const r = await verifyPurchase(env, v.purchaseToken, tokenHash, now);
	if (r.state === "invalid") throw new HttpError(400, "invalid_purchase");
	if (r.state !== "active") throw new HttpError(403, "not_subscribed");
	const sub = tokenHash.slice(0, 32);
	const exp = Math.min(now + SESSION_TTL_MS, r.expiryMs);
	const sessionToken = await signSession(env.SESSION_SECRET, sub, exp);
	const limit = intEnv(env.MONTHLY_LINE_LIMIT, DEFAULT_LIMIT, 1, 10_000_000);
	const used = await readQuotaUsed(env, sub, now);
	return json(200, { ok: true, sessionToken, expiresAt: exp, quota: quotaObj(used, limit, now) });
}

/** @param {Request} request @param {Env} env @param {Ctx} ctx @param {number} now */
async function handleTranslate(request, env, ctx, now) {
	const auth = request.headers.get("authorization") || "";
	const m = /^Bearer ([A-Za-z0-9_.-]{1,512})$/.exec(auth);
	if (!m || !env.SESSION_SECRET) throw new HttpError(401, "unauthorized");
	const sess = await verifySession(env.SESSION_SECRET, m[1], now);
	if (!sess) throw new HttpError(401, "unauthorized");
	if (!rateLimit("t:" + sess.sub, 10, 10, now)) throw new HttpError(429, "rate_limited");

	const maxChars = intEnv(env.MAX_TEXT_CHARS, MAX_TEXT_HARD, 1, MAX_TEXT_HARD);
	const v = validateTranslateBody(await readJsonBody(request), maxChars);
	if (!v.ok) throw new HttpError(400, "bad_request");
	const streaming = (request.headers.get("accept") || "").toLowerCase().includes("application/x-ndjson");
	const model = /^[a-z0-9.-]{3,64}$/.test(env.GEMINI_MODEL || "") ? /** @type {string} */ (env.GEMINI_MODEL) : DEFAULT_MODEL;
	const limit = intEnv(env.MONTHLY_LINE_LIMIT, DEFAULT_LIMIT, 1, 10_000_000);

	const ck = await cacheKey(model, v.text, v.context, v.speaker);
	/** @type {string|null} */
	let hit = null;
	try { hit = await env.ALTYAZI_KV.get(ck, { cacheTtl: 3600 }); } catch {}
	const usedBefore = await readQuotaUsed(env, sess.sub, now);
	if (hit) {
		const q = quotaObj(usedBefore, limit, now);
		if (streaming) return ndjson([{ done: true, text: hit, quota: q, cached: true }]);
		return json(200, { ok: true, text: hit, quota: q, cached: true });
	}
	if (usedBefore >= limit) {
		const d = new Date(nextMonthStart(now));
		const when = String(d.getUTCDate()).padStart(2, "0") + "." + String(d.getUTCMonth() + 1).padStart(2, "0") + "." + d.getUTCFullYear();
		throw new HttpError(429, "quota_exceeded", `Bu ayki ${limit} satırlık çeviri sınırına ulaşıldı. Sınır ${when} tarihinde sıfırlanır.`);
	}
	if (!env.GEMINI_API_KEY) throw new HttpError(502, "upstream_error");

	const timeoutMs = intEnv(env.UPSTREAM_TIMEOUT_MS, DEFAULT_TIMEOUT_MS, 50, 30_000);
	const ac = new AbortController();
	const timer = setTimeout(() => ac.abort(new Error("timeout")), timeoutMs);

	/** Başarılı çeviriyi önbelleğe yaz + kotayı say. */
	const commit = (/** @type {string} */ text) => {
		const inc = incrementQuota(env, sess.sub, now);
		const tasks = [env.ALTYAZI_KV.put(ck, text, { expirationTtl: TR_CACHE_TTL_S }).catch(() => {})];
		if (inc.flush) tasks.push(inc.flush);
		ctx.waitUntil(Promise.all(tasks));
		return quotaObj(inc.used, limit, now);
	};

	let res;
	try {
		res = await callGemini(env, model, streaming, v.text, v.context, v.speaker, ac.signal);
	} catch {
		clearTimeout(timer);
		throw new HttpError(502, "upstream_error");
	}
	if (res.status !== 200 || !res.body) {
		clearTimeout(timer);
		try { await res.body?.cancel(); } catch {}
		throw new HttpError(502, "upstream_error", undefined);
	}

	if (!streaming) {
		try {
			const r = extractText(await res.json());
			const text = cleanOutput(r.text);
			if (!text) throw new Error("empty");
			return json(200, { ok: true, text, quota: commit(text), cached: false });
		} catch {
			throw new HttpError(502, "upstream_error");
		} finally {
			clearTimeout(timer);
		}
	}

	// Akış: Gemini SSE → NDJSON
	const upstream = res.body;
	const { readable, writable } = new TransformStream();
	const writer = writable.getWriter();
	const line = (/** @type {any} */ o) => writer.write(enc.encode(JSON.stringify(o) + "\n"));
	const pump = (async () => {
		const cleaner = new StreamCleaner();
		let raw = "";
		try {
			for await (const ev of parseSse(upstream)) {
				const r = extractText(ev);
				if (r.error || r.blocked) throw new Error("upstream");
				if (!r.text) continue;
				raw += r.text;
				const d = cleaner.push(r.text);
				if (d) await line({ d });
			}
			const rest = cleaner.finish();
			if (rest) await line({ d: rest });
			const text = cleanOutput(raw);
			if (!text) throw new Error("empty");
			clearTimeout(timer);
			await line({ done: true, text, quota: commit(text), cached: false });
		} catch {
			clearTimeout(timer);
			ac.abort();
			try { await line({ error: "upstream_error", message: MESSAGES.upstream_error }); } catch {}
		} finally {
			try { await writer.close(); } catch {}
		}
	})();
	ctx.waitUntil(pump);
	return new Response(readable, {
		status: 200,
		headers: { "content-type": "application/x-ndjson; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff" },
	});
}

/** @param {any[]} objs */
function ndjson(objs) {
	return new Response(objs.map((o) => JSON.stringify(o) + "\n").join(""), {
		status: 200,
		headers: { "content-type": "application/x-ndjson; charset=utf-8", "cache-control": "no-store" },
	});
}

/** Yalnızca meta veri log'lanır (metin asla). */
function log(route, status, ms, code) {
	console.log(JSON.stringify({ route, status, ms, code: code || undefined }));
}

/**
 * @param {Request} request @param {Env} env @param {Ctx} ctx
 * @returns {Promise<Response>}
 */
export async function handle(request, env, ctx) {
	const t0 = Date.now();
	const url = new URL(request.url);
	const path = url.pathname;
	/** @type {Response} */
	let res;
	let code = "";
	try {
		if (request.method === "OPTIONS") res = fail(405, "method_not_allowed");
		else if (path === "/v1/health") res = request.method === "GET" || request.method === "HEAD" ? json(200, { ok: true, version: API_VERSION }) : fail(405, "method_not_allowed");
		else if (path === "/v1/session") res = request.method === "POST" ? await handleSession(request, env, t0) : fail(405, "method_not_allowed");
		else if (path === "/v1/translate") res = request.method === "POST" ? await handleTranslate(request, env, ctx, t0) : fail(405, "method_not_allowed");
		else res = fail(404, "not_found");
	} catch (e) {
		if (e instanceof HttpError) {
			code = e.code;
			res = fail(e.status, e.code, e.userMessage);
		} else {
			code = "server_error";
			console.log(JSON.stringify({ route: path, err: String(e && /** @type {any} */ (e).name || "Error") }));
			res = fail(500, "server_error");
		}
	}
	log(path.startsWith("/v1/") ? path : "other", res.status, Date.now() - t0, code);
	return res;
}

/** Testler için isolate içi durumu sıfırlar. */
export function _resetState() {
	buckets.clear();
	quotaMem.clear();
	noThinking.clear();
	gTokenMem = null;
	rsaKeyMem = null;
	hmacKeys.clear();
}

export default {
	/** @param {Request} request @param {Env} env @param {Ctx} ctx */
	fetch(request, env, ctx) {
		return handle(request, env, ctx);
	},
};
