# AltyazıTR Oyun — Gizlilik Politikası

Son güncelleme: 25 Ekim 2026

AltyazıTR Oyun ("uygulama"), PlayStation oyunlarındaki İngilizce altyazıları Türkçeye çevirip TV ekranında gösteren bağımsız bir uygulamadır. Bu politika hangi verilerin nasıl işlendiğini açıklar.

## Özet
- Hesap açmanız gerekmez; adınız, e-postanız, konumunuz veya ödeme bilginiz toplanmaz. Ödemeyi tamamen Google Play alır; kart bilgileriniz bize ulaşmaz.
- Oyun görüntüsü hiçbir zaman cihazdan çıkmaz. Varsayılan (cihaz içi) çeviride altyazı metinleri de **yalnızca cihazınızda** işlenir.
- Yalnızca siz **Gemini** (kendi anahtarınızla) veya **AltyazıTR Yapay Zekâ** (abonelik) çevirisini seçerseniz, ekrandan okunan altyazı metni çeviri için internete gönderilir (ayrıntılar aşağıda).
- Reklam yoktur, verileriniz satılmaz veya üçüncü taraflarla paylaşılmaz.

## Cihazda işlenen veriler
- **Oyun görüntüsü:** Uygulama, kendi ev ağınızdaki PlayStation konsolunuza Remote Play protokolüyle bağlanır. Gelen görüntü yalnızca altyazıyı okumak (metin tanıma) için kullanılır, kaydedilmez ve cihazdan çıkmaz.
- **Altyazı metinleri ve çevirileri:** Çeviri, cihaz üzerinde çalışan bir dil modeliyle yapılır. Metinler yalnızca kısa süreli bellekte tutulur.
- **Konsol eşleştirme bilgileri:** Konsolunuzun ağ adresi, eşleştirme anahtarları ve girdiğiniz PSN hesap kimliği (Account ID) yalnızca cihazınızda saklanır ve sadece konsolunuza bağlanmak için kullanılır. Uygulamayı kaldırdığınızda silinir.
- **Ayarlar:** Yazı boyutu, renk gibi tercihleriniz cihazınızda saklanır.

## Google ML Kit
Metin tanıma ve çeviri için Google'ın ML Kit kitaplığı kullanılır. Çeviri modeli ilk kullanımda Google sunucularından indirilir. ML Kit, hizmetin çalışmasını ve hata ayıklamayı sağlamak için cihaz/uygulama bilgileri, performans ölçümleri, hata kodları ve seçilen dil çifti gibi **tanılama verilerini** HTTPS ile şifreli olarak Google'a gönderebilir. Bu veriler oyun görüntüsü veya altyazı içeriği değildir. Ayrıntılar: https://developers.google.com/ml-kit/android-data-disclosure

## İsteğe bağlı Gemini çevirisi
Varsayılan çeviri cihaz içinde yapılır. Ayarlardan **Gemini** motorunu seçip kendi Google API anahtarınızı girerseniz: ekrandan okunan İngilizce altyazı metni ve tutarlılık için en fazla 4 önceki altyazı çifti (İngilizce ve Türkçe), sizin anahtarınızla Google'ın Gemini API'sine (generativelanguage.googleapis.com) HTTPS ile gönderilir ve Google'ın API koşullarına tabidir. Görüntü, ses, video veya cihaz kimliği gönderilmez. Google'ın ücretsiz kullanım katmanında gönderilen içerik Google tarafından ürünlerini geliştirmek için kullanılabilir. API anahtarınız ve çeviri önbelleği (en fazla yaklaşık 2000 satır) yalnızca TV'nizde saklanır ve cihaz yedeklerine dahil edilmez.

## AltyazıTR aboneliği (isteğe bağlı aylık abonelik: Standart, Pro veya Premium paketi)
AltyazıTR'ye abone olursanız (3 günlük ücretsiz deneme dahil) yapay zekâ çevirisi, AltyazıTR'nin kendi sunucusu (Cloudflare üzerinde çalışan bir ara sunucu) üzerinden Google'ın ücretli Gemini API'si ile yapılır:
- **Gönderilen:** Ekrandan okunan İngilizce altyazı satırı ve tutarlılık için en fazla 4 önceki altyazı çifti (İngilizce ve Türkçe). Görüntü, ses, video, cihaz kimliği, konum veya hesap bilgisi gönderilmez. Bağlantı HTTPS ile şifrelidir.
- **Sunucuda tutulan:** Altyazı metinleri **kaydedilmez ve günlüklere yazılmaz**. Paketinizin satır hakkı için yalnızca abonelik başına çevrilen satır **sayısı** (aylık; denemede deneme boyunca) ve hata kodları tutulur. Abonelik, satın alma jetonunun tek yönlü özetiyle (SHA-256) tanınır; paket değiştirdiğinizde Google yeni bir jeton verir ve sunucu, aynı ay içindeki satır sayısının devam etmesi için eski ve yeni jetonun özetlerini birbirine bağlayan bir kayıt tutar (en fazla ~13 ay). Adınız veya e-postanız sunucuya gelmez. Aynı cümlenin tekrar çevrilmemesi için sunucu kısa süreli bir çeviri önbelleği tutabilir; bu önbellek kimseyle ilişkilendirilmez.
- **Google Gemini (ücretli API):** Metin, çevrilmek üzere Google'ın ücretli Gemini API'sine gönderilir. Ücretli API'de Google, gönderilen içeriği **ürünlerini geliştirmek veya model eğitmek için kullanmaz**; yalnızca kötüye kullanımı tespit etmek amacıyla sınırlı süre saklayabilir (Google'ın Gemini API koşulları). AltyazıTR de metinleri hiçbir model eğitiminde kullanmaz.
- **Abonelik doğrulaması:** Uygulama, Google Play'in verdiği satın alma jetonunu sunucumuza gönderir; sunucu bu jetonu Google Play Developer API ile doğrulayarak aboneliğin etkin olup olmadığını, hangi paketin geçerli olduğunu ve ücretsiz denemede olup olmadığınızı kontrol eder. Ödeme ve fatura bilgileri yalnızca Google Play'dedir.
- **Cihazda:** Satın alma jetonu, kısa süreli oturum jetonu, paket adı, son kota bilgisi ve abonelik çeviri önbelleği yalnızca TV'nizde saklanır ve cihaz yedeklerine dahil edilmez.
- Aboneliği istediğiniz zaman Google Play → Ödemeler ve abonelikler bölümünden iptal edebilirsiniz. İptal edip abonelik çevirisini kullanmayı bıraktığınızda (veya paketinizin satır hakkı dolup cihaz içi çeviriye geçildiğinde) sunucuya hiçbir metin gönderilmez.

## Telefondan kontrol
TV uygulaması, ev ağınızda telefonunuzun açabileceği bir kontrol sayfası sunar. Sayfa yalnızca yerel ağdan erişilebilir ve her açılışta değişen bir kodla korunur; bu sayfa üzerinden girilen bilgiler (ayarlar, eşleştirme bilgileri, isteğe bağlı API anahtarı) yalnızca TV'nize gider.

## İzinler
- **İnternet / ağ:** Konsolunuza ev ağı üzerinden bağlanmak, çeviri modelini indirmek ve (seçerseniz) Gemini veya AltyazıTR abonelik çevirisi için.
- **Google Play faturalandırma:** AltyazıTR aboneliğini (paket seçimi, deneme, paket değişikliği) Google Play üzerinden yapabilmek için.
- **Diğer uygulamaların üzerinde gösterme:** Türkçe altyazıyı HDMI görüntüsünün üstünde göstermek için.
- **Ön plan hizmeti:** Siz oynarken altyazı çevirisinin arka planda çalışmaya devam etmesi için.

## Çocuklar
Uygulama çocuklara yönelik değildir ve bilerek çocuklardan kişisel veri toplamaz.

## Değişiklikler
Bu politika güncellenirse yeni sürüm bu sayfada yayımlanır. Abonelikle ilgili verileri silmek isterseniz aşağıdaki adrese yazın; sunucuda yalnızca satır sayıları ve jeton özetleri bulunduğundan bunlar talep üzerine silinir.

## İletişim
Sorularınız için: o.yilmaz6607@gmail.com

---
AltyazıTR Oyun, Sony Interactive Entertainment ile bağlantılı değildir ve onun tarafından onaylanmamıştır. "PlayStation" ve "PS5", Sony Interactive Entertainment Inc.'in ticari markalarıdır.
