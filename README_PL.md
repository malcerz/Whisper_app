# Whisper Files Android v0.3

Aplikacja Android oparta na oficjalnym `ggml-org/whisper.cpp`.

Przypięta baza Whisper:

`52a939a2a762224e255d366c1182b2af4dd1a032` (master, 2026-09-04)

## Najważniejsze funkcje

- transkrypcja WAV i MP4 offline,
- polski Whisper (`.bin`, model wielojęzyczny),
- długie pliki są obrabiane porcjami, bez wczytywania całego nagrania do RAM,
- TXT + SRT,
- ręczna edycja napisów po transkrypcji,
- wypalanie napisów na stałe do MP4 przez AndroidX Media3 Transformer,
- brak FFmpeg w APK,
- render jako foreground service z postępem i anulowaniem.

## Nowości v0.3

### Edycja napisów

Po transkrypcji MP4 dostępny jest przycisk `Edytuj napisy`.

Edytor pokazuje jeden napis naraz, pozwala przechodzić `Poprzedni / Następny` i ręcznie poprawiać błędy rozpoznawania. Podgląd nad polem edycji jest liczony tym samym silnikiem układu, który jest używany podczas końcowego renderu MP4. Dzięki temu liczba słów w wierszu i podział na 1/2 linie odpowiadają finalnemu filmowi.

Jeśli poprawka zmienia liczbę słów, czasy nowych słów są interpolowane w obrębie czasu poprawianego napisu. Jeśli liczba słów się nie zmienia, zachowywane są dotychczasowe czasy słów.

### Bezpieczny układ dla pionowego filmu

Napisy nie są już dzielone według sztywnej liczby znaków. `SubtitleLayoutEngine` mierzy tekst w pikselach przy faktycznej rozdzielczości filmu i wybranym rozmiarze fontu.

- używane jest maksymalnie **84% szerokości obrazu**, czyli około 8% bezpiecznego marginesu z każdej strony,
- maksymalnie 2 linie,
- dla pionowego filmu pojedynczy napis jest celowo krótszy: do około 2,5 s i zwykle maks. 6 słów,
- dla poziomego filmu limit jest luźniejszy,
- układ uwzględnia także maksymalne powiększenie aktywnego słowa,
- wyjątkowo długie pojedyncze słowo jest dodatkowo skalowane, aby nie wyjść poza bezpieczny obszar.

### Śledzenie wypowiadanego słowa

JNI włącza `token_timestamps` w `whisper.cpp`. Aplikacja zapisuje osobną oś czasu słów (`last_words.tsv`) i podczas renderowania zna czas początku i końca każdego słowa.

Aktualnie wypowiadane słowo może być:

- lekko powiększone,
- wyróżnione kolorem,
- śledzenie można całkowicie wyłączyć.

W głównym ekranie są dwa suwaki:

- `Powiększenie aktywnego słowa`: 100–140%,
- `Zaznaczenie aktywnego słowa`: 0–100%.

Domyślnie: 112% powiększenia i 55% zaznaczenia.

Dla starych transkrypcji z v0.2, które nie mają osi słów, v0.3 potrafi utworzyć przybliżone czasy słów z istniejącego SRT. Najdokładniejsze śledzenie słów wymaga nowej transkrypcji wykonanej przez v0.3.

## Przepływ pracy

1. Wybierz WAV albo MP4.
2. Wybierz model `whisper.cpp` `.bin`.
3. Kliknij `Transkrybuj i utwórz napisy`.
4. Dla MP4 ustaw rozmiar/pozycję napisów, tło i parametry śledzenia słowa.
5. Kliknij `Edytuj napisy` i popraw błędy transkrypcji.
6. `Zapisz SRT` generuje SRT ponownie według bieżącego układu filmu.
7. Kliknij `Wypal napisy do MP4`.
8. Po renderze kliknij `Zapisz MP4 z napisami`.

## Architektura renderowania

Nie jest używany FFmpeg. Aplikacja korzysta z AndroidX Media3 Transformer 1.11.0:

- `Transformer` — dekodowanie/enkodowanie i muxowanie,
- `OverlayEffect` — nakładka,
- `CanvasOverlay` — dynamiczny overlay zależny od czasu,
- `SubtitleLayoutEngine` — dokładne dzielenie napisów według szerokości w pikselach,
- `SubtitlePainter` — identyczne rysowanie w edytorze i finalnym MP4,
- systemowe `MediaCodec`/GPU — pipeline wideo.

Obraz musi być ponownie zakodowany, ponieważ napisy są wypalane w piksele. Audio nie ma efektów i Media3 może je przepuścić bez ponownego kodowania, gdy format na to pozwala.

## Model Whisper

Do polskiego używaj modelu wielojęzycznego, bez `.en`.

Na Snapdragonie 8s Gen 3 sensownym wyborem jest `ggml-large-v3-turbo-q8_0.bin`. Można także użyć `large-v3-q5_0` albo pełnego `large-v3`, kosztem większego RAM i czasu.

Model jest wybierany przez Storage Access Framework i kopiowany raz do prywatnego cache aplikacji.

## Build Windows

Wymagane:

- Git,
- JDK 17,
- Android SDK,
- NDK `25.2.9519653`.

Skrypt korzysta z:

- Android Gradle Plugin `8.10.1`,
- Gradle `8.11.1`,
- compileSdk `36`,
- AndroidX Media3 `1.11.0`.

Uruchom:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\build_windows.ps1
```

Wynik:

`out\WhisperFiles-v0.3-arm64-release.apk`

APK jest dla `arm64-v8a`.
