# AltyazıTR Pro sunucusu — Kurulum rehberi

Bu rehber, aylık **AltyazıTR Pro** aboneliği için çeviri sunucusunu kurmanızı adım adım anlatır.
Kod bilmeniz gerekmez; yalnızca birkaç web sitesinde tıklayıp kopyala-yapıştır yapacaksınız.
Toplam süre: yaklaşık 45–60 dakika (Google'ın izinleri etkinleştirmesi bazen birkaç saat sürer).

**Sunucu ne yapar?** Uygulama, abonenin altyazı satırını sunucuya gönderir; sunucu önce Google Play'den
"bu kişinin aboneliği gerçekten aktif mi?" diye sorar, sonra satırı sizin Gemini anahtarınızla çevirip
geri yollar. Gemini anahtarınız kullanıcıların telefonuna/TV'sine hiç gitmez. Aynı satır daha önce
çevrildiyse (menü yazıları, sık replikler) sunucu Gemini'ye hiç sormadan anında cevap verir.

Sunucu **Cloudflare Workers** üzerinde çalışır (bilgisayarınızın açık olması gerekmez).

Toplayacağınız 5 bilgi (hepsini en sonda GitHub'a gireceksiniz):

| GitHub gizli değeri adı | Nereden? | Adım |
|---|---|---|
| `CLOUDFLARE_API_TOKEN` | Cloudflare | A |
| `CLOUDFLARE_ACCOUNT_ID` | Cloudflare | A |
| `GOOGLE_SERVICE_ACCOUNT_JSON` | Google Cloud | C |
| `GEMINI_API_KEY` | Google AI Studio | E |
| `PRO_API_BASE_URL` | Kurulum bitince çıkan adres | F–G |

> Bu değerleri bir not defterinde geçici olarak tutabilirsiniz; GitHub'a girdikten sonra notu silin.
> Kimseyle paylaşmayın, ekran görüntüsü almayın.

---

## A) Cloudflare hesabı, API jetonu ve Hesap Kimliği (Account ID)

1. **https://dash.cloudflare.com/sign-up** adresine gidin. E-posta ve şifre ile **ücretsiz** hesap açın,
   e-postanıza gelen doğrulama bağlantısına tıklayın. (Alan adı eklemeniz istenirse atlayın; gerekmez.)
2. Sol menüden **Workers & Pages** (Türkçe arayüzde "Compute (Workers)" / "Workers ve Pages") sayfasını
   bir kez açın. Sizden bir **workers.dev alt alan adı** seçmeniz istenirse kısa bir ad yazın
   (örn. `altyazitr`) ve onaylayın. Sunucunuzun adresi bundan oluşur:
   `https://altyazitr-api.<seçtiğiniz-ad>.workers.dev`
3. **Hesap Kimliği (Account ID):** Aynı **Workers & Pages** sayfasında sağ tarafta **Account ID** yazar;
   yanındaki kopyala simgesine tıklayın. (Alternatif: hesap ana sayfasında hesap adınızın yanındaki **⋯**
   menüsü → **Copy account ID**.) Bu 32 karakterlik değer = `CLOUDFLARE_ACCOUNT_ID`.
4. **API jetonu:** Sağ üstteki kişi simgesi → **My Profile** (Profilim) → soldan **API Tokens** →
   **Create Token** düğmesi.
5. Listede **Edit Cloudflare Workers** şablonunun yanındaki **Use template** düğmesine tıklayın.
6. **Account Resources** satırında: `Include` → kendi hesabınız.
   **Zone Resources** satırında: `Include` → `All zones` (alan adınız yoksa sorun değil).
   Diğer ayarlara dokunmayın (şablon, Workers ve **Workers KV Storage** düzenleme iznini zaten içerir).
7. **Continue to summary** → **Create Token**. Ekranda bir kez gösterilen uzun jetonu kopyalayın.
   Bu = `CLOUDFLARE_API_TOKEN`. (Kaybederseniz aynı sayfadan yenisini oluşturabilirsiniz.)

## B) KV deposu (otomatik)

Sunucu, çeviri önbelleği ve aylık kota sayaçları için Cloudflare **KV** deposunu kullanır.
**Elle bir şey yapmanız gerekmez:** dağıtım iş akışı `altyazitr-api-ALTYAZI_KV` adlı depoyu ilk
çalıştırmada kendisi oluşturur, sonraki çalıştırmalarda aynısını bulur.

## C) Google Cloud: proje, Play API ve hizmet hesabı

Bu adım, sunucunun "abonelik aktif mi?" diye Google Play'e sorabilmesi içindir.

1. **https://console.cloud.google.com** adresine Play Console'da kullandığınız Google hesabıyla girin.
2. Üstteki proje seçicisine tıklayın → **New Project** (Yeni Proje) → ad: `altyazitr` → **Create**.
   Oluşunca üstteki seçiciden bu projeyi seçili hale getirin.
3. Üstteki arama kutusuna **Google Play Android Developer API** yazın, çıkan sonuca tıklayın →
   **Enable** (Etkinleştir).
4. Sol menü → **IAM & Admin** (IAM ve Yönetici) → **Service Accounts** (Hizmet Hesapları) →
   **+ Create service account**.
   - Ad: `altyazitr-play` → **Create and continue**.
   - "Grant this service account access to project" (rol) adımını **boş bırakın** → **Continue** → **Done**.
5. Listede yeni hesabın e-postasını görürsünüz (ör. `altyazitr-play@altyazitr.iam.gserviceaccount.com`).
   **Bu e-postayı kopyalayın** — D adımında lazım.
6. Hesabın satırına tıklayın → üstte **Keys** (Anahtarlar) sekmesi → **Add key** → **Create new key** →
   **JSON** → **Create**. Bilgisayarınıza bir `.json` dosyası iner.
   - Dosyayı Not Defteri (Windows) / TextEdit (Mac) ile açın; **içeriğin tamamı**
     (`{` ile başlayıp `}` ile biten) = `GOOGLE_SERVICE_ACCOUNT_JSON`.
   - Not: Kuruluşunuz anahtar oluşturmayı engellediyse ("iam.disableServiceAccountKeyCreation"),
     kişisel bir Google hesabıyla oluşturulan projede tekrar deneyin.

## D) Play Console: hizmet hesabına izin verme

1. **https://play.google.com/console** → sol menü (uygulama listesinin olduğu ana ekranda)
   **Users and permissions** (Kullanıcılar ve izinler).
2. **Invite new users** (Yeni kullanıcı davet et).
3. **Email address**: C-5'te kopyaladığınız hizmet hesabı e-postası.
4. **App permissions** (Uygulama izinleri) sekmesi → **Add app** → **AltyazıTR Oyun**'u seçin → **Apply**.
   Açılan listede yalnızca şu ikisini işaretleyin:
   - **View financial data, orders, and cancellation survey responses**
     (Finansal verileri, siparişleri ve iptal anketi yanıtlarını görüntüleme)
   - **Manage orders and subscriptions** (Siparişleri ve abonelikleri yönetme)
5. **Apply** → **Invite user** → onaylayın.
6. Google'ın bu izni etkinleştirmesi **birkaç dakika ile 24 saat** arası sürebilir. Bu sürede uygulamada
   "Satın alma doğrulanamadı / Sunucu hatası" görmeniz normaldir.

> Google'ın güncel belgesi: https://developers.google.com/android-publisher/getting_started
> (Artık Play Console'u Cloud projesine "bağlamak" gerekmiyor; yukarıdaki davet yeterli.)

## E) Gemini API anahtarı (faturalı proje)

**Önemli:** Ücretsiz Gemini kotasında gönderilen veriler Google tarafından ürün geliştirmede
kullanılabilir. Kullanıcılarınızın altyazılarını korumak için anahtar **faturalandırması açık
(ücretli katman)** bir projede olmalıdır; ücretli katmanda veriler eğitimde kullanılmaz.

1. **https://aistudio.google.com/apikey** adresine gidin (aynı Google hesabı).
2. **Create API key** → proje olarak C adımında açtığınız `altyazitr` projesini seçin → oluşturun.
3. Anahtarın yanında **Set up billing** / "Free tier" yazıyorsa ona tıklayın ve bir ödeme yöntemi
   ekleyerek faturalandırmayı açın. Bitince anahtarın satırında "Paid"/ücretli katman görünmelidir.
4. Anahtarı kopyalayın (`AIza...` ile başlar) = `GEMINI_API_KEY`.
5. **Tavsiye — bütçe uyarısı:** https://console.cloud.google.com/billing → **Budgets & alerts** →
   **Create budget** → ör. aylık 20 $; %50/%90/%100'de e-posta uyarısı.

## F) GitHub'a gizli değerleri girme ve sunucuyu yayınlama

1. GitHub'da depo sayfanız → **Settings** → soldan **Secrets and variables** → **Actions** →
   **New repository secret**. Her biri için **Name** ve **Secret** alanlarını doldurup **Add secret**:
   - `CLOUDFLARE_API_TOKEN` (A-7)
   - `CLOUDFLARE_ACCOUNT_ID` (A-3)
   - `GOOGLE_SERVICE_ACCOUNT_JSON` (C-6: JSON dosyasının tüm içeriği, olduğu gibi)
   - `GEMINI_API_KEY` (E-4)
   - `SESSION_SECRET` — **isteğe bağlı.** Girmezseniz ilk yayınlamada otomatik rastgele üretilip
     Cloudflare'de saklanır. (Değiştirirseniz açık oturumlar bir kez yenilenir; sorun olmaz.)
2. Depoda **Actions** sekmesi → soldan **Sunucu (AltyazıTR Pro)** → sağda **Run workflow** →
   dal `main` → yeşil **Run workflow**.
3. 1–3 dakika bekleyin. Yeşil tik ✓ çıkınca iş akışına tıklayın; **Summary** (Özet) bölümünde
   **API adresi** yazar, ör. `https://altyazitr-api.altyazitr.workers.dev`.
4. **Test:** Tarayıcıda adresin sonuna `/v1/health` ekleyip açın:
   `https://altyazitr-api.altyazitr.workers.dev/v1/health`
   Ekranda `{"ok":true,"version":"1"}` görüyorsanız sunucu çalışıyor.

Kırmızı ✗ çıkarsa: iş akışına tıklayın, kırmızı adımı açın; en alttaki Türkçe hata mesajı genelde
hangi gizli değerin eksik/yanlış olduğunu söyler. Değeri düzeltip **Run workflow**'u tekrarlayın.

Sonraki güncellemeler: `server/` klasöründe `main` dalına bir değişiklik geldiğinde sunucu
otomatik yeniden yayınlanır. Gizli değerleri değiştirdiyseniz **Run workflow** ile elle yayınlayın.

## G) API adresini uygulamaya tanıtma

1. F-3'teki adresi kopyalayın (**sonunda `/` olmadan**).
2. GitHub → **Settings → Secrets and variables → Actions → New repository secret**:
   Name: `PRO_API_BASE_URL`, Secret: adres.
3. Android derleme iş akışı (**Android (AltyazıTR)**) bu değeri Gradle özelliği `proApiBaseUrl`
   olarak uygulamaya gömer (`BuildConfig.PRO_API_BASE_URL`). Boş bırakılırsa uygulamada Pro
   "henüz yapılandırılmadı" görünür. Bilgisayarda elle derliyorsanız:
   `./gradlew assembleRelease -PproApiBaseUrl=https://altyazitr-api.altyazitr.workers.dev`
4. Uygulamayı yeniden derleyip Play Console'a yükleyin.

Test aboneliği: Play Console → **Setup → License testing** bölümüne kendi Gmail adresinizi ekleyin;
test satın alımları ücret almaz ve sunucu bunları gerçek abonelik gibi kabul eder.

---

## Ayarlar (isteğe bağlı)

`server/wrangler.toml` dosyasındaki `[vars]` bölümü (GitHub'da dosyayı açıp kalem simgesiyle düzenleyin,
kaydedince sunucu otomatik yeniden yayınlanır):

| Ayar | Varsayılan | Anlamı |
|---|---|---|
| `GEMINI_MODEL` | `gemini-3.5-flash-lite` | Çeviri modeli (en hızlı/ucuz Flash-Lite önerilir) |
| `MONTHLY_LINE_LIMIT` | `30000` | Abone başına aylık çeviri satırı sınırı (önbellekten gelenler sayılmaz) |
| `MAX_TEXT_CHARS` | `600` | Tek satır için en fazla karakter (600'den büyük olamaz) |

Cloudflare panelinden değiştirdiğiniz değişkenler bir sonraki yayınlamada bu dosyadakilerle ezilir;
kalıcı değişikliği dosyada yapın.

---

## Maliyet

### Gemini (asıl maliyet)

Fiyat (ücretli katman, Ekim 2026, https://ai.google.dev/gemini-api/docs/pricing):
**Gemini 3.5 Flash-Lite: giriş 0,30 $ / 1 milyon jeton, çıkış 2,50 $ / 1 milyon jeton.**
(Jeton ≈ 3–4 karakter; Türkçe biraz daha fazla jeton tutar.)

Bir satırın maliyeti:

```
maliyet ≈ (giriş_jetonu × 0,30 + çıkış_jetonu × 2,50) / 1.000.000  $
giriş  ≈ 140 (sistem talimatı) + 30–150 (önceki 0–4 satır bağlamı) + 15 (satır)  ≈ 200–300 jeton
çıkış  ≈ 20–40 (Türkçe çeviri) + birkaç "minimal" düşünme jetonu              ≈ 30–50 jeton
```

| | Satır başı | 1.000 satır | 10.000 satır | 30.000 satır (tam kota) |
|---|---|---|---|---|
| Tipik (~270 giriş + ~45 çıkış) | ~0,0002 $ | ~0,19 $ | ~1,9 $ | ~5,7 $ |

- Sunucu önbelleği aynı satırı ikinci kez **ücretsiz** verir (menüler, "Press X", sık replikler);
  gerçek maliyet tabloda yazandan genelde %10–30 daha düşüktür.
- 2 saatlik bir oyun oturumu kabaca 500–1.500 altyazı satırıdır → ~0,10–0,30 $.
- Aylık abonelik fiyatını belirlerken: Google Play komisyonu (%15) + en yoğun kullanıcının maliyeti.
  30.000 satırlık kota, en kötü durumda abone başına ~6 $/ay demektir; fiyat buna göre düşükse
  `MONTHLY_LINE_LIMIT` değerini düşürün (ör. 15.000 ≈ 3 $).

### Cloudflare Workers + KV

| | Ücretsiz plan | Workers Paid (5 $/ay) |
|---|---|---|
| İstek | 100.000 / gün | 10 milyon / ay dahil, sonra 0,30 $ / milyon |
| KV okuma | 100.000 / gün | 10 milyon / ay dahil, sonra 0,50 $ / milyon |
| **KV yazma** | **1.000 / gün** | 1 milyon / ay dahil, sonra 5 $ / milyon |
| KV depolama | 1 GB | 1 GB dahil, sonra 0,50 $ / GB-ay |

(Kaynak: https://developers.cloudflare.com/workers/platform/pricing/ ,
https://developers.cloudflare.com/kv/platform/limits/)

Her **yeni** (önbellekte olmayan) çeviri ~1–2 KV yazması yapar (önbellek kaydı + kota sayacı).
Bu yüzden ücretsiz plan günde yalnızca **~500–1.000 yeni satıra** yeter — yani test için yeterli,
ama ilk gerçek abonelerle birlikte **Workers Paid (5 $/ay)** planına geçin:
Cloudflare → **Workers & Pages → Plans** → **Workers Paid**. Ücretli planda ayda ~500.000+ yeni satır
5 $'a dahildir; bunun üstü her ek milyon yazma için 5 $.
Ücretsiz planda yazma sınırı aşılırsa çeviriler yine çalışır ama o gün önbellek ve kota sayımı
kaydedilemez.

### Google Play Developer API

Abonelik doğrulaması ücretsizdir (sonuçlar 10 dakika önbelleğe alınır; oturum başına en fazla 1 sorgu).

---

## Bilinen sınırlamalar

- **Kota yaklaşıktır:** KV "sonunda tutarlı" bir depodur ve aynı anahtara saniyede en fazla 1 yazma
  kabul eder; sunucu sayacı en fazla ~1,5 sn'de bir yazar. Çok nadiren bir abonenin birkaç satırı
  sayılmayabilir ya da sınır birkaç satır geç devreye girebilir. Ödeme açısından önemsizdir.
- **Hız sınırı** (abone başına ~10 istek/sn) her Cloudflare sunucusunda ayrı tutulur; kaba bir korumadır.
- Abone aboneliğini iptal edip yeniden alırsa yeni bir satın alma jetonu oluşur ve o ayın kotası
  sıfırdan başlar.
- Sunucu altyazı metnini **asla** kayda (log) yazmaz; yalnızca yol, durum kodu, süre ve hata kodu.
