# SOYO Tek Onay v2.3

Bu sürüm, gönderilen **Önerilen** ve **Murat sohbet** ekran görüntülerindeki düzene göre ayarlandı.

## Akış

1. Bir kez Android **Ayarlar → Erişilebilirlik → SOYO Tek Onay** servisini aç.
2. Yardımcı uygulamada mesaj şablonunu seç. Varsayılan: `{isim} {hitap} selamlarrrr`.
3. SOYO'yu aç ve **Önerilen** sekmesine gel.
4. Servis ekrandaki sarı **Sohbet** düğmelerini bulur ve aynı satırın solundaki kullanıcı adını eşleştirir.
5. İlk uygun satırın sohbetini açar.
6. Sohbet ekranında mesaj kutusuna örneğin `Murat Bey selamlarrrr` yazar.
7. Normal modda ekranın altındaki `ONAYLA` düğmesiyle gönderim yapılır.
8. **Tam Otomatik Mod** açıksa onay beklemeden gönderir, listeye geri döner, sıradaki kişiyi açar; görünür uygun kişi kalmadığında listeyi kısa ve örtüşmeli adımlarla ileri kaydırır.
9. **Sadece VIP olanlara mesaj gönder** seçeneği açılırsa yalnızca aynı öneri satırında VIP rozeti algılanan kişiler işlenir. Bu seçenek varsayılan olarak kapalıdır.
10. Tam Otomatik Mod ana ekrandaki kutudan anında açılıp kapatılabilir ve varsayılan olarak kapalıdır.





## v2.3 uzun kullanımda hızın düşmesi / geç tıklama düzeltmesi

- Accessibility event ve gecikmeli taramalar artık tek bir tarama kuyruğunda birleştiriliyor; MainLooper üzerinde zamanla yüzlerce eski scan birikmiyor.
- Gesture callback + watchdog + content-change eventlerinin aynı anda tetiklediği tekrar taramalar coalesce edildi.
- Kaydırma kurtarmasının bekleme süreleri sabit hızı koruyacak şekilde kısaltıldı; takılma kurtarmaları korunuyor.
- Servis kesintisi/yeniden bağlanmasında bekleyen tarama bayrağı temizlenerek sahte kilit oluşması engellendi.
- Sohbet başına 5 saniyelik güvenli atlama sınırı korunuyor.
- Sürüm `2.3` / `versionCode 14`.

## v2.2 uzun kullanımda kaydırma kilidi düzeltmesi

- Android `dispatchGesture()` çağrısının `true` dönmesi artık listenin gerçekten kaydığı anlamına gelmiş sayılmıyor.
- Önerilen ekranı değişmezse kısa swipe → erişilebilirlik `ACTION_SCROLL_FORWARD` → güçlü/uzun swipe yöntemleri sırayla denenir.
- Lazy-load gecikmelerini yakalamak için güçlü kurtarma kaydırmalarından sonra ikinci bir zorunlu tarama yapılır.
- Aynı gesture hattının uygulama tarafından yutulmasına karşı kurtarma kaydırmaları hafif sağ/sol konum değiştirir.
- Çok uzun süre aynı listede kalınırsa Önerilen sekmesine seyrek yeniden dokunularak liste controller'ı uyandırılır.
- Tek bir sohbette gönderim 5 saniye boyunca sonuçlanmazsa bütün otomasyonun kilitlenmesi yerine o sohbet atlanıp devam edilir.
- Sürüm `2.2` / `versionCode 13`.

## v2.1 tam otomatik süreklilik + isim/tag düzeltmesi

- Erişilebilirlik servisi geçici olarak kesildiğinde watchdog otomatik yeniden başlatılır; uzun kullanımda sessizce durma azaltıldı.
- Sohbet kutusunda önceden kalmış bir taslak varsa tam otomatik mod artık o ekranda sonsuza kadar beklemez; taslağı silmeden kişiyi atlayıp listeye döner.
- İsim güvenilir biçimde okunamazsa akış kilitlenmez; kısa doğrulamadan sonra sohbet atlanır.
- `TAG`, `VIP`, `ID`, `rozet`, `badge`, `level`, `etiket` gibi arayüz metinleri isim kabul edilmez. Sohbet başlığında `contentDescription` artık isim kaynağı değildir.
- Matematik/fancy Unicode ve small-caps isim sadeleştirmesi genişletildi.
- Önerilenler ekranı birkaç tur değişmezse daha güçlü kurtarma kaydırması denenir.

## v2.0 kaydırma + devamlılık + VIP filtresi

- Otomatik kaydırma mesafesi yaklaşık yarım ekrandan kısa, örtüşmeli bir adıma düşürüldü; satırların arada kaybolma riski azaltıldı.
- Bazı cihazlarda `TYPE_VIEW_SCROLLED` olayı gelmese bile kaydırmadan kısa süre sonra zorunlu yeniden tarama yapılır.
- Tam otomatik akış için 900 ms aralıklı bir watchdog eklendi; erişilebilirlik olayı/callback kaybolduğunda akış yeniden kontrol edilir.
- Gönderim callback'i takılırsa mesaj körlemesine tekrar gönderilmez; önce mesajın gerçekten gidip gitmediği doğrulanır, sonra gerekirse gönderim akışı yeniden başlatılır.
- RuntimeException kurtarması da doğrudan yeniden tıklamak yerine önce gönderim durumunu doğrular.
- Ana ekrana isteğe bağlı **Sadece VIP olanlara mesaj gönder** filtresi eklendi. `VIP`, `VIP1`, `VIP3` gibi rozetler öneri satırıyla aynı hizada aranır.
- Servisin periyodik kontrolü yalnızca SOYO aktif penceresindeyken işlem yapar.
- Sürüm `2.0` / `versionCode 11` olarak güncellendi.

## v1.9 güvenlik + hız düzeltmeleri

- Liste satırından taşınan isim artık sohbet başlığıyla doğrulanır; başlık farklıysa gerçek sohbet başlığı esas alınır.
- Her sohbet açılışına işlem kimliği verilir; önceki sohbetten gecikmiş gönderme görevleri yeni sohbette tıklama yapamaz.
- Otomatik gönderme öncesi hem sohbet adı hem mesaj kutusundaki metin tekrar doğrulanır.
- Gönderme tıklaması kabul edildi diye hemen tamamlandı sayılmaz; mesaj kutusunun temizlenmesi veya gönderilen mesajın görünmesi doğrulanır.
- SOYO gönderimden sonra zaten Önerilenler'e döndüyse artık fazladan geri basılmaz; uygulamadan çıkma riski engellenir.
- Geri dönüş sırasında SOYO yanlışlıkla arka plana düşerse Tam Otomatik Mod kurtarma amacıyla uygulamayı öne almaya çalışır.
- Tam Otomatik Mod başka bir SOYO sekmesine düşerse alt menüdeki `Önerilen` sekmesini bulup kendisi geri döner.
- Öneri satırı isim eşleştirmesinde dikey tolerans daraltıldı; komşu satırdaki kişinin adının alınma riski azaltıldı.
- Tarama, gönderme, geri dönüş ve kaydırma beklemeleri güvenli sınırlar içinde kısaltıldı.
- Sürüm `1.9` / `versionCode 10` olarak güncellendi.

## v1.8 düzeltmeleri

- Tam Otomatik Mod açıkken `ONAYLA` penceresine düşme kaldırıldı. Gönder düğmesi geç oluşursa otomatik yeniden denenir ve mesaj gerektiğinde yeniden yazılır.
- Sohbetten çıkışta çift geri basma kaldırıldı. Listeye dönüş tamamlanana kadar yeni sohbet açılması kilitlenir.
- Kaydırma için `canPerformGestures` açıldı ve erişilebilirlik `scroll` eylemi çalışmazsa gerçek yukarı kaydırma gesture'ı eklendi.
- Sürüm `1.8` / `versionCode 9` olarak güncellendi.

## Ekran görüntülerine özel algılama

- İsim, `Sohbet` düğmesinin solunda ve aynı satırda aranır.
- `VIP1`, `VIP3`, `125km`, yaş değerleri, `Önerilen`, `Yeniler`, `Ana Sayfa`, `Mesaj` gibi yazılar isim kabul edilmez.
- Aynı servis oturumunda gönderilen/atlanmış kişi, Önerilenler'e dönüldüğünde hemen tekrar açılmaz.
- Tam otomatik modda gönderimden sonra listeye dönülür; ekrandaki kişiler işlendiğinde öneri alanı gerçek kaydırma hareketiyle yukarı kaydırılır. Erişilebilirlik listesi kaydırma desteği vermezse gesture yedeği devreye girer.
- Mesaj gönderilince sohbet ekranındaki sol üst geri oku bulunup **yalnızca bir kez** tıklanır. Ok erişilebilir değilse Android geri işlemi **tek kez** kullanılır; ikinci otomatik geri komutu gönderilmez.
- Listeye dönüşten sonra sıradaki işlenmemiş kişi otomatik açılır. Görünür aday kalmadığında öneri listesi ileri kaydırılır ve tarama sürer.
- Sohbet ekranında ad üst orta başlıktan da okunabilir; `Murat VIP2` tek metin olarak gelirse `VIP2` otomatik temizlenip `Murat` kullanılır.
- Alt kısımdaki gri mesaj alanı düzenlenebilir kutu olarak aranır.
- Gönder düğmesinin erişilebilirlik etiketi yoksa, mesaj kutusunun sağındaki tıklanabilir ikonlardan **en sağdaki kâğıt uçak konumuna** öncelik verilir; emoji ikonunun seçilmemesi için konum puanlaması uygulanır.
- `Turkey`, `Kişilik benzerliği`, hazır mesaj önerileri, `Çevrimiçi`, `VIP` ve sayısal rozetler isim kabul edilmez.
- Süslü Unicode adlar (ör. `𝓜𝓾𝓻𝓪𝓽`), emoji/taç çerçeveleri ve aksan süsleri sadeleştirilir; `[VIP3]`, `(Lv.12)`, `@etiket`, `#etiket`, rozet ve seviye metinleri mesaja yazılmaz.
- SOYO arayüzü ileride değişirse satır eşleştirme kurallarının yeniden ayarlanması gerekebilir.

## APK üretme

Projeyi Android Studio ile açıp **Build → Build APK(s)** kullanabilirsin.

Alternatif olarak GitHub'a yükleyip **Actions → Build Android APK → Run workflow** çalıştır. Çıktı artifact olarak `app-debug.apk` olur.

## Not

Erişilebilirlik servisleri Android tarafından güçlü izinler olarak değerlendirilir. Bu proje yalnızca `com.haflla.soulu` ve `com.haflla.soulu.lite` paketleriyle sınırlandırılmıştır.
