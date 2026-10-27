# Dosya Yöneticisi + (v3.6.7 - Kişisel Performans & Çoklu Bulut Arama Sürümü)

Bu proje, **Dosya Yöneticisi + (File Manager + 3.6.7)** uygulamasının birebir aynı menü, ekran ve işlev yapısını koruyarak; **reklamsız, telemetrisiz, ultra-düşük CPU/GPU/RAM tüketimli, modern minimalist vektörel ikonlu, AMOLED tam siyah (`#000000`) temalı ve Çoklu Bulut Hesaplarında Toplu Arama (Unified Multi-Account Cloud Search)** özellikleriyle sıfırdan optimize edilmiş Android kaynak kodudur.

## Öne Çıkan Özellikler

1. **Tüm Bulut ve Google Drive Hesaplarında Toplu Arama (Unified Cloud Search)**
   - **Bulut** ekranının en üstünde yer alan arama çubuğu sayesinde, eklediğiniz **10, 20 veya daha fazla Google Drive hesabı** ile Dropbox, OneDrive, Box, Nextcloud ve Yandex Disk hesaplarının tamamında **aynı anda (paralel Coroutines ile)** dosya arayabilirsiniz.
   - Her arama sonucunda dosyanın **hangi Google Drive hesabında (`☁ Google Drive (eposta@gmail.com)`)** bulunduğu açıkça gösterilir; tek dokunuşla dahili video oynatıcıda akış (stream) başlatabilir, **"Şununla aç"** (MediaInfo, Nova Video Player, mpvKt, 1DM+, Seal vb.) menüsünü açabilir veya doğrudan o hesabın ilgili klasörüne gidebilirsiniz.
   - Yerel bulut üstveri önbelleği (`CloudMetadataIndexCache`) sayesinde tekrar eden aramalar **0 ms** gecikmeyle anında listelenir.

2. **Birebir Arayüz + Modern Minimalist Vektör İkonlar**
   - **12'li Ana Ekran Izgarası**: `Ana bellek`, `İndirilenler`, `Bellek Analizi`, `Görüntüler`, `Ses`, `Videolar`, `Belgeler`, `Uygulamalar`, `Yeni Dosyalar`, `Bulut`, `Uzak` ve `Ağdan erişim` (Yatay ekranda 7 sütun, dikey ekranda 4 sütun otomatik duyarlı).
   - **Sol Gezinme Çekmecesi (`☰`)**: `Yer İmleri`, `Favoriler`, `Geçmiş`, `Geri Dönüşüm Kutusu`, sabitlenmiş `Google Drive` hesapları ve uzak `SMB/FTP` sunucuları.

3. **Açık, Koyu ve Gerçek `#000000` AMOLED Siyah Tema**
   - Üst araç çubuğundaki palet ikonundan, sol çekmecenin altından veya **Ayarlar > Görünüm ve Tema** bölümünden **Açık Tema**, **Koyu Tema** ve **AMOLED Tam Siyah (`#000000`)** arasında anında geçiş yapabilirsiniz.

4. **Tüm Kodek ve Uzantıları Destekleyen Dahili Video Oynatıcı (12–18 MB Kompakt APK Boyutu)**
   - **Media3 ExoPlayer + FFmpeg Decoder + Orijinal `exolibs.zip` Köprüsü**: `.mkv`, `.webm`, `.mp4`, `.avi`, `.mov`, `.flv`, `.ts`, `.m4v`, `.wmv`, `.3gp`, `AV1`, `HEVC (H.265)`, `VP9`, `H.264`, `AC3`, `EAC3`, `DTS`, `Opus`, `FLAC`, `Vorbis`, `AAC` formatlarını hem yerel depolamadan hem de doğrudan Google Drive / Bulut akışından oynatır.
   - Sıfır kopya `SurfaceView` donanım ivmesiyle GPU/CPU'yu yormaz ve cihazı ısıtmaz; donanım çözücünün desteklemediği bir formatta otomatik olarak yazılım (`SW-FFmpeg`) çözücüsüne geçer.
   - Sol/sağ çift dokunuşla **`-00:10` / `+00:10`** sarma, ekran kilidi (`🔒`), ekran döndürme (`⟳`), hız seçimi (`0.5x – 2.0x`), döngü ve **Şununla aç** menüsü içerir.

5. **Uzak Bağlantılar ve Ağdan Erişim (Dahili FTP Sunucusu)**
   - **Uzak**: `Yerel Ağ`, `SMB (SMB1/SMB2/SMB3)`, `FTP / FTPS`, `SFTP` ve `WebDAV`.
   - **Ağdan erişim**: Varsayılan `7552` bağlantı noktası, `Rastgele şifre`, `Şifre (123)` ve `Gizli dosyaları göster` seçenekleriyle tek tıkla (`BAŞLAT`) cihazınızı ağ üzerinden erişilebilir hale getirir.

---

## GitHub Actions ile APK Derleme Adımları

1. Bu ZIP dosyasının içeriğini yeni bir GitHub deposuna (repository) yükleyin.
2. GitHub deponuzda **Actions** sekmesine tıklayın.
3. **`Android APK Build (Dosya Yöneticisi +)`** iş akışı otomatik olarak başlayacaktır (veya **Run workflow** butonuna tıklayarak manuel tetikleyebilirsiniz).
4. Derleme tamamlandığında (**yaklaşık 2–3 dakika**), iş akışı sayfasının en altındaki **Artifacts** bölümünden **`Dosya-Yoneticisi-Plus-APK`** paketini indirerek imzalı **`Dosya_Yoneticisi_Plus_v3.6.7_Release_Signed.apk`** dosyasını doğrudan telefonunuza veya tabletinize kurabilirsiniz.
