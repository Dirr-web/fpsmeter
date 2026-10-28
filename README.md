# FPS Meter (overlay ringan)

Overlay FPS, CPU, GPU, suhu untuk Android 12 / MIUI 13. Tanpa library tambahan, tanpa XML layout,
satu proses shell yang hidup terus, sampling 1x/detik, berhenti sendiri saat layar mati.

## Build
**Opsi A, Android Studio:** File > Open folder ini, tunggu sync, Build > Build APK(s).
**Opsi B, GitHub:** upload folder ini ke repo, tab Actions > Build APK, download artifact `fpsmeter-apk`.

## Setup di HP
1. Install APK, buka, tekan "Izinkan overlay".
2. Developer options: aktifkan USB debugging (dan "USB debugging (Security settings)" di MIUI).
3. Dari PC: `adb shell pm grant com.dirr.fpsmeter android.permission.DUMP`
   (atau pakai Shizuku/LADB di HP sendiri lewat wireless debugging).
4. Settings MIUI: Hemat baterai "Tanpa batasan", Autostart ON, izin pop-up di latar belakang ON.
5. Tekan "Mulai", buka game.

## Cara kerja
- FPS: `dumpsys SurfaceFlinger --latency <layer game>`, timestamp frame asli.
- CPU: `/proc/stat` kalau bisa dibaca, kalau tidak estimasi dari frekuensi (tanda `~`).
- GPU: sysfs Adreno (kgsl) kalau ada. Chipset lain bisa tampil `--` dan perlu disesuaikan path-nya.
- Mode root (su) membuka semua path di atas.
