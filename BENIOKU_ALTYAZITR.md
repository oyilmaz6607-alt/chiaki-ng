# AltyazıTR Oyun — Google TV için Türkçe altyazılı PlayStation Remote Play

PS4/PS5 oyununu Google TV'ye Remote Play ile aktarır, oyundaki **İngilizce altyazıyı ekrandan okur ve anında Türkçeye çevirip İngilizcenin üstüne yazar.** OCR ve çeviri tamamen TV'de çalışır (Google ML Kit); görüntü veya metin dışarı gönderilmez. Tek internet kullanımı, ilk açılışta ~30 MB'lık Türkçe çeviri modelinin indirilmesidir.

Açık kaynak [chiaki-ng](https://github.com/streetpea/chiaki-ng) üzerine kuruludur (AGPL-3.0).

## Nasıl çalışır

```
PS5 ──Remote Play──► TV'deki uygulama ──► görüntü ekranda
                                     └─► her 300 ms: ekranın alt %35'i kopyalanır (PixelCopy)
                                          → OCR (ML Kit Latin) → altyazı satırları seçilir
                                          → 2 karede sabitse ML Kit ile EN→TR çeviri (önbellekli)
                                          → Türkçe metin İngilizce altyazının tam üstüne çizilir
```

Altyazı seçimi (`SubtitleFilter.kt`): ekranın ortasındaki, harf ağırlıklı satırlar alınır; köşelerdeki HUD yazıları (cephane, can vb.) atılır; alt alta duran satırlar tek altyazı olarak birleştirilir. OCR titremesi benzerlik oranıyla (%85) tolere edilir, harf harf beliren yazılar sabitlenmeden çevrilmez.

## Eklenen / değişen dosyalar

| Dosya | Ne yapıyor |
|---|---|
| `android/app/src/main/java/com/metallic/chiaki/subtitle/SubtitleTranslator.kt` | Ekran yakalama, OCR, çeviri, konumlandırma |
| `android/app/src/main/java/com/metallic/chiaki/subtitle/SubtitleFilter.kt` | Altyazıyı bulma ve tekrar/titreme kontrolü (saf Kotlin, testli) |
| `android/app/src/test/.../SubtitleFilterTest.kt` | Birim testleri |
| `stream/StreamActivity.kt`, `layout/activity_stream.xml` | Altyazı katmanı ve "Türkçe altyazı" anahtarı |
| `common/Preferences.kt`, `xml/preferences.xml`, `values/strings.xml` | Ayarlar: aç/kapa, üstüne yaz, taranan alan %, tarama hızı, yazı boyutu |
| `app/build.gradle`, `gradle/libs.versions.toml` | ML Kit bağımlılıkları, yeni paket adı `com.altyazitr.oyun` |
| `AndroidManifest.xml` | Yalnızca TV (leanback zorunlu), dinamik FileProvider |
| `.github/workflows/build-android-altyazitr.yml` | Bulutta .aab/.apk derlemesi |

## 1) Derleme (bilgisayara Android Studio kurmadan)

1. GitHub'da [streetpea/chiaki-ng](https://github.com/streetpea/chiaki-ng)'yi **Fork** edin (public kalmalı; AGPL gereği kaynak kod açık olmalı). Sonra bilgisayarınızda yamayı uygulayıp gönderin:
   ```bash
   git clone https://github.com/KULLANICI/chiaki-ng.git && cd chiaki-ng
   git apply altyazitr.patch
   git add -A && git commit -m "Türkçe altyazı çevirisi + Google TV" && git push
   ```
2. İmza anahtarı oluşturun (bir kez, kaybetmeyin):
   ```bash
   keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
   base64 -w0 upload.jks > upload.b64
   ```
3. Depoda **Settings → Secrets and variables → Actions** altına ekleyin: `KEYSTORE_BASE64` (upload.b64 içeriği), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`upload`), `KEY_PASSWORD`.
4. **Actions → Android (AltyazıTR) → Run workflow.** ~20-30 dk sonra `altyazitr-release` çıktısında `.aab` ve `.apk` olur.

## 2) TV'de denemek (Play Store'dan önce)

Google TV'de: Ayarlar → Sistem → Hakkında → "Android TV OS derlemesi"ne 7 kez basın → Geliştirici seçenekleri → USB/ağ hata ayıklama açık.
```bash
adb connect TV_IP_ADRESI
adb install app-release.apk
```
Uygulamada PS5'i kaydedin (PS5: Ayarlar → Sistem → Remote Play → Cihazı Bağla, PIN'i girin), oyunda **İngilizce altyazıyı açın**, oynayın.

## 3) Play Store'a yükleme

1. [Play Console](https://play.google.com/console) geliştirici hesabı (tek seferlik 25 $, kimlik doğrulama).
2. Uygulama oluştur → `.aab` dosyasını önce **Dahili test** kanalına yükleyin.
3. **Cihaz katalogu / Form faktörleri → Android TV**'yi ekleyin ve TV incelemesine gönderin. Gerekenler: 1280×720 TV ekran görüntüleri, 320×180 banner (`res/mipmap*/atv_banner` — şu an Chiaki görseli, **kendi görselinizle değiştirin**), tamamen kumanda/gamepad ile gezilebilir arayüz.
4. Mağaza kaydı: açıklamada "Sony ile bağlantılı değildir / onaylanmamıştır" uyarısı; başlıkta, ikonda **PlayStation logosu/adı kullanmayın** (açıklamada "PS5 ile uyumlu" demek normaldir). Açıklamaya GitHub kaynak kodu bağlantısını ekleyin.
5. **Veri güvenliği formu:** veri toplanmıyor/paylaşılmıyor (OCR ve çeviri cihazda). Gizlilik politikası URL'si yine de zorunlu — basit bir GitHub Pages sayfası yeterli.
6. Hedef API: TV uygulamaları için Android 14 (API 34) ve üstü yeterli; proje API 35 hedefliyor.

## Bilinen sınırlamalar / sonraki adımlar

- **Ana ekran telefon odaklı** (chiaki-ng'nin yüzen butonu). Google'ın TV incelemesi D-pad ile her şeye erişilebilmesini ister; ret gelirse ilk düzeltilecek yer `main/MainActivity.kt` ve kayıt ekranları.
- ML Kit cihaz içi çeviri anlaşılır ama edebi değil. Daha doğal çeviri için `SubtitleTranslator.translate()` içine bulut çeviri (ör. Google Cloud Translation / DeepL) eklenebilir — o zaman veri güvenliği formu değişir.
- Çok stilize/el yazısı fontlu altyazılar ve hareketli arka planlar OCR doğruluğunu düşürür. Ayarlardan "taranan alan" ve "tarama aralığı" oyuna göre ayarlanabilir.
- Remote Play gecikmesi refleks gerektiren oyunlarda hissedilir; kablolu Ethernet önerilir.
