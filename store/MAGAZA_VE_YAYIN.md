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
- Kullanıcı Gemini'yi seçerse: **Uygulama etkinliği → Diğer kullanıcı tarafından oluşturulan içerik** (en yakın kategori; Google'ın güncel tanımlarını kontrol edin) — ekrandan okunan altyazı metni kullanıcının kendi anahtarıyla Google Gemini API'ye gönderilir; amaç: Uygulama işlevselliği; isteğe bağlı: Evet; şifreli: Evet.
- Paylaşılan veri: **Yok** (ML Kit verileri üçüncü taraflarla paylaşmaz; Google'a hizmet sağlayıcı olarak gönderim "paylaşım" sayılmaz).
- Veri silme isteği: Uygulama kendi sunucusunda veri tutmaz; kaldırınca cihazdaki her şey silinir.

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
- [ ] Önce **Dahili test** kanalı → sonra kapalı test → üretim
- [ ] Yeni kişisel geliştirici hesaplarında üretime çıkmadan önce kapalı testte belirli sayıda test kullanıcısı ve süre şartı olabilir; Play Console'daki güncel şartı kontrol edin.

## Bilinen riskler
- Sony ticari markası: ad/simgede "PlayStation", "PS5" logosu veya DualSense görseli kullanılmamalı; açıklamada uyumluluk belirtmek için isim geçmesi normaldir.
- Google TV incelemesi arayüzü kumandayla test eder; tüm ekranlar D-pad ile kullanılabilir olmalı.
