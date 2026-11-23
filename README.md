# Master Gym Android App

Aplikasi Android sederhana berbasis **WebView** dengan Kotlin.

## Fitur

- Membuka website di dalam aplikasi Android
- JavaScript dan DOM storage aktif
- Loading indicator
- Dukungan tombol back untuk navigasi halaman
- Membuka link non-HTTP(S) ke aplikasi eksternal

## Ubah URL website

Edit file berikut:

```xml
app/src/main/res/values/strings.xml
```

Lalu ganti nilai berikut:

```xml
<string name="webview_url">https://example.com</string>
```

## Cara menjalankan

1. Buka folder ini di **Android Studio**.
2. Tunggu proses **Gradle Sync**.
3. Jalankan ke emulator atau perangkat Android.

### Activity tidak ditemukan saat menjalankan

Jika muncul error dengan package `com.mastergym.webview` dan activity
`com.rfgym.deemka.SplashActivity`, konfigurasi peluncuran menggunakan app ID
lama. Lakukan **Sync Project with Gradle Files**, lalu di **Run > Edit
Configurations**, pilih modul `app` dan **Launch: Default Activity**.
Jika masih terjadi, hapus dan buat ulang konfigurasi Android App untuk modul
`app`. App ID dan launcher saat ini adalah `com.rfgym.deemka` dan
`com.rfgym.deemka.SplashActivity`.

## Catatan

- `minSdk` saat ini: `24`
- `compileSdk` / `targetSdk`: `34`
- Package name: `com.rfgym.deemka`

Printer Repo : https://github.com/dantsu/escpos-thermalprinter-android
