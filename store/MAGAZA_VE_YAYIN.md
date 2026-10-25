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

★ AltyazıTR Pro (isteğe bağlı aylık abonelik)
Daha doğru ve doğal yapay zekâ çevirisi; API anahtarı gerekmez, Türkçe yazıldıkça ekranda belirir. Abonelik her ay otomatik yenilenir, Google Play'den istediğiniz zaman iptal edilebilir.

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
- **AltyazıTR Pro aboneleri için (yeni):** **Uygulama etkinliği → Diğer kullanıcı tarafından oluşturulan içerik** (ekrandan okunan altyazı metni + en fazla 4 önceki çift) — bizim sunucumuza ve oradan Google Gemini (ücretli API) servisine gönderilir; amaç: Uygulama işlevselliği; isteğe bağlı: Evet (yalnızca Pro'yu kullanan); aktarımda şifreli: Evet; sunucu metni saklamaz (yalnızca satır sayısı). Google burada hizmet sağlayıcı olduğu için "paylaşım" sayılmaz.
- **Satın alma geçmişi (Financial info → Purchase history):** Pro aboneliğinin etkin olup olmadığını doğrulamak için satın alma jetonu sunucuya gönderilir ve Google Play'e doğrulatılır; amaç: Uygulama işlevselliği / hesap yönetimi; isteğe bağlı: Evet; şifreli: Evet. Ödeme bilgisi (kart vb.) toplanmaz — onu Google Play işler.
- Kullanıcı Gemini'yi seçerse: **Uygulama etkinliği → Diğer kullanıcı tarafından oluşturulan içerik** (en yakın kategori; Google'ın güncel tanımlarını kontrol edin) — ekrandan okunan altyazı metni kullanıcının kendi anahtarıyla Google Gemini API'ye gönderilir; amaç: Uygulama işlevselliği; isteğe bağlı: Evet; şifreli: Evet.
- Paylaşılan veri: **Yok** (ML Kit verileri üçüncü taraflarla paylaşmaz; Google'a hizmet sağlayıcı olarak gönderim "paylaşım" sayılmaz).
- Veri silme isteği: Pro sunucusu yalnızca abonelik başına aylık satır sayısını tutar (metin tutmaz); talep e-postayla silinir. Kaldırınca cihazdaki her şey silinir.

## AltyazıTR Pro aboneliği — Play Console kurulumu
Uygulama ve sunucu aynı kimlikleri kullanır (ayrıntılar `PRO_API.md`): ürün kimliği **`altyazitr_pro`**, temel plan **`aylik`**, paket **`com.altyazitr.oyun`**.

1. **Ödeme profili:** Play Console → Ayarlar → Ödeme profili. Satış yapabilmek için bir ödeme (satıcı) profili bağlanmış olmalı; Türkiye için vergi bilgilerini doldurun.
2. **Faturalandırma kitaplığı olan bir sürüm yükleyin:** Abonelik oluşturma ekranı ancak Play Billing içeren bir sürüm (en az dahili test kanalına) yüklendikten sonra açılır. Bu sürüm Billing 8.0.0 içerir.
3. **Aboneliği oluşturun:** Para kazanma → Ürünler → **Abonelikler** → "Abonelik oluştur".
   - Ürün kimliği: `altyazitr_pro` (sonradan değiştirilemez).
   - Ad: "AltyazıTR Pro"; avantajlar: "Daha doğru ve doğal çeviri", "API anahtarı gerekmez", "Hızlı, akışlı çeviri".
4. **Temel plan:** "Temel plan ekle" → kimlik `aylik` → tür **Otomatik yenilenen**, faturalandırma dönemi **Aylık (1 ay)**. Ek süre (grace period) ve hesap askıya alma varsayılan kalabilir (sunucu ek süreyi de etkin sayar). Fiyatı belirleyin (ör. Türkiye için TL fiyat; diğer ülkeler için "fiyatları dönüştür"). Temel planı **Etkinleştir**.
5. **(İsteğe bağlı) Ücretsiz deneme:** Temel planda "Teklif ekle" → kimlik ör. `deneme-7gun` → uygunluk **Yeni müşteri edinme** ("bu aboneliği hiç almamış olanlar") → aşama **Ücretsiz deneme, 7 gün** → Etkinleştir. Uygulama deneme teklifini kendisi bulur ve düğmede "7 gün ücretsiz dene" yazar; denemeyi kullanmış kişiye Google bu teklifi göstermez.
6. **Gerçek zamanlı bildirimler / sunucu erişimi:** Sunucunun aboneliği doğrulayabilmesi için Google Cloud'da bir hizmet hesabı oluşturup Play Console → Kullanıcılar ve izinler'den bu hesaba uygulama için "Finansal verileri görüntüleme" ve "Siparişleri ve abonelikleri yönetme" izinlerini verin (anahtarı yalnızca sunucuya koyun; ayrıntılar sunucu belgelerinde).
7. **Sunucu adresi:** GitHub → Settings → Secrets → Actions → `PRO_API_BASE_URL` (ör. `https://altyazitr-pro.<hesap>.workers.dev`). Boş bırakılırsa uygulama "Pro hizmeti henüz hazır değil" der ve abonelik düğmesini göstermez.

### Test etme
- Play Console → Ayarlar → **Lisans testi** → test e-postalarını ekleyin (TV'deki Google hesabı). Lisans test kullanıcıları gerçek ödeme yapmaz; test kartıyla ("Test kartı, her zaman onaylar" / "her zaman reddeder" / "yavaş onay" = bekleyen ödeme) dener.
- Test aboneliklerinde süreler kısalır (ör. aylık yenileme ~5 dakika, deneme ~3 dakika); birkaç yenilemeden sonra abonelik kendiliğinden biter. Bitiş ve iptal akışını böyle deneyin.
- Uygulama **Google Play'den yüklenmiş** olmalı (dahili test bağlantısıyla). Elle yüklenen (yan yüklenen) APK'da Google Play faturalandırma çalışmaz; ekranda bunu anlatan bir mesaj çıkar.
- Denenecekler: satın alma, iptal (kullanıcı vazgeçti), bekleyen ödeme, "Aboneliği yönet" düğmesi, abonelik bitince motorun cihaz içi/Gemini'ye dönmesi, kota bilgisi.

### Mağaza ve politika
- Abonelik koşulları uygulamada satın alma düğmesinin yanında yazılıdır (fiyat, dönem, otomatik yenileme, deneme, iptal yolu, gizlilik politikası bağlantısı). Mağaza açıklamasında da Pro'nun isteğe bağlı aylık abonelik olduğunu belirtin.
- **Veri güvenliği formunu** yukarıdaki Pro maddelerine göre güncelleyin ve **gizlilik politikasının** güncel sürümünü (GIZLILIK.md) yayımlayın.
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
- [ ] AltyazıTR Pro: ödeme profili, `altyazitr_pro` aboneliği + `aylik` temel planı (+ isteğe bağlı deneme teklifi), lisans test kullanıcıları, `PRO_API_BASE_URL` gizli değişkeni, sunucunun hizmet hesabı
- [ ] Önce **Dahili test** kanalı → sonra kapalı test → üretim
- [ ] Yeni kişisel geliştirici hesaplarında üretime çıkmadan önce kapalı testte belirli sayıda test kullanıcısı ve süre şartı olabilir; Play Console'daki güncel şartı kontrol edin.

## Bilinen riskler
- Sony ticari markası: ad/simgede "PlayStation", "PS5" logosu veya DualSense görseli kullanılmamalı; açıklamada uyumluluk belirtmek için isim geçmesi normaldir.
- Google TV incelemesi arayüzü kumandayla test eder; tüm ekranlar D-pad ile kullanılabilir olmalı.
