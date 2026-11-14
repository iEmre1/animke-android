# Animke Android

[animke.com](https://animke.com)'u açan Android uygulaması ([Capacitor](https://capacitorjs.com)).
Site güncellendikçe uygulama da güncel kalır; APK yalnızca uygulamanın kendisi değişince yeniden yayınlanır.

**İndir:** [Animke.apk](https://github.com/iEmre1/animke-android/releases/latest/download/Animke.apk)

## Yeni sürüm yayınlama

1. `package.json` içindeki `version`ı artır (ör. `1.0.1`).
2. `git commit -am "1.0.1" && git tag v1.0.1 && git push --follow-tags`

GitHub Actions imzalı APK'yı derler ve Releases sayfasında yayınlar.

## İmzalama anahtarı (bir kez)

`bash anahtar-olustur.sh` (Git Bash). Anahtarı oluşturur ve GitHub'a gizli değişken olarak yükler.
Oluşan `anahtar/animke-android.p12` dosyasını ve şifresini yedekle; depoya girmez.

## Yapı

- `capacitor.config.json` — uygulama kimliği `com.animke.app`, açılan adres, sistem çubukları
- `www/offline.html` — internet yokken gösterilen sayfa
- `android/app/src/main/java/com/animke/app/MainActivity.java` — video tam ekranı ve geri tuşu
- `assets/` — simge ve açılış ekranı kaynakları (`npm run assets` ile yeniden üretilir)
