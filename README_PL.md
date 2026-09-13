# Whisper Files Android v0.5

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

## Poprawka v0.5.1: początek napisów

Pierwszy napis zaczyna się teraz dokładnie przy pierwszym słowie mowy. Aplikacja korzysta z czasu pierwszego poprawnego tokena/słowa Whispera, zamiast z początku segmentu, który może obejmować ciszę od `00:00`. Dzięki temu cisza przed rozpoczęciem mowy nie powoduje wyświetlania napisów zbyt wcześnie.

## Nowości v0.5

### Suwaki rozmiaru i położenia

Zamiast list `Małe / Średnie / Duże` oraz `Dół / Środek / Góra` są teraz dwa suwaki procentowe:

- `Rozmiar napisów`: 2,0–10,0% wysokości obrazu (domyślnie 4,8%),
- `Położenie napisów`: 0–100% od góry obrazu (domyślnie 88%, czyli dół kadru).

Blok napisów jest dodatkowo pilnowany, żeby przy dowolnej kombinacji rozmiaru i położenia nie wyszedł poza kadr.

Stare ustawienia z v0.4 są migrowane automatycznie do równowartości procentowych, więc nie trzeba niczego ustawiać od nowa.

### Kolor wyróżnienia aktywnego słowa

Obok suwaków `Powiększenie aktywnego słowa` i `Zaznaczenie aktywnego słowa` wybierasz kolor wyróżnienia z sześciu próbek: żółty, pomarańczowy, czerwony, zielony, cyjan i różowy. Suwak zaznaczenia decyduje, jak mocno kolor łączy się z białą czcionką (0% = brak koloru, 100% = pełny kolor).

Podglâd w edytorze i wypalanie do MP4 używają dokładnie tej samej wartości rozmiaru, położenia i koloru (`SubtitleStyle` → `SubtitlePainter`).

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

W głównym ekranie są suwaki:

- `Rozmiar napisów`: 2,0–10,0% wysokości obrazu,
- `Położenie napisów`: 0–100% od góry obrazu,
- `Powiększenie aktywnego słowa`: 100–140%,
- `Zaznaczenie aktywnego słowa`: 0–100%,

dodatkowo wybór koloru wyróżnienia. Domyślnie: 112% powiększenia i 55% zaznaczenia.

Dla starych transkrypcji z v0.2, które nie mają osi słów, v0.3 potrafi utworzyć przybliżone czasy słów z istniejącego SRT. Najdokładniejsze śledzenie słów wymaga nowej transkrypcji wykonanej przez v0.3.

## Przepływ pracy

1. Wybierz WAV albo MP4.
2. Wybierz model `whisper.cpp` `.bin`.
3. Kliknij `Transkrybuj i utwórz napisy`.
4. Dla MP4 ustaw suwakami rozmiar i pozycję napisów, tło, kolor wyróżnienia i parametry śledzenia słowa.
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

`out\Whisper_app-v0.5.0-arm64-release.apk` (oraz `...-debug.apk`)

APK jest dla `arm64-v8a`.

## Poprawka v0.5.2: prawdziwy początek mowy (VAD)

Poprzednie wersje próbowały brać początek pierwszego słowa z token timestamps Whispera. To nie rozwiązuje przypadku, gdy pierwszy segment/token jest oznaczony od 00:00 mimo 1–3 s ciszy na początku.

v0.5.2 włącza wbudowany w whisper.cpp **Silero VAD v6.2.0**. VAD najpierw wykrywa faktyczne fragmenty mowy, a whisper.cpp mapuje timestampy segmentów i tokenów z powrotem na oryginalną oś czasu pliku. Pierwszy wykryty moment mowy jest dodatkowo używany jako twarda dolna granica timestampu pierwszego segmentu/słowa.

Model VAD (~0.88 MB) jest dołączony do APK jako `ggml-silero-v6.2.0.bin`; użytkownik nie musi go wybierać ani pobierać osobno.

Dodatkowo zachowywany jest dodatni PTS początku ścieżki audio z MP4. Jeśli sama ścieżka audio zaczyna się np. 300 ms po początku obrazu, napisy dostają również ten offset.

Parametry VAD: threshold 0.50, min speech 120 ms, min silence 180 ms, speech pad 30 ms. Dzięki temu krótkie polskie słowa nie są łatwo gubione, a napisy nie powinny startować od ciszy.
