# TazPlay 🎮

Launcher game retro Android dengan emulator bawaan. Library game dari folder yang dipilih sendiri (SAF), tap game = main.

Inti app adalah **frontend libretro generik** tulisan sendiri (C/JNI + Java): core emulator berupa `.so` yang di-load saat runtime, jadi menambah sistem = menambah core, bukan menulis app baru.

> **Tidak ada ROM dan tidak ada BIOS di repo ini.** Semua game & BIOS berasal dari dump milik pengguna sendiri.

## Status (v0.4.2)

- 📚 **Library** — scan folder rekursif, klasifikasi PS1/PS2/PSP/N64/NDS/NES dari nama folder + ekstensi, chip filter, override sistem per game (tekan-tahan). ROM NES `.nes`, `.unf`, `.unif`, dan `.fds` dikenali otomatis.
- 🕹️ **PS1 main di dalam app** — core [PCSX-ReARMed](https://github.com/libretro/pcsx_rearmed) (libretro) dicompile dari source untuk ARM64. Terverifikasi jalan di Xiaomi 15T Pro.
- 🎛️ **Kontrol** — overlay sentuh (D-pad, △○✕□, L1/R1, Select/Start) + gamepad/keyboard fisik, termasuk stik analog DualShock.
- ⚙️ **Setelan player** — Video (rasio Asli/Stretch/Zoom, filter Tajam/Halus, orientasi, overlay FPS), Audio (on/off, latensi rendah/stabil), Gamepad (jenis stik, deadzone), Kontrol Sentuh (ukuran tombol, getar).
- 💾 **Save state** 3 slot per game (terpisah dari kartu memori yang auto-save).
- 🧬 **BIOS** — mode HLE bawaan core, atau file BIOS asli dari dump pengguna (dipilih sekali lewat file picker).
- Sistem selain PS1 (PS2/PSP/N64/NDS/NES) dibuka lewat emulator eksternal yang terpasang. Untuk NES, TazPlay mencoba NES.emu, Lemuroid, lalu RetroArch; jika tidak ada yang cocok, Android menampilkan pemilih app. **PS2 tetap eksternal, permanen** — tidak ada core libretro PS2 yang layak di Android.

## Cara build

Build sengaja **tanpa Gradle** (aapt2 + javac + d8 + NDK clang) — lihat `build.sh`.

Prasyarat: JDK 17, Android SDK (platform 34 + build-tools 34.0.0), Android NDK r26d.

1. **Build core PS1** (sekali saja, atau ambil dari rilis):

   ```bash
   git clone --depth 1 --recurse-submodules https://github.com/libretro/pcsx_rearmed
   cd pcsx_rearmed
   # perbaiki path root di jni/Android.mk:
   #   ROOT_DIR := $(abspath $(LOCAL_PATH)/..)
   ndk-build
   cp libs/arm64-v8a/libretro.so /path/ke/tazplay/app/src/main/libpcsx_rearmed_libretro.so
   ```

2. **Build APK**:

   ```bash
   ./build.sh   # hasil: build-manual/TazPlay.apk (debug-signed)
   ```

## Struktur

```
app/src/main/
├── AndroidManifest.xml
├── cpp/tazcore.c            # frontend libretro: video (Surface), audio (AAudio), input, save state
├── java/id/taz/tazplay/
│   ├── MainActivity.java    # library game + launcher eksternal
│   ├── PlayerActivity.java  # player PS1: overlay kontrol + panel setelan
│   └── NativeCore.java      # jembatan JNI
└── res/                     # ikon & warna
build.sh                     # pipeline build manual
```

## Roadmap

- **v0.4.x** — poles PS1 (berjalan).
- **v0.5** — core kedua: NDS (melonDS) atau PSP (core PPSSPP).
- **v0.6** — N64 (Mupen64Plus-Next).

## Lisensi

- Frontend TazPlay: © taz.
- Core PCSX-ReARMed berlisensi **GPL** (lihat repo upstream-nya). Karena itu, distribusi APK yang memuat core wajib disertai source yang bersesuaian — repo ini salah satunya.
