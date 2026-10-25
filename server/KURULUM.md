# AltyazıTR abonelik sunucusu — Kurulum rehberi

Bu rehber, **AltyazıTR aboneliği** (Standart / Pro / Premium paketleri, aylık veya yıllık, + 3 gün ücretsiz deneme
ve tek seferlik +2.000 satır ek paketi) için
çeviri sunucusunu kurmanızı adım adım anlatır.
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
2. Depoda **Actions** sekmesi → soldan **Sunucu (AltyazıTR Pro)** (iş akışının adı değişmedi) → sağda **Run workflow** →
   dal `main` → yeşil **Run workflow**.
3. 1–3 dakika bekleyin. Yeşil tik ✓ çıkınca iş akışına tıklayın; **Summary** (Özet) bölümünde
   **API adresi** yazar, ör. `https://altyazitr-api.altyazitr.workers.dev`.
4. **Test:** Tarayıcıda adresin sonuna `/v1/health` ekleyip açın:
   `https://altyazitr-api.altyazitr.workers.dev/v1/health`
   Ekranda `{"ok":true,"version":"1.2"}` görüyorsanız sunucu çalışıyor.

Kırmızı ✗ çıkarsa: iş akışına tıklayın, kırmızı adımı açın; en alttaki Türkçe hata mesajı genelde
hangi gizli değerin eksik/yanlış olduğunu söyler. Değeri düzeltip **Run workflow**'u tekrarlayın.

Sonraki güncellemeler: `server/` klasöründe `main` dalına bir değişiklik geldiğinde sunucu
otomatik yeniden yayınlanır. Gizli değerleri değiştirdiyseniz **Run workflow** ile elle yayınlayın.

## G) API adresini uygulamaya tanıtma

1. F-3'teki adresi kopyalayın (**sonunda `/` olmadan**).
2. GitHub → **Settings → Secrets and variables → Actions → New repository secret**:
   Name: `PRO_API_BASE_URL`, Secret: adres.
3. Android derleme iş akışı (**Android (AltyazıTR)**) bu değeri Gradle özelliği `proApiBaseUrl`
   olarak uygulamaya gömer (`BuildConfig.PRO_API_BASE_URL`). Boş bırakılırsa uygulamada abonelik
   "henüz hazır değil" görünür. Bilgisayarda elle derliyorsanız:
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
| `PLAN_LIMITS` | `{"standart":4500,"pro":10000,"premium":24000,"deneme":1500}` | Paket (Play temel plan kimliği) başına aylık yapay zekâ çeviri satırı; `deneme` = 3 günlük deneme boyunca toplam. Önbellekten gelenler ve cihaz içi çevrilen kısa satırlar sayılmaz. Yıllık planlar (`pro-yillik` …) aynı paketin aylık sınırını kullanır. Listeyi **tam** yazın: listede olmayan temel plan "Satın alma doğrulanamadı" alır. |
| `MAX_TEXT_CHARS` | `600` | Tek satır için en fazla karakter (600'den büyük olamaz) |

Cloudflare panelinden değiştirdiğiniz değişkenler bir sonraki yayınlamada bu dosyadakilerle ezilir;
kalıcı değişikliği dosyada yapın.

**Sınırları değiştirirseniz** uygulamadaki paket kartlarında yazan sayıları da güncelleyin
(`android/app/src/main/java/com/metallic/chiaki/billing/BillingLogic.kt` → `Plans`): kartlar bilgi amaçlıdır,
asıl sınırı sunucu uygular. Fiyatlar kodda değil, Play Console'dadır (`store/MAGAZA_VE_YAYIN.md`).

---

## Google Play: ek süre (grace period) ve hesap askıya alma (account hold)

Kartı reddedilen abonenin aboneliğinin hemen bitmemesi için (istem dışı ayrılmayı azaltır, kullanıcı bir şey yapmadan
ödemesi düzelince devam eder) her temel planda bu iki ayarın **açık** olduğundan emin olun. Google'a göre ikisi de yeni
otomatik yenilenen temel planlarda varsayılan olarak açıktır ve hesap bekletme süresi "60 gün − ek süre" olarak hesaplanır
(https://developer.android.com/google/play/billing/lifecycle/subscriptions); **kısaltmayın**, kısaltmak kurtarılan abonelikleri azaltır.

1. Play Console → **Para kazanma → Ürünler → Abonelikler** → `altyazitr_abonelik` → bir temel plana tıklayın
   (`standart`, `pro`, `premium`, `standart-yillik`, `pro-yillik`, `premium-yillik` — her biri için ayrı ayrı).
2. **Temel planı düzenle** → "Yenileme / Hesap bekletme ve ek süre" (*Grace period and account hold*) bölümü:
   - **Ek süre (Grace period):** açık; varsayılan süreyi koruyun (Play Console'da seçenek varsa yıllık planlarda en uzun değeri seçin).
     Ek süre boyunca abonelik etkin sayılır (sunucu `SUBSCRIPTION_STATE_IN_GRACE_PERIOD` durumunu kabul eder); kullanıcı
     çeviriyi kesintisiz kullanır, Play ödeme yöntemini düzeltmesini ister.
   - **Hesap bekletme (Account hold):** açık, varsayılan (otomatik hesaplanan) süre. Bekletmede abonelik
     etkin değildir (`SUBSCRIPTION_STATE_ON_HOLD` → sunucu "abonelik etkin değil" der, uygulama cihaz içi çeviriye döner);
     ödeme düzelince abonelik kendiliğinden geri gelir, yeniden satın alma gerekmez.
3. **Kaydet** (planı yeniden etkinleştirmeniz gerekmez). Play bu süreçte kullanıcıya kendisi e-posta/bildirim gönderir,
   ödemeyi otomatik yeniden dener; kullanıcı Play Store → Ödemeler ve abonelikler'den kartını değiştirebilir. Kod gerekmez.
4. "Dynamic Grace Period" gibi Play'in otomatik kurtarma özellikleri hesabınızda açıksa açık bırakın (toplam kurtarma süresini değiştirmez).

Not: Sunucu doğrulama sonucunu 10 dakika önbelleğe alır; ödeme düzelince çeviri en geç ~1 saat içinde (oturum yenilenince) yapay zekâya döner.

## Maliyet

### Gemini (asıl maliyet)

Fiyat (ücretli katman, Ekim 2026, https://ai.google.dev/gemini-api/docs/pricing):
**Gemini 3.5 Flash-Lite: giriş 0,30 $ / 1 milyon jeton, çıkış 2,50 $ / 1 milyon jeton.**
(Jeton ≈ 3–4 karakter; Türkçe biraz daha fazla jeton tutar.)

Bir satırın maliyeti:

```
maliyet ≈ (giriş_jetonu × 0,30 + çıkış_jetonu × 2,50) / 1.000.000  $
```

| | Önce (v1.1) | Şimdi (v1.2) | Neden |
|---|---|---|---|
| Sistem talimatı | ~140 | ~140 | değişmedi |
| Önceki satırlar (bağlam) | 30–150 (her satırda, ≤ 4 çift × 600 kar.) | ortalama ~15–20 (yalnızca ~%30–40 satırda, ≤ 2 çift × 160 kar.) | bağlam yalnızca zamir/gönderme olan ≥ 6 kelimelik satırlarda |
| Satırın kendisi | ~15 | ~20 | kısa satırlar artık gelmiyor, kalanlar ortalamada daha uzun |
| **Giriş toplamı** | ~200–300 (≈ 270) | **≈ 180** | |
| Çıkış (çeviri + "minimal" düşünme) | ~45 | ~45 | çeviri boyu aynı; `maxOutputTokens` = clamp(2×kelime+24, 32, 160) yalnızca kaçak/uzun yanıtı keser |
| **Sayılan satır başına** | **~0,00020 $** | **~0,00017 $** (−%15) | |

Tek sayılan satırın maliyeti az düşer (çıkış jetonları baskın); asıl kazanç **sunucuya hiç gitmeyen** satırlardadır:

- **Kısa satırlar cihaz içi:** ≤ 3 kelime / ≤ 18 karakter / büyük harfli arayüz yazıları / sayılar ("Yes.", "Get down!",
  "PRESS X TO CONTINUE", "120 / 250"). Tipik bir oyunda altyazı + arayüz satırlarının ~%25–35'i. Bunlar sunucuya gitmez,
  kotadan düşmez (aynı kalitede: 1–3 kelimede cihaz içi çeviri yapay zekâya denktir).
- **Daha akıllı sunucu önbelleği:** büyük/küçük harf, kıvrık/düz tırnak, "…"/"...", boşluk, sondaki nokta/virgül, OCR kırıntısı
  ("|", "_" …) farkları ve konuşmacı etiketi ("JOEL:") aynı anahtara düşer; bağlam yalnızca gerçekten kullanıldığında anahtara girer;
  önbellek 90 gün tutulur; her Worker isolate'inde en sıcak 2.000 satır bellekte. Tahmini ek isabet: **+5–10 puan**.

**Saatlik tahmin** (diyaloğu yoğun oyun, saatte ~450 satır, önbellek yok):
önce 450 × 0,00020 = **0,090 $/saat** → şimdi ~%30 kısa satır cihaz içi: 315 × 0,00017 = **~0,054 $/saat (≈ −%40)**.
%40 önbellekle: önce ~0,054 $/saat → şimdi ~0,030 $/saat (önbellek oranı da artınca daha az).
Bunlar tahmindir; gerçek oranı aşağıdaki sayaçlardan ölçün.

**Önbellek ölçümü:** Sunucu her gün için yalnızca sayıları tutar (metin yok): KV anahtarı `st:YYYY-MM-DD` =
`{"mem": isolate belleği isabeti, "kv": KV isabeti, "miss": Gemini'ye giden}`. Okumak için Cloudflare → **Workers & Pages →
KV → altyazitr-api-ALTYAZI_KV → `st:2026-10-25`** (veya `npx wrangler kv key get --binding ALTYAZI_KV st:2026-10-25`).
Önbellek oranı = (mem + kv) / (mem + kv + miss). Kısa satırlar uygulamada kaldığı için bu sayılara hiç girmez.

### Paket başına maliyet ve kâr (abone başına, aylık)

Varsayımlar: **USD/TRY 49,69**; fiyattan **%20 KDV** ve **%15 Google Play komisyonu** düşülür → net = fiyat ÷ 1,20 × 0,85
(yıllıkta net ÷ 12 = aylık karşılığı). "En kötü durum" = abone her ay paketinin **tamamını** önbelleğe hiç denk gelmeden kullanır;
iki satır maliyetiyle hesaplandı: **0,00020 $** (eski, temkinli) ve **0,00017 $** (v1.2 tahmini). "%40 önbellek" = 0,00017 $ ile,
satırların %40'ı sunucu önbelleğinden. Fiyatlar öneridir, Play Console'da girilir. Yıllık planlarda da satır hakkı **aylıktır**.

| Paket | Fiyat | Net / ay | Satır / ay | Gemini, en kötü (0,00020 $) | Kâr | Gemini, en kötü (0,00017 $) | Kâr | %40 önbellek | Kâr |
|---|---|---|---|---|---|---|---|---|---|
| Deneme (3 gün) | ücretsiz | 0 | 1.500 (toplam) | 0,30 $ | −0,30 $ | 0,26 $ | −0,26 $ | 0,15 $ | −0,15 $ |
| Standart (~10 saat) | 79,99 TL/ay | 56,66 TL = 1,14 $ | 4.500 | 0,90 $ | +0,24 $ (**%21**) | 0,77 $ | +0,38 $ (**%33**) | 0,46 $ | %60 |
| Pro (~22 saat) | 149,99 TL/ay | 106,24 TL = 2,14 $ | 10.000 | 2,00 $ | +0,14 $ (%6) | 1,70 $ | +0,44 $ (**%20**) | 1,02 $ | %52 |
| Premium (~50 saat) | 349,99 TL/ay | 247,91 TL = 4,99 $ | 24.000 | 4,80 $ | +0,19 $ (%4) | 4,08 $ | +0,91 $ (**%18**) | 2,45 $ | %51 |
| Standart yıllık | 799,99 TL/yıl | 47,22 TL = 0,95 $ | 4.500 | 0,90 $ | +0,05 $ (%5) | 0,77 $ | +0,19 $ (**%20**) | 0,46 $ | %52 |
| Pro yıllık | 1.499,99 TL/yıl | 88,54 TL = 1,78 $ | 10.000 | 2,00 $ | −0,22 $ (−%12) | 1,70 $ | +0,08 $ (%5) | 1,02 $ | %43 |
| Premium yıllık | 3.499,99 TL/yıl | 206,60 TL = 4,16 $ | 24.000 | 4,80 $ | −0,64 $ (−%15) | 4,08 $ | +0,08 $ (%2) | 2,45 $ | %41 |
| Ek paket (+2.000 satır) | 39,99 TL | 28,32 TL = 0,57 $ | 2.000 | 0,40 $ | +0,17 $ (**%30**) | 0,34 $ | +0,23 $ (**%40**) | 0,20 $ | %64 |

Yorum:
- **Aylık paketlerin hepsi en kötü durumda bile kârlı** (eski 0,00020 $ ile de artık zarar yok; v1.1'de Pro −%3, Premium −%26 idi).
  %15+ kâr hedefi, v1.2 satır maliyeti tahminiyle (0,00017 $) üç aylık pakette de tutar; eski temkinli maliyetle Pro %6, Premium %4'te kalır.
  Gerçek satır maliyetini sayaçlar + Google Cloud faturasıyla bir ay ölçün; 0,00019 $'ın üstündeyse `PLAN_LIMITS`'te Pro'yu 9.000,
  Premium'u 21.000'e çekmek %15'i eski maliyette de garanti eder.
- **Yıllık planlar (≈ 10 × aylık, "2 ay bedava")** en kötü durumda zayıftır: Standart yıllık %20, Pro/Premium yıllık ancak başabaş.
  Gerçekte bir abonenin 12 ay boyunca her ay sınırın tamamını kullanması çok nadirdir ve önbellekle (%40) hepsi %40+ kârlıdır.
  En kötü durumda da %15 isteniyorsa önerilen yıllık fiyatlar: **Standart 799,99 TL** (değişmez), **Pro 1.699,99 TL**, **Premium 4.049,99 TL**
  (uygulama kazancı Play fiyatlarından kendisi hesaplar: bu fiyatlarla "%5 / %3 indirim" yazar). Karar sizin; tablo aynı formülle yeniden hesaplanabilir.
- **Ek paket** her durumda kârlıdır ve yalnızca hak dolunca / %80'den sonra abonelik ekranında önerilir.
- Deneme, yeni abone başına en çok ~0,26–0,30 $ maliyettir (Play denemeyi yalnızca hiç abone olmamış hesaplara verir).
- Sabit gider: Cloudflare Workers Paid 5 $/ay (aşağıda), tablo dışıdır. Kur veya Gemini fiyatı değişirse aynı formülle yeniden hesaplayın.

### Cloudflare Workers + KV

| | Ücretsiz plan | Workers Paid (5 $/ay) |
|---|---|---|
| İstek | 100.000 / gün | 10 milyon / ay dahil, sonra 0,30 $ / milyon |
| KV okuma | 100.000 / gün | 10 milyon / ay dahil, sonra 0,50 $ / milyon |
| **KV yazma** | **1.000 / gün** | 1 milyon / ay dahil, sonra 5 $ / milyon |
| KV depolama | 1 GB | 1 GB dahil, sonra 0,50 $ / GB-ay |

(Kaynak: https://developers.cloudflare.com/workers/platform/pricing/ ,
https://developers.cloudflare.com/kv/platform/limits/)

Her **yeni** (önbellekte olmayan) çeviri ~1–2 KV yazması yapar (önbellek kaydı + kota sayacı); önbellek sayaçları
isolate başına dakikada en fazla 1 yazma, ek paket alımı 2 yazmadır.
Bu yüzden ücretsiz plan günde yalnızca **~500–1.000 yeni satıra** yeter — yani test için yeterli,
ama ilk gerçek abonelerle birlikte **Workers Paid (5 $/ay)** planına geçin:
Cloudflare → **Workers & Pages → Plans** → **Workers Paid**. Ücretli planda ayda ~500.000+ yeni satır
5 $'a dahildir; bunun üstü her ek milyon yazma için 5 $.
Ücretsiz planda yazma sınırı aşılırsa çeviriler yine çalışır ama o gün önbellek ve kota sayımı
kaydedilemez.

### Google Play Developer API

Abonelik ve ek paket doğrulaması ücretsizdir (abonelik sonuçları 10 dakika önbelleğe alınır; oturum başına en fazla 1 sorgu;
ek paket jetonu başına 1 sorgu). Ek paket için D adımındaki izinler yeterlidir (`purchases.products.get`).

---

## Bilinen sınırlamalar

- **Kota yaklaşıktır:** KV "sonunda tutarlı" bir depodur ve aynı anahtara saniyede en fazla 1 yazma
  kabul eder; sunucu sayacı en fazla ~1,5 sn'de bir yazar. Çok nadiren bir abonenin birkaç satırı
  sayılmayabilir ya da sınır birkaç satır geç devreye girebilir. Ödeme açısından önemsizdir.
- **Hız sınırı** (abone başına ~10 istek/sn) her Cloudflare sunucusunda ayrı tutulur; kaba bir korumadır.
- **Paket değişikliği / yeniden abonelik:** Google yeni bir satın alma jetonu verir ve eskisini
  `linkedPurchaseToken` ile bildirir. Sunucu bu zinciri izler ve aynı ay içinde kullanılan satırları yeni
  pakete taşır (yeni paketin sınırıyla karşılaştırılır); kullanılmayan satırlar devredilmez. Eski jeton
  sunucuya hiç gelmemişse zincir oradan başlar. İptal edip **süresi dolduktan sonra** yeniden abone olan
  kişide bağlantı yoktur; kota sıfırdan başlar (deneme ise Play tarafından tekrar verilmez).
- **Deneme tespiti:** Google'ın `offerPhase.freeTrial` alanına bakılır; bu alan yoksa teklif kimliği
  `deneme-3gun` ve abonelik başlangıcı son 3 gün içindeyse deneme sayılır. Deneme bitince (en geç 1 saat
  içinde, oturum yenilenince) paketin aylık sınırı başlar; deneme satırları ilk aya sayılmaz.
- **Ek paket:** aynı satın alma jetonu yalnızca bir kez kredilenir (`tu:<jeton özeti>` kaydı, 400 gün). Kredi, satın alındığı
  kota ayının sonuna kadar geçerlidir ve paket değişikliğinde aynı kovada kalır. KV sonunda tutarlı olduğu için iki farklı
  Cloudflare merkezinden aynı anda gelen iki istek çok nadiren aynı paketi iki kez sayabilir (kullanıcı lehine; önemsiz).
  Abonelik bittikten sonra tüketilmemiş ek paket kredilendirilemez; Play Console → Siparişler'den iade edin.
- **Kısa satırlar:** uygulama ≤ 3 kelime / ≤ 18 karakter satırları cihaz içi çevirir; bu satırlar sunucuya hiç gelmez,
  dolayısıyla sayaçlarda ve kotada görünmez.
- Sunucu altyazı metnini **asla** kayda (log) yazmaz; yalnızca yol, durum kodu, süre ve hata kodu.
