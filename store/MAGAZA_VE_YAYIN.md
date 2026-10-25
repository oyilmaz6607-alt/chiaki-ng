# Play Store mağaza metni ve yayın kontrol listesi

## Mağaza kaydı (Türkçe)

**Uygulama adı (en fazla 30 karakter):** AltyazıTR Oyun: Canlı Çeviri

**Kısa açıklama (en fazla 80 karakter):**
Oyunlardaki İngilizce altyazıları anında Türkçeye çevirip TV ekranında gösterir.

**Tam açıklama:**
İngilizce oyunları artık Türkçe altyazıyla oynayın!

AltyazıTR Oyun, PS5'inizde oynadığınız oyunların İngilizce altyazılarını okur, anında Türkçeye çevirir ve çeviriyi doğrudan TV ekranınızda, orijinal altyazının üstünde gösterir.

★ Oyununuzu her zamanki gibi oynayın
PS5'iniz TV'ye HDMI ile bağlı kalır. Görüntü kalitesi ve tepki süresi değişmez, kumandanız konsolda kalır.

★ Cihaz içi çeviri
Metin tanıma ve çeviri TV'nizin içinde yapılır. Oyun görüntünüz hiçbir sunucuya gönderilmez.

★ AltyazıTR Abonelik (isteğe bağlı, oyun saatinize göre paket)
Daha doğru ve doğal yapay zekâ çevirisi; API anahtarı gerekmez, Türkçe yazıldıkça ekranda belirir. Ne kadar oynadığınıza göre seçin: Standart (ayda ~10 saat), Pro (ayda ~22 saat) veya Premium (ayda ~50 saat); aylık veya indirimli yıllık ödeme. Yeni abonelere 3 gün ücretsiz deneme. Çok kısa satırlar (1–3 kelime, menü yazıları) cihaz içinde çevrilir ve hakkınızdan düşmez. Paketin aylık hakkı dolarsa ay sonuna kadar ücretsiz cihaz içi çeviri devam eder; isterseniz tek seferlik ek satır paketi alabilirsiniz. Reklam yok. Abonelik seçtiğiniz döneme göre otomatik yenilenir, Google Play'den istediğiniz zaman iptal edilebilir veya paket değiştirilebilir.

★ Size göre altyazı
Yazı tipi, boyutu, rengi, arka planı ve konumu ayarlanabilir; değişiklikleri canlı önizlemede görün.

★ Kolay kurulum
1. PS5'te Remote Play'i açın.
2. Konsolu uygulamayla bir kez eşleştirin.
3. "Altyazıyı Başlat"a basın, TV'yi PS5 girişine alın ve oynayın.

Gereksinimler:
• Google TV / Android TV (Android 7.0 ve üzeri)
• PS5 veya PS4 ve aynı ev ağı (kablolu bağlantı önerilir)
• Konsolu eşleştirmek için ikinci bir (ücretsiz) PlayStation Network hesabı önerilir; böylece kendi hesabınızla oynamaya devam edersiniz.
• Oyunda İngilizce altyazının açık olması

Notlar:
• Çeviri kalitesi oyuna ve yazı tipine göre değişebilir.
• Bu uygulama Sony Interactive Entertainment ile bağlantılı değildir ve onun tarafından onaylanmamıştır. PlayStation ve PS5, Sony Interactive Entertainment Inc.'in ticari markalarıdır.
• Açık kaynaklıdır (chiaki-ng tabanlı, AGPL-3.0): https://github.com/oyilmaz6607-alt/chiaki-ng

**Kategori:** Araçlar (Tools) — "Oyun" kategorisi seçilmemeli; uygulama kendisi bir oyun değil.
**İletişim e-postası:** o.yilmaz6607@gmail.com
**Gizlilik politikası URL'si:** https://github.com/oyilmaz6607-alt/chiaki-ng/blob/main/store/GIZLILIK.md

## Veri güvenliği formu (Data safety) — önerilen yanıtlar
Kaynak: Google ML Kit veri açıklaması (https://developers.google.com/ml-kit/android-data-disclosure). Son karar sizindir.

- Uygulama gerekli kullanıcı verilerini topluyor veya paylaşıyor mu? **Evet** (ML Kit'in tanılama verileri nedeniyle).
- Toplanan veriler:
  - **Uygulama bilgileri ve performans → Tanılama verileri** (Diagnostics) — amaç: Analiz/uygulama işlevselliği; isteğe bağlı değil; aktarım sırasında şifreli: Evet.
  - **Cihaz veya diğer kimlikler** — ML Kit kurulum başına kimlik kullanır; amaç: Analiz; şifreli: Evet.
- **AltyazıTR abonelerinin yapay zekâ çevirisi için:** **Uygulama etkinliği → Diğer kullanıcı tarafından oluşturulan içerik** (ekrandan okunan altyazı metni + gerektiğinde en fazla 2 önceki çift; 1–3 kelimelik satırlar hiç gönderilmez) — bizim sunucumuza ve oradan Google Gemini (ücretli API) servisine gönderilir; amaç: Uygulama işlevselliği; isteğe bağlı: Evet (yalnızca abonelik çevirisini kullanan); aktarımda şifreli: Evet; sunucu metni saklamaz (yalnızca satır sayısı). Google burada hizmet sağlayıcı olduğu için "paylaşım" sayılmaz.
- **Satın alma geçmişi (Financial info → Purchase history):** Aboneliğin etkin olup olmadığını ve hangi paketin (Standart/Pro/Premium/deneme) geçerli olduğunu doğrulamak için satın alma jetonu sunucuya gönderilir ve Google Play'e doğrulatılır; amaç: Uygulama işlevselliği / hesap yönetimi; isteğe bağlı: Evet; şifreli: Evet. Ödeme bilgisi (kart vb.) toplanmaz — onu Google Play işler.
- Kullanıcı Gemini'yi seçerse: **Uygulama etkinliği → Diğer kullanıcı tarafından oluşturulan içerik** (en yakın kategori; Google'ın güncel tanımlarını kontrol edin) — ekrandan okunan altyazı metni kullanıcının kendi anahtarıyla Google Gemini API'ye gönderilir; amaç: Uygulama işlevselliği; isteğe bağlı: Evet; şifreli: Evet.
- Paylaşılan veri: **Yok** (ML Kit verileri üçüncü taraflarla paylaşmaz; Google'a hizmet sağlayıcı olarak gönderim "paylaşım" sayılmaz).
- Veri silme isteği: Abonelik sunucusu yalnızca abonelik başına aylık satır sayısını ve (paket değişikliğinde) eski/yeni satın alma jetonlarının özet (hash) bağlantısını tutar (metin tutmaz); talep e-postayla silinir. Kaldırınca cihazdaki her şey silinir.

## AltyazıTR aboneliği (Standart / Pro / Premium, aylık/yıllık + 3 gün deneme + ek paket) — Play Console kurulumu
Uygulama ve sunucu aynı kimlikleri kullanır (ayrıntılar `server/PRO_API.md`, v1.2):

| Ne | Kimlik | Not |
|---|---|---|
| Paket adı | `com.altyazitr.oyun` | |
| Abonelik (ürün) | **`altyazitr_abonelik`** | tek ürün; eski `altyazitr_pro` artık kullanılmaz |
| Temel plan | **`standart`** / **`standart-yillik`** | aylık / yıllık, otomatik yenilenen · ~10 saat · 4.500 satır/ay |
| Temel plan | **`pro`** / **`pro-yillik`** | aylık / yıllık · ~22 saat · 10.000 satır/ay · "En çok tercih edilen" |
| Temel plan | **`premium`** / **`premium-yillik`** | aylık / yıllık · ~50 saat · 24.000 satır/ay |
| Teklif (her temel planda) | **`deneme-3gun`** | 3 gün ücretsiz deneme, yalnızca yeni müşteriler · deneme boyunca 1.500 satır |
| Uygulama içi ürün (tek seferlik, tüketilebilir) | **`ek_satir_2000`** | +2.000 yapay zekâ satırı, satın alındığı ayın sonuna kadar |

Yıllık planlarda da satır hakkı **her ay** yenilenir (sunucu `<paket>-yillik` → `<paket>` sınırını uygular).

Saatler, diyaloğu yoğun oyunda saatte ~450 altyazı satırı varsayımına dayanır. Satır sınırlarını sunucu uygular
(`server/wrangler.toml` → `PLAN_LIMITS`); uygulamadaki kartlarda yazan sayılar `BillingLogic.kt → Plans` içindedir —
birini değiştirirseniz diğerini de güncelleyin. **Fiyatlar kodda değildir**: Play Console'da girilir, uygulama
Google Play'in biçimlendirdiği fiyatı gösterir.

Önerilen fiyatlar (KDV dahil). Kâr = net gelir (÷1,20 KDV, ×0,85 Play) − Gemini; "en kötü" = paketin tamamı her ay,
önbelleksiz. Satır maliyeti: 0,00017 $ (v1.2 tahmini; parantezde eski temkinli 0,00020 $). Ayrıntı ve varsayımlar:
`server/KURULUM.md` → "Maliyet".

| Paket | Önerilen fiyat | Satır / ay | Kâr, en kötü | Kâr, %40 önbellek |
|---|---|---|---|---|
| Standart | 79,99 TL / ay | 4.500 | %33 (%21) | %60 |
| Pro | 149,99 TL / ay | 10.000 | %20 (%6) | %52 |
| Premium | **349,99 TL / ay** (önce 299,99) | 24.000 | %18 (%4) | %51 |
| Standart yıllık | 799,99 TL / yıl ("2 ay bedava") | 4.500 | %20 (%5) | %52 |
| Pro yıllık | 1.499,99 TL / yıl ("2 ay bedava") | 10.000 | %5 (−%12) | %43 |
| Premium yıllık | 3.499,99 TL / yıl ("2 ay bedava") | 24.000 | %2 (−%15) | %41 |
| Ek paket `ek_satir_2000` | 39,99 TL (tek seferlik) | +2.000 | %40 (%30) | %64 |

- Yıllık fiyatlar ≈ 10 × aylık (~%17 indirim). Pro/Premium yıllık en kötü durumda yalnızca başabaştır (her ay tüm hakkı kullanan
  yıllık abone çok nadirdir). En kötü durumda da %15 kâr istenirse: Pro yıllık **1.699,99 TL**, Premium yıllık **4.049,99 TL**.
- Uygulama yıllık kartlarda "x ay bedava" yazısını Play'in fiyatlarından kendisi hesaplar (fiyat kodda yoktur).
- **Mevcut Premium aboneleri:** Premium fiyatını 299,99'dan 349,99'a çıkarırsanız Play mevcut abonelere fiyat artışı bildirimi
  gösterir ("opt-in" / Play kurallarına göre). Bunun yerine eski aboneleri eski fiyatta bırakmak için Play Console → temel plan
  → fiyat → "Mevcut aboneleri eski fiyatta tut (legacy price)" seçeneğini kullanın — sürpriz fiyat artışı şikâyet sebebidir.

1. **Ödeme profili:** Play Console → Ayarlar → Ödeme profili. Satış yapabilmek için bir ödeme (satıcı) profili bağlanmış olmalı; Türkiye için vergi bilgilerini doldurun.
2. **Faturalandırma kitaplığı olan bir sürüm yükleyin:** Abonelik oluşturma ekranı ancak Play Billing içeren bir sürüm (en az dahili test kanalına) yüklendikten sonra açılır. Bu sürüm Billing 8.0.0 içerir.
3. **Aboneliği oluşturun:** **Para kazanma (Monetize with Play) → Ürünler → Abonelikler → Abonelik oluştur**.
   - Ürün kimliği: `altyazitr_abonelik` (sonradan değiştirilemez; küçük harf, rakam, `_` ve `.`; en fazla 40 karakter).
   - Ad: "AltyazıTR Abonelik"; avantajlar (en fazla 4): "Doğal yapay zekâ çevirisi", "API anahtarı gerekmez", "Oyun saatine göre paket", "Hak dolunca cihaz içi çeviri sürer".
   - **Kaydet**.
4. **Üç temel plan** (her biri için aboneliğin yanındaki sağ oka tıklayın → **Temel plan ekle / Add base plan**):
   - Temel plan kimliği: sırasıyla `standart`, `pro`, `premium` (küçük harf, rakam, tire; aynı abonelikte benzersiz, sonradan değiştirilemez).
   - Tür: **Otomatik yenilenen (Auto-renewing)** · Faturalandırma dönemi: **Aylık (1 ay)**.
   - Ek süre (grace period) ve hesap bekletme (account hold) **açık** kalmalı (varsayılan; kısaltmayın) — istem dışı ayrılmayı azaltır.
     Adımlar: `server/KURULUM.md` → "Google Play: ek süre ve hesap askıya alma".
   - **Yeniden abone olma / paket değişikliği:** "Yükseltme/düşürme" için ek ayar gerekmez; uygulama yükseltmeyi hemen
     (kalan süre orantılı: `WITH_TIME_PRORATION`), düşürmeyi dönem sonunda (`DEFERRED`) yapar.
   - **Fiyat:** "Fiyatları ayarla" → Türkiye için yukarıdaki TL fiyatı girin; diğer ülkeler için "Fiyatları dönüştür" veya yalnızca Türkiye'yi seçin.
   - **Uygula → Kaydet → Etkinleştir (Activate)**. Üç plan da **Etkin** olmalı; etkin olmayan plan uygulamada "Fiyat alınamadı / Tekrar dene" görünür.
5. **3 günlük ücretsiz deneme teklifi** — **her** temel plan için ayrı ayrı (teklif kimliği bir temel plan içinde benzersiz olmalı; üç planda aynı kimlik kullanılabilir):
   aboneliğin ayrıntıları → "Temel planlar ve teklifler" → **Teklif ekle (Add offer)** → temel planı seçin (`standart`, sonra `pro`, sonra `premium`) → **Teklif ekle**:
   - Teklif kimliği: **`deneme-3gun`** (sunucu yedek deneme tespitinde bu kimliğe bakar).
   - Uygunluk (Eligibility): **Yeni müşteri edinme (New customer acquisition) → Hiç abonelik almamış olanlar (Never had any subscription)**. Böylece bir kez deneme kullanan kişi başka pakette tekrar deneme alamaz.
   - Aşama ekle: **Ücretsiz deneme (Free trial)**, süre **3 gün** (Google'ın izin verdiği en kısa süre).
   - Etiket gerekmez. **Kaydet → Etkinleştir**.
   - Uygulama denemeyi kendisi bulur: Google Play yalnızca kullanıcının uygun olduğu teklifleri döndürür; uygunsa kartta "3 gün ücretsiz dene" yazar ve satın alma deneme teklifiyle başlar, değilse temel plan fiyatıyla.
   - Deneme boyunca kullanıcı paketin aylık hakkını değil, toplam **1.500 satır** yapay zekâ çevirisi alır; deneme bitip ilk ödeme alınınca paketin aylık hakkı başlar.
6. **Yıllık temel planlar:** aynı abonelikte 4. adımı üç kez daha tekrarlayın: kimlikler **`standart-yillik`**, **`pro-yillik`**,
   **`premium-yillik`** (tam olarak bu yazımla; sunucu ve uygulama `-yillik` ekine bakar) · Otomatik yenilenen · Faturalandırma
   dönemi **Yıllık (1 yıl)** · fiyatlar yukarıdaki tablodan · ek süre/hesap bekletme açık · **Etkinleştir**. Her yıllık plana da
   5. adımdaki `deneme-3gun` teklifini ekleyin. Sunucuda ayar gerekmez (yıllık plan aynı paketin aylık sınırını kullanır).
   Uygulamada abonelik ekranının üstündeki **Aylık / Yıllık** seçimi kartlardaki fiyatı değiştirir. Paket değişikliği kuralı:
   aylıktan aynı paketin yıllığına ve üst pakete geçiş hemen (kalan süre orantılı), yıllıktan aylığa ve alt pakete geçiş dönem sonunda.
7. **Ek satır paketi (tek seferlik):** **Para kazanma → Ürünler → Uygulama içi ürünler (One-time products) → Ürün oluştur**:
   - Ürün kimliği: **`ek_satir_2000`** · Ad: "+2.000 satır yapay zekâ çevirisi" · Açıklama: "Bu ayın sonuna kadar geçerli
     2.000 ek yapay zekâ çeviri satırı. Tek seferlik; otomatik yenilenmez."
   - Fiyat: **39,99 TL** (Türkiye) · Satın alma seçeneği: varsayılan tek seçenek ("Buy"), çoklu adet (multi-quantity) **kapalı**.
   - **Kaydet → Etkinleştir**. Uygulama ürünü `INAPP` türünde sorgular; sunucu kredilendirdikten sonra tüketir (consume),
     böylece aynı ay içinde tekrar alınabilir. Kullanıcıya yalnızca hak dolunca (tek seferlik bildirim metninde) ve abonelik
     ekranında kullanım %80'i geçince sunulur; oyun sırasında açılır pencere yoktur.
   - Sunucu için ek izin gerekmez: 9. adımdaki hizmet hesabı izinleri `purchases.products.get` için de yeterlidir.
8. **Geri kazanma (win-back) teklifi** — aboneliği biten eski abonelere (ör. ilk ay %50 indirim). Kod gerekmez:
   - Aboneliğin ayrıntıları → "Temel planlar ve teklifler" → **Teklif ekle** → temel plan: `pro` (istersen `standart`/`premium` için de ayrı teklif).
   - Teklif kimliği: **`geri-donus-50`** · Uygunluk (Eligibility): Play Console'da varsa **Win-back / "Geri kazanma"** (daha önce bu
     aboneliğe sahip olup şu an abone olmayanlar), yoksa **Geliştirici tarafından belirlenir (Developer determined)**.
   - Aşama: **Tek seferlik ödeme / indirimli giriş fiyatı (Single payment veya Discounted recurring payment)**, süre **1 ay**,
     fiyat aylık fiyatın %50'si (ör. Pro için 74,99 TL). Sonra normal fiyattan devam eder; kullanıcı Play'de bunu görür.
   - Etiket (tag): `geri-donus`. **Kaydet → Etkinleştir**.
   - Ulaştırma: Play, "win-back" uygunluğundaki teklifi eski abonelere **Play Store'da** (abonelikler bölümü, uygulama sayfası)
     kendisi gösterir; e-posta/kampanya için Play Console'daki teklif bağlantısını kullanabilirsiniz. Uygulama içi seçim değişmez:
     uygulama yalnızca ücretsiz deneme teklifini veya temel planı seçer, indirimli teklifleri kendiliğinden uygulamaz
     (Developer-determined seçilirse teklif herkese döner ama uygulama onu seçmez → yalnızca Play Store/bağlantı üzerinden kullanılır).
   - Kötüye kullanımı azaltmak için teklifi yalnızca en az 30 gündür abone olmayanlara verin (Play Console'daki "son abonelikten bu yana" ayarı varsa).
   - Sunucu bu teklifle alınan aboneliği normal paket olarak görür (deneme değildir; `offerPhase` ücretli aşamadır).
9. **Sunucu erişimi:** Sunucunun aboneliği doğrulayabilmesi için Google Cloud'da bir hizmet hesabı oluşturup Play Console → Kullanıcılar ve izinler'den bu hesaba uygulama için "Finansal verileri görüntüleme" ve "Siparişleri ve abonelikleri yönetme" izinlerini verin (anahtarı yalnızca sunucuya koyun; ayrıntılar `server/KURULUM.md`).
10. **Sunucu adresi:** GitHub → Settings → Secrets → Actions → `PRO_API_BASE_URL` (ör. `https://altyazitr-api.<hesap>.workers.dev`). Boş bırakılırsa uygulama "Abonelik hizmeti henüz hazır değil" der ve satın almayı başlatmaz.
11. **Eski ürün:** Daha önce `altyazitr_pro` oluşturulduysa ve hiç satılmadıysa arşivleyin (Abonelikler → ürün → Arşivle). Satıldıysa: o abonelerin uygulaması artık bu ürünü tanımaz; arşivlemeden önce onları yeni ürüne geçirin veya iade edin.

### Test etme
- Play Console → Ayarlar → **Lisans testi** → test e-postalarını ekleyin (TV'deki Google hesabı) ve "Lisans yanıtı: RESPOND_NORMALLY" kalsın. Lisans test kullanıcıları gerçek ödeme yapmaz; test kartıyla ("Test kartı, her zaman onaylar" / "her zaman reddeder" / "yavaş onay" = bekleyen ödeme) dener.
- Test aboneliklerinde süreler kısalır: aylık yenileme ~5 dakika, **3 günlük deneme ~3 dakika**; birkaç yenilemeden sonra abonelik kendiliğinden biter. Deneme → ücretli geçişini, bitişi ve iptali böyle deneyin.
- Denemeyi tekrar görmek için: Play'de test hesabının aboneliğini iptal edip süresinin bitmesini bekleyin; "Never had any subscription" uygunluğu nedeniyle aynı hesap denemeyi **tekrar almaz** — yeni bir lisans test hesabı kullanın.
- Uygulama **Google Play'den yüklenmiş** olmalı (dahili test bağlantısıyla). Elle yüklenen (yan yüklenen) APK'da Google Play faturalandırma çalışmaz; ekranda bunu anlatan bir mesaj çıkar.
- Denenecekler (kumandayla):
  1. Abonelik ekranı açılınca odak **Pro** kartında; üç kartta fiyat, "Ayda ~… saat oyun", satır sayısı ve (yeni hesapta) "3 gün ücretsiz dene" görünüyor.
  2. "Ne kadar oynuyorsun?" seçenekleri ilgili kartı vurgulayıp odağı ona taşıyor.
  3. Standart ile satın alma (deneme) → kart "Mevcut paketin", üst çubukta "Ücretsiz deneme etkin", kota "Deneme: 0 / 1.500 satır, deneme bitişi: …".
  4. Pro'ya **Yükselt** → Play ekranında orantılı ücret; sonra kart "Mevcut paketin" ve kota sınırı 10.000 (bu ay kullanılanlar taşınır).
  4b. **Yıllık** seçimi → kartlarda yıllık fiyat ve "2 ay bedava"; Pro aylık abone iken "Pro yıllık" → **Yükselt** (hemen); Pro yıllıkken "Pro aylık" → **Düşür** (dönem sonunda).
  5. Standart'a **Düşür** → "mevcut paketiniz dönem sonuna kadar geçerli" bilgisi; test yenilemesinden sonra paket Standart.
  6. Kota dolması: sunucuda geçici olarak `PLAN_LIMITS` içinde ilgili paketi `5` yapın → 5 satırdan sonra altyazı cihaz içi çeviriyle sürer, **tek seferlik** "…paketine geçebilir ya da tek seferlik +2.000 satır alabilirsin" bildirimi çıkar (her satırda tekrar etmez). Sonra değeri geri alın.
  6b. %80 uyarısı: sınırı `10` yapıp 8 uzun satır çevirin → oyun sırasında hiçbir şey çıkmaz; altyazıyı durdurup ana ekrana dönünce bir kez "…%80 kullanıldı, 2 satır kaldı…" bilgisi; telefon sayfasında aynı uyarı. Abonelik ekranında **+2.000 satır ekle · ₺39,99** düğmesi.
  6c. Ek paket: düğme → Play (test kartı) → "+2.000 satır eklendi" → kota sınırı +2.000, yapay zekâ çevirisi yeniden açılır. "Yavaş onay" kartıyla bekleyen ödeme → onaylanınca kendiliğinden eklenir. Uygulamayı satın alma sırasında kapatıp açın → paket yine eklenir (tüketilmemiş satın almalar yeniden gönderilir).
  6d. Kısa satırlar: "Yes.", "Options", "PRESS X TO CONTINUE" gibi satırlar kotayı artırmaz (hata ayıklama satırında "cihaz içi").
  7. İptal (kullanıcı vazgeçti), bekleyen ödeme, "Aboneliği yönet", abonelik bitince motorun cihaz içi/Gemini'ye dönmesi, ana ekran rozetinde paket adı / "Abone ol", telefondaki ayar sayfasında paket ve kota çubuğu.

### Mağaza ve politika
- Abonelik koşulları uygulamada paket kartlarının altında yazılıdır (paket başına fiyat ve dönem — aylık/yıllık, otomatik yenileme, yıllıkta da satır hakkının aylık olduğu, 3 gün deneme ve kimlerin alabileceği, paket değişikliğinin ne zaman geçerli olduğu, hak dolunca ne olduğu, ek paketin tek seferlik olduğu ve ne zamana kadar geçerli olduğu, kısa satırların cihaz içi çevrildiği, iptal yolu, gizlilik politikası bağlantısı).
- Şikâyet yaratmamak için bilinçli olarak **yapılmayanlar:** reklam yok; oyun sırasında kesinti/açılır pencere yok (uyarılar yalnızca ana ekranda ve telefon sayfasında, hak dolunca tek bir kısa bildirim); çeviri hiçbir zaman durmaz (hak dolunca cihaz içi devam eder); otomatik paket yükseltme yok; ek paket otomatik yenilenmez; iptal yolu ("Aboneliği yönet") her zaman ekranda. Mağaza açıklamasında da aboneliğin isteğe bağlı olduğunu ve paketleri belirtin.
- **Veri güvenliği formunu** yukarıdaki abonelik maddelerine göre güncelleyin ve **gizlilik politikasının** güncel sürümünü (GIZLILIK.md) yayımlayın.
- Ücretsiz özellikler (cihaz içi çeviri, kendi Gemini anahtarı) abonelik olmadan çalışmaya devam eder.

## İçerik derecelendirmesi
Anketi "Yardımcı program / araç" olarak doldurun; şiddet, kullanıcı etkileşimi, konum paylaşımı yok → muhtemelen "3+ / Herkes".

## Ön plan hizmeti beyanı (Play Console → Uygulama içeriği → Ön plan hizmetleri)
Tür: **specialUse**. Açıklama: "Kullanıcı oyununu HDMI üzerinden oynarken, uygulama kullanıcının kendi PlayStation konsoluna Remote Play ile bağlanıp altyazıları cihaz üzerinde okur ve canlı Türkçe çeviriyi ekranın üzerinde gösterir. Kullanıcı 'Altyazıyı Başlat' ile başlatır, 'Durdur' ile durdurur; hizmet çalıştığı sürece çeviri görünür." İstenirse kısa bir video ekleyin (çekilen test videosu uygundur).

## Yayın kontrol listesi
- [ ] Play Console geliştirici hesabı (25 $, kimlik doğrulama)
- [ ] Yükleme anahtarı oluştur ve GitHub Secrets'a ekle (KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD) → imzalı .aab üretilir
- [ ] Play App Signing'i aç (varsayılan)
- [ ] Mağaza grafikleri: 512×512 simge, 1024×500 öne çıkan grafik, en az 1 adet 1280×720 TV ekran görüntüsü, 320×180 TV banner (uygulamada)
- [ ] Gizlilik politikası URL'si, veri güvenliği, içerik derecelendirmesi, hedef kitle (18+ veya 13+), reklam yok
- [ ] Ön plan hizmeti beyanı (specialUse) ve "Diğer uygulamaların üzerinde göster" kullanım gerekçesi
- [ ] Form faktörü olarak **Android TV**'yi ekle, TV kalite incelemesine gönder (D-pad ile tam gezinme, banner, sadece yatay ekran)
- [ ] AltyazıTR Abonelik: ödeme profili, `altyazitr_abonelik` aboneliği + `standart` / `pro` / `premium` ve `standart-yillik` / `pro-yillik` / `premium-yillik` temel planları (fiyatlarıyla, etkin, ek süre + hesap bekletme açık) + her birinde `deneme-3gun` teklifi (3 gün, yeni müşteriler), `ek_satir_2000` uygulama içi ürünü (39,99 TL, etkin), isteğe bağlı `geri-donus-50` geri kazanma teklifi, lisans test kullanıcıları, `PRO_API_BASE_URL` gizli değişkeni, sunucunun hizmet hesabı ve `PLAN_LIMITS`
- [ ] Önce **Dahili test** kanalı → sonra kapalı test → üretim
- [ ] Yeni kişisel geliştirici hesaplarında üretime çıkmadan önce kapalı testte belirli sayıda test kullanıcısı ve süre şartı olabilir; Play Console'daki güncel şartı kontrol edin.

## Bilinen riskler
- Sony ticari markası: ad/simgede "PlayStation", "PS5" logosu veya DualSense görseli kullanılmamalı; açıklamada uyumluluk belirtmek için isim geçmesi normaldir.
- Google TV incelemesi arayüzü kumandayla test eder; tüm ekranlar D-pad ile kullanılabilir olmalı.
