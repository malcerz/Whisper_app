# Whisper Files Android v0.1

Minimalna aplikacja Android oparta na oficjalnym `ggml-org/whisper.cpp`.

Przypięty commit bazowy:

`52a939a2a762224e255d366c1182b2af4dd1a032` (master, 2026-09-04)

## Zakres v0.1

- brak nagrywania mikrofonem i brak `RECORD_AUDIO`,
- wejście: WAV oraz MP4 z dowolną ścieżką audio obsługiwaną przez Android `MediaExtractor`/`MediaCodec`,
- GoPro MP4/AAC jest obsługiwane natywnie, bez FFmpeg,
- dekodowanie strumieniowe,
- konwersja do mono 16 kHz float,
- Whisper w blokach 30 s z 2 s nakładką,
- język transkrypcji: polski (`pl`),
- anulowanie także wewnątrz aktualnego wywołania `whisper_full()` przez native abort callback,
- foreground service + partial wake lock: transkrypcja może działać po wygaszeniu ekranu,
- postęp względem czasu ścieżki audio,
- wyniki TXT i SRT zapisywane po każdym segmencie,
- podgląd w UI jest ograniczony do ostatnich ~30 tys. znaków, ale pliki wynikowe nie mają tego limitu,
- wybór modelu `.bin` przez Android Storage Access Framework; model jest kopiowany raz do prywatnego cache aplikacji.

## Model

Potrzebny jest wielojęzyczny model `whisper.cpp`, np.:

- `ggml-base.bin` — dobry pierwszy wybór,
- model quantized base/small — mniejszy RAM / plik,
- `ggml-small.bin` — lepsza jakość, ale dużo większy RAM i wolniejsza praca.

Nie używaj `*.en.bin` do polskiego.

## Build Windows

Wymagania:

- Git,
- JDK 17,
- Android SDK,
- Android NDK `25.2.9519653`,
- dostęp do internetu przy pierwszym pobraniu `whisper.cpp` i zależności Gradle.

Uruchom w PowerShell:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\build_windows.ps1
```

Gotowy APK pojawi się jako:

`out\WhisperFiles-v0.1-arm64-release.apk`

APK jest przeznaczony dla `arm64-v8a` i podpisany debugowym kluczem Gradle, więc nadaje się do bezpośredniego sideloadu/testów.

## Ograniczenia pierwszej wersji

- WAV: obsługiwany jest PCM 8/16/24/32-bit oraz float, o ile systemowy `MediaExtractor` danego telefonu rozpoznaje plik.
- MP4: działa przez systemowe kodeki Androida; AAC z GoPro jest głównym przypadkiem testowym.
- 2-sekundowa nakładka ogranicza urywanie słów na granicy bloków; jest też prosta deduplikacja powtórzonych segmentów.
- pełna transkrypcja nie jest trzymana w RAM, ale sam model Whisper oczywiście pozostaje w pamięci podczas pracy.
