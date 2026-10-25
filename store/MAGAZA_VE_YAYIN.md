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
Daha doğru ve doğal yapay zekâ çevirisi; API anahtarı gerekmez, Türkçe yazıldıkça ekranda belirir. Ne kadar oynadığınıza göre seçin: Standart (ayda ~10 saat), Pro (ayda ~25 saat) veya Premium (ayda ~60 saat). Yeni abonelere 3 gün ücretsiz deneme. Paketin aylık hakkı dolarsa ay sonuna kadar ücretsiz cihaz içi çeviri devam eder. Abonelik her ay otomatik yenilenir, Google Play'den istediğiniz zaman iptal edilebilir veya paket değiştirilebilir.

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
- **AltyazıTR abonelerinin yapay zekâ çevirisi için:** **Uygulama etkinliği → Diğer kullanıcı tarafından oluşturulan içerik** (ekrandan okunan altyazı metni + en fazla 4 önceki çift) — bizim sunucumuza ve oradan Google Gemini (ücretli API) servisine gönderilir; amaç: Uygulama işlevselliği; isteğe bağlı: Evet (yalnızca abonelik çevirisini kullanan); aktarımda şifreli: Evet; sunucu metni saklamaz (yalnızca satır sayısı). Google burada hizmet sağlayıcı olduğu için "paylaşım" sayılmaz.
- **Satın alma geçmişi (Financial info → Purchase history):** Aboneliğin etkin olup olmadığını ve hangi paketin (Standart/Pro/Premium/deneme) geçerli olduğunu doğrulamak için satın alma jetonu sunucuya gönderilir ve Google Play'e doğrulatılır; amaç: Uygulama işlevselliği / hesap yönetimi; isteğe bağlı: Evet; şifreli: Evet. Ödeme bilgisi (kart vb.) toplanmaz — onu Google Play işler.
- Kullanıcı Gemini'yi seçerse: **Uygulama etkinliği → Diğer kullanıcı tarafından oluşturulan içerik** (en yakın kategori; Google'ın güncel tanımlarını kontrol edin) — ekrandan okunan altyazı metni kullanıcının kendi anahtarıyla Google Gemini API'ye gönderilir; amaç: Uygulama işlevselliği; isteğe bağlı: Evet; şifreli: Evet.
- Paylaşılan veri: **Yok** (ML Kit verileri üçüncü taraflarla paylaşmaz; Google'a hizmet sağlayıcı olarak gönderim "paylaşım" sayılmaz).
- Veri silme isteği: Abonelik sunucusu yalnızca abonelik başına aylık satır sayısını ve (paket değişikliğinde) eski/yeni satın alma jetonlarının özet (hash) bağlantısını tutar (metin tutmaz); talep e-postayla silinir. Kaldırınca cihazdaki her şey silinir.

## AltyazıTR aboneliği (Standart / Pro / Premium + 3 gün deneme) — Play Console kurulumu
Uygulama ve sunucu aynı kimlikleri kullanır (ayrıntılar `server/PRO_API.md`, v1.1):

| Ne | Kimlik | Not |
|---|---|---|
| Paket adı | `com.altyazitr.oyun` | |
| Abonelik (ürün) | **`altyazitr_abonelik`** | tek ürün; eski `altyazitr_pro` artık kullanılmaz |
| Temel plan | **`standart`** | aylık, otomatik yenilenen · ~10 saat · 4.500 satır |
| Temel plan | **`pro`** | aylık, otomatik yenilenen · ~25 saat · 11.000 satır · "En çok tercih edilen" |
| Temel plan | **`premium`** | aylık, otomatik yenilenen · ~60 saat · 27.000 satır |
| Teklif (her temel planda) | **`deneme-3gun`** | 3 gün ücretsiz deneme, yalnızca yeni müşteriler · deneme boyunca 1.500 satır |

Saatler, diyaloğu yoğun oyunda saatte ~450 altyazı satırı varsayımına dayanır. Satır sınırlarını sunucu uygular
(`server/wrangler.toml` → `PLAN_LIMITS`); uygulamadaki kartlarda yazan sayılar `BillingLogic.kt → Plans` içindedir —
birini değiştirirseniz diğerini de güncelleyin. **Fiyatlar kodda değildir**: Play Console'da girilir, uygulama
Google Play'in biçimlendirdiği fiyatı gösterir.

Önerilen fiyatlar (KDV dahil; maliyet/kâr hesabı `server/KURULUM.md` → "Paket başına maliyet ve kâr"):

| Paket | Önerilen fiyat |
|---|---|
| Standart | 79,99 TL / ay |
| Pro | 149,99 TL / ay |
| Premium | 299,99 TL / ay |

1. **Ödeme profili:** Play Console → Ayarlar → Ödeme profili. Satış yapabilmek için bir ödeme (satıcı) profili bağlanmış olmalı; Türkiye için vergi bilgilerini doldurun.
2. **Faturalandırma kitaplığı olan bir sürüm yükleyin:** Abonelik oluşturma ekranı ancak Play Billing içeren bir sürüm (en az dahili test kanalına) yüklendikten sonra açılır. Bu sürüm Billing 8.0.0 içerir.
3. **Aboneliği oluşturun:** **Para kazanma (Monetize with Play) → Ürünler → Abonelikler → Abonelik oluştur**.
   - Ürün kimliği: `altyazitr_abonelik` (sonradan değiştirilemez; küçük harf, rakam, `_` ve `.`; en fazla 40 karakter).
   - Ad: "AltyazıTR Abonelik"; avantajlar (en fazla 4): "Doğal yapay zekâ çevirisi", "API anahtarı gerekmez", "Oyun saatine göre paket", "Hak dolunca cihaz içi çeviri sürer".
   - **Kaydet**.
4. **Üç temel plan** (her biri için aboneliğin yanındaki sağ oka tıklayın → **Temel plan ekle / Add base plan**):
   - Temel plan kimliği: sırasıyla `standart`, `pro`, `premium` (küçük harf, rakam, tire; aynı abonelikte benzersiz, sonradan değiştirilemez).
   - Tür: **Otomatik yenilenen (Auto-renewing)** · Faturalandırma dönemi: **Aylık (1 ay)**.
   - Ek süre (grace period) ve hesap askıya alma varsayılan kalabilir (sunucu ek süreyi etkin sayar).
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
6. **(Kapsam dışı, ileride) Yıllık planlar:** Aynı aboneliğe `standart-yillik` gibi ayrı kimlikli yıllık temel planlar eklenebilir; ancak önce sunucudaki `PLAN_LIMITS`'e (aylık sınırla) ve uygulamadaki `Plans` listesine eklenmeleri gerekir. Eklenmeden etkinleştirilirse o planı alanlar "Satın alma doğrulanamadı" görür.
7. **Sunucu erişimi:** Sunucunun aboneliği doğrulayabilmesi için Google Cloud'da bir hizmet hesabı oluşturup Play Console → Kullanıcılar ve izinler'den bu hesaba uygulama için "Finansal verileri görüntüleme" ve "Siparişleri ve abonelikleri yönetme" izinlerini verin (anahtarı yalnızca sunucuya koyun; ayrıntılar `server/KURULUM.md`).
8. **Sunucu adresi:** GitHub → Settings → Secrets → Actions → `PRO_API_BASE_URL` (ör. `https://altyazitr-api.<hesap>.workers.dev`). Boş bırakılırsa uygulama "Abonelik hizmeti henüz hazır değil" der ve satın almayı başlatmaz.
9. **Eski ürün:** Daha önce `altyazitr_pro` oluşturulduysa ve hiç satılmadıysa arşivleyin (Abonelikler → ürün → Arşivle). Satıldıysa: o abonelerin uygulaması artık bu ürünü tanımaz; arşivlemeden önce onları yeni ürüne geçirin veya iade edin.

### Test etme
- Play Console → Ayarlar → **Lisans testi** → test e-postalarını ekleyin (TV'deki Google hesabı) ve "Lisans yanıtı: RESPOND_NORMALLY" kalsın. Lisans test kullanıcıları gerçek ödeme yapmaz; test kartıyla ("Test kartı, her zaman onaylar" / "her zaman reddeder" / "yavaş onay" = bekleyen ödeme) dener.
- Test aboneliklerinde süreler kısalır: aylık yenileme ~5 dakika, **3 günlük deneme ~3 dakika**; birkaç yenilemeden sonra abonelik kendiliğinden biter. Deneme → ücretli geçişini, bitişi ve iptali böyle deneyin.
- Denemeyi tekrar görmek için: Play'de test hesabının aboneliğini iptal edip süresinin bitmesini bekleyin; "Never had any subscription" uygunluğu nedeniyle aynı hesap denemeyi **tekrar almaz** — yeni bir lisans test hesabı kullanın.
- Uygulama **Google Play'den yüklenmiş** olmalı (dahili test bağlantısıyla). Elle yüklenen (yan yüklenen) APK'da Google Play faturalandırma çalışmaz; ekranda bunu anlatan bir mesaj çıkar.
- Denenecekler (kumandayla):
  1. Abonelik ekranı açılınca odak **Pro** kartında; üç kartta fiyat, "Ayda ~… saat oyun", satır sayısı ve (yeni hesapta) "3 gün ücretsiz dene" görünüyor.
  2. "Ne kadar oynuyorsun?" seçenekleri ilgili kartı vurgulayıp odağı ona taşıyor.
  3. Standart ile satın alma (deneme) → kart "Mevcut paketin", üst çubukta "Ücretsiz deneme etkin", kota "Deneme: 0 / 1.500 satır, deneme bitişi: …".
  4. Pro'ya **Yükselt** → Play ekranında orantılı ücret; sonra kart "Mevcut paketin" ve kota sınırı 11.000 (bu ay kullanılanlar taşınır).
  5. Standart'a **Düşür** → "mevcut paketiniz dönem sonuna kadar geçerli" bilgisi; test yenilemesinden sonra paket Standart.
  6. Kota dolması: sunucuda geçici olarak `PLAN_LIMITS` içinde ilgili paketi `5` yapın → 5 satırdan sonra altyazı cihaz içi çeviriyle sürer, **tek seferlik** "…paketine geçebilirsin" bildirimi çıkar (her satırda tekrar etmez). Sonra değeri geri alın.
  7. İptal (kullanıcı vazgeçti), bekleyen ödeme, "Aboneliği yönet", abonelik bitince motorun cihaz içi/Gemini'ye dönmesi, ana ekran rozetinde paket adı / "Abone ol", telefondaki ayar sayfasında paket ve kota çubuğu.

### Mağaza ve politika
- Abonelik koşulları uygulamada paket kartlarının altında yazılıdır (paket başına fiyat ve dönem, otomatik yenileme, 3 gün deneme ve kimlerin alabileceği, paket değişikliğinin ne zaman geçerli olduğu, hak dolunca ne olduğu, iptal yolu, gizlilik politikası bağlantısı). Mağaza açıklamasında da aboneliğin isteğe bağlı olduğunu ve paketleri belirtin.
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
- [ ] AltyazıTR Abonelik: ödeme profili, `altyazitr_abonelik` aboneliği + `standart` / `pro` / `premium` temel planları (fiyatlarıyla, etkin) + her birinde `deneme-3gun` teklifi (3 gün, yeni müşteriler), lisans test kullanıcıları, `PRO_API_BASE_URL` gizli değişkeni, sunucunun hizmet hesabı ve `PLAN_LIMITS`
- [ ] Önce **Dahili test** kanalı → sonra kapalı test → üretim
- [ ] Yeni kişisel geliştirici hesaplarında üretime çıkmadan önce kapalı testte belirli sayıda test kullanıcısı ve süre şartı olabilir; Play Console'daki güncel şartı kontrol edin.

## Bilinen riskler
- Sony ticari markası: ad/simgede "PlayStation", "PS5" logosu veya DualSense görseli kullanılmamalı; açıklamada uyumluluk belirtmek için isim geçmesi normaldir.
- Google TV incelemesi arayüzü kumandayla test eder; tüm ekranlar D-pad ile kullanılabilir olmalı.
