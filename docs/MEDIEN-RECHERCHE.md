# Ordo – Stand der Technik für Medien und Dateitypen

Stand: 16.11.2026. Grundlage für die Roadmap-Ziele „Musik-Player“, „Video-Player“, „PDF-Leser“, „PDF bearbeiten“,
„Markdown lesen und schreiben“, „Bildbetrachter auf Spitzenniveau“, „Jede gängige Datei öffnen …“ und die
Office-Entscheidung.

**Wie das entstand:** drei Recherchen mit der Vorgabe „nur Primärquellen“ (Google Maven, Maven Central, GitHub-API,
developer.android.com, LICENSE-Dateien, offizielle Spezifikationen; keine Blogs). Was dort nicht belegt war, steht
als „nicht belegt“ drin. Größen und 16-KB-Ausrichtung nativer Bibliotheken wurden an den heruntergeladenen AARs
gemessen. **Selbst nachgeprüft** sind bisher nur die Media3-Version (1.11.1 vom 11.09.2026, Google Maven und
GitHub-API). Alles andere wird vor dem jeweiligen Baustein noch einmal an der Quelle und auf Pixel und Tab geprüft.

## Entscheidungen auf einen Blick

| Baustein | Basis | Lizenz | Haupt-Risiko |
|---|---|---|---|
| Musik-Player | AndroidX Media3 1.11.1 (exoplayer, session, ui, inspector), `MediaLibraryService`, Vordergrunddienst `mediaPlayback` | Apache-2.0 | Überblenden und Lautheitsausgleich (ReplayGain) sind nicht eingebaut, Eigenbau; Play verlangt Erklärung mit Video |
| Tags, Cover, Liedtexte | TagLib | LGPL-2.1 | Android-Bindung ist ein kleines Projekt |
| Eigenes App-Symbol je eingebauter App | `activity-alias` + `setComponentEnabledSetting(DONT_KILL_APP)` | – | Haupt-Symbol lässt sich ab Android 10 nicht ausblenden; Play: nur mit Wissen und Zustimmung, leicht umkehrbar; Verhalten der Launcher (Pixel, One UI) messen |
| Video-Player | Media3 + nextlib-media3ext (FFmpeg, libass, dav1d) | GPL-3.0 | Ein-Personen-Projekt, an die Media3-Version gekoppelt; AVI/WMV erst mit libmpv als Zweit-Engine |
| PDF lesen | androidx.pdf 1.0.0-beta01 ab API 28, darunter die vorhandene PdfRenderer-Ansicht | Apache-2.0 | Beta; Doku widerspricht sich bei den Mindestanforderungen |
| PDF bearbeiten | `EditablePdfViewerFragment` (Stift, Marker, Formulare) | Apache-2.0 | als experimentell markiert; Alternative MuPDF ist AGPL-3.0 und +5,5 MB je ABI |
| Bildbetrachter | ZoomImage (`zoomimage-view-coil2`), HDR-Farbmodus bei Gain-Map, jxl-/avif-coder, EXIF-Drehung | Apache-2.0 / MIT | Coil verliert Ultra-HDR-Gain-Maps beim Hardware-Dekodieren (Issue offen) |
| Text und Code | Sora Editor mit tree-sitter | LGPL-2.1 | vom Hersteller als „in Arbeit“ bezeichnet; TextMate-Modul (EPL-2.0) meiden |
| JSON, YAML, TOML, XML, CSV, SQLite | ein Strukturbetrachter (Baum bzw. Tabelle) mit Umschalter zur Textansicht | – | Speicher bei großen Dateien: darüber nur lesender Streaming-Modus |
| Markdown | Leser mit gepflegtem Renderer (Compose-Insel) oder eingefrorenes Markwon; Schreiben mit Sora | Apache-2.0 | kein gepflegter Renderer für klassische Views |
| E-Books | Readium Kotlin Toolkit | BSD-3 | Größe nicht gemessen |
| Office (DOCX, XLSX, PPTX, ODT) | **keine eigene Engine**: Weitergabe an eine Office-App, später höchstens einfache Inhaltsvorschau | – | Bearbeiten in Spitzenqualität ist auf dem Gerät mit Open Source nicht realistisch (LibreOfficeKit 80–95 MB, vom Hersteller „experimentell“) |

## Offene Entscheidungen für Belkis

1. Office nur als Weitergabe an eine Office-App – oder doch eine eingeschränkte eigene Vorschau?
2. Markdown-Leser: ein zweites Oberflächen-System (Compose) in die App holen oder Markwon einfrieren?
3. PDF bearbeiten: Googles experimentelle Bibliothek abwarten oder MuPDF (AGPL, größer) nehmen?

## Reihenfolge

1. Musik-Player, dann als eigenes App-Symbol
2. Video-Player (gleicher Media3-Unterbau)
3. Bildbetrachter auf Spitzenniveau
4. PDF lesen, danach PDF bearbeiten
5. Text- und Code-Editor, Strukturbetrachter für Datendateien, Markdown
6. E-Books, Untertitel, Kontakte und Kalender, Schriften
7. Office zuletzt, nur als Weitergabe

Jeder Baustein: APK-Zuwachs messen, Lizenz prüfen, 16-KB-Seiten prüfen, auf Pixel und Tab filmen, erst dann bleibt er.

---

## Anhang A – Musik-Player und eigenes App-Symbol

### Ordo Musik – Recherche-Brief (Stand 2026-11-16, nur Primärquellen, read-only)

Kürzel: RN = https://raw.githubusercontent.com/androidx/media/release/RELEASENOTES.md · FMT = https://developer.android.com/media/media3/exoplayer/supported-formats · BG = https://developer.android.com/media/media3/session/background-playback · GNU = https://www.gnu.org/licenses/license-list.html

#### 1. Playback-Engine
- Stabil: **Media3 1.11.1**, Release Notes 2026-09-10, auf Maven/GitHub 2026-09-11 (https://dl.google.com/android/maven2/androidx/media3/media3-exoplayer/maven-metadata.xml, https://api.github.com/repos/androidx/media/releases). 1.12.0-beta01 (2026-11-11) ist Vorabversion (gleiche Quelle).
- Module: `media3-exoplayer`, `media3-session`, `media3-ui` (XML-Views), `media3-inspector` (dorthin ist `MetadataRetriever` gezogen; die exoplayer-Variante wurde in 1.11.0 entfernt, RN); `common`/`extractor`/`decoder` kommen transitiv (https://dl.google.com/android/maven2/androidx/media3/group-index.xml).
- Gapless: Playlist-Übergänge sind nahtlos (https://developer.android.com/media/media3/exoplayer/playlists); 1.11.0 korrigiert MP3-Delay/Padding aus LAME/Xing (RN).
- Offload: per `AudioOffloadPreferences`, inkl. `setIsGaplessSupportRequired` (https://developer.android.com/media/media3/exoplayer/track-selection); schränkt Effekte wie Tempo und Silence-Skipping ein, Geräte-Tests nötig (https://developer.android.com/media/media3/exoplayer/battery-consumption).
- Preload des nächsten Titels: `ExoPlayer.setPreloadConfiguration(PreloadConfiguration(targetPreloadDurationUs))`, @UnstableApi, Standard aus (https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer.PreloadConfiguration).
- Crossfade: **nicht eingebaut**; Feature-Request #2 seit 2021-11-01 offen (https://github.com/androidx/media/issues/2).
- Tempo mit Tonhöhenerhalt: `PlaybackParameters(speed, pitch)` (https://developer.android.com/reference/androidx/media3/common/PlaybackParameters).
- Formate: ExoPlayer nutzt standardmäßig Plattform-Decoder; Container MP3, M4A, Ogg (Vorbis/Opus/FLAC), WAV, FLAC, Matroska (FMT). Plattform: FLAC ab Android 3.1, Opus ab 5.0 (https://developer.android.com/media/platform/supported-formats); FLAC-MediaCodec ist aber erst ab API 27 Pflicht (FMT) → für API 24–26 Erweiterung nötig. ALAC steht nicht in der Plattformliste; Decoder `alac` nur über die FFmpeg-Erweiterung (FMT).
- Erweiterungen (FLAC, Opus, FFmpeg …) müssen selbst gebaut werden, keine Maven-Artefakte (FMT; group-index oben).
- Lizenzen: Media3 Apache-2.0, native Bibliotheken „licensed separately" (https://raw.githubusercontent.com/androidx/media/release/libraries/decoder_ffmpeg/README.md). Apache-2.0 und LGPL-2.1 sind GPLv3-kompatibel (GNU). FFmpeg ist LGPL-2.1+, mit GPL-Teilen GPL (https://ffmpeg.org/legal.html); libopus 3-Klausel-BSD (https://opus-codec.org/license/); libFLAC New BSD (https://xiph.org/flac/license.html). Alles mit GPL-3.0 vereinbar.

#### 2. Hintergrundwiedergabe
- Player + Session in `MediaSessionService`; bei Inhaltsbibliothek `MediaLibraryService` (BG).
- Manifest: `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `foregroundServiceType="mediaPlayback"`, Intent-Filter `androidx.media3.session.MediaSessionService` und `android.media.browse.MediaBrowserService` (BG; https://developer.android.com/develop/background-work/services/fgs/service-types).
- Benachrichtigung: MediaStyle automatisch; ab API 33 füllt System-UI sie aus der Session; nach 10 min Pause verlässt der Dienst den Vordergrund (BG).
- Bluetooth/Headset-Tasten: laufen über die Session; Wiederaufnahme nach Dienst-Ende braucht `MediaButtonReceiver` + `onPlaybackResumption()` (BG).
- Audiofokus: ExoPlayer verwaltet ihn per `setAudioAttributes`; ab targetSdk 35 Fokus nur als Top-App oder mit laufendem FGS (https://developer.android.com/media/optimize/audio-focus).
- Becoming noisy: `setHandleAudioBecomingNoisy(true)` (https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer).
- Ab targetSdk 35 kein mediaPlayback-FGS aus `BOOT_COMPLETED` (FGS-Typen-Seite oben).
- Android Auto (optional): exportierter MediaBrowserService/MediaLibraryService + MediaSession, Launcher- und Attribution-Icon; Listung erfordert Auto-Qualitätsprüfung (https://developer.android.com/training/cars/media, https://developer.android.com/training/cars/media/configure-manifest).
- Play Console (Ziel Android 14+): je FGS-Typ Beschreibung, Auswirkung bei Aufschub/Unterbrechung, **Video-Link**, Anwendungsfall. Für Media Playback: „Show a user starting video or audio playback. Playback must be user-initiated" (https://support.google.com/googleplay/android-developer/answer/13392821).

#### 3. Lautheit
- Plattform: `LoudnessCodecController` (API 35) wertet Lautheits-Metadaten **im komprimierten Strom** nach CTA-2075 aus (https://developer.android.com/reference/android/media/LoudnessCodecController); Media3 konfiguriert das seit 1.5.0 automatisch (RN). Hilft nicht bei MP3/FLAC mit ReplayGain-Tags.
- ReplayGain: Media3 **parst** nur LAME-ReplayGain (1.9.1, RN); eine Anwendung der Werte ist nicht belegt. Bausteine: `GainProcessor` (@UnstableApi, https://developer.android.com/reference/androidx/media3/common/audio/GainProcessor); seit 1.11.0 bekommen AudioProcessors Timeline/Period-UID (RN, https://github.com/androidx/media/issues/418). Tags lesen und Gain setzen muss die App.
- Opus: `R128_TRACK_GAIN`/`R128_ALBUM_GAIN` statt REPLAYGAIN_* (https://www.rfc-editor.org/rfc/rfc7845.txt).
- EBU R128-Messung: keine Plattform-/Media3-Funktion gefunden; Scan ungetaggter Dateien wäre Eigenbau.

#### 4. Tags, Cover, Liedtexte
- `androidx.media3.inspector.MetadataRetriever` liefert TrackGroups/Timeline/Dauer ohne Player (https://developer.android.com/media/media3/exoplayer/retrieving-metadata). `MediaMetadata` hat Titel, Künstler, Album, Track/Disc, Genre, `artworkData`, **kein Liedtext-Feld** (https://raw.githubusercontent.com/androidx/media/release/libraries/common/src/main/java/androidx/media3/common/MediaMetadata.java); USLT/SYLT haben keinen eigenen Decoder, nur roher `BinaryFrame` (…/libraries/extractor/src/main/java/androidx/media3/extractor/metadata/id3/Id3Decoder.java).
- TagLib: taglib/taglib, LGPL-2.1, v2.3.2 vom 2026-09-05 (https://api.github.com/repos/taglib/taglib/releases/latest) – GPLv3-kompatibel (GNU).
- Kyant0/taglib (Android-Bindung), Apache-2.0, 1.0.6 vom 2026-04-15, 53 Sterne (https://api.github.com/repos/Kyant0/taglib).
- jaudiotagger-Fork AdrienPoupa: letzter Push 2018, keine Lizenz erkannt → nicht gepflegt (https://api.github.com/repos/AdrienPoupa/jaudiotagger). FFmpegMediaMetadataRetriever: Lizenz laut API nicht erkannt (https://api.github.com/repos/wseemann/FFmpegMediaMetadataRetriever).

#### 5. Bibliothek und Queue
- MediaStore: System scannt automatisch; `READ_MEDIA_AUDIO` ab API 33 (https://developer.android.com/training/data-storage/shared/media). Direkter Ordner-Scan vs. MediaStore: kein offizieller Vergleich, nicht belegt; Gramophone nutzt MediaStore, Auxio eigenen Index mit TagLib (Abschnitt 7).
- Playlists: `MediaStore.Audio.Playlists` seit API 31 deprecated, „M3U files can be used as an alternative" (https://developer.android.com/reference/android/provider/MediaStore.Audio.Playlists). M3U-Parser (nicht HLS) in Media3: nicht belegt → selbst parsen.
- Queue/Position: Media3 speichert nichts; App liefert sie in `onPlaybackResumption()` zurück (BG).

#### 6. Zweites Launcher-Icon
- Mechanik: `<activity-alias android:enabled="false" android:targetActivity=…>` mit MAIN/LAUNCHER (https://developer.android.com/guide/topics/manifest/activity-alias-element), umschalten per `setComponentEnabledSetting(…, DONT_KILL_APP)`; das Flag heißt laut Doku „you don't want to kill the app" (https://developer.android.com/reference/android/content/pm/PackageManager). Kill-Pfad ohne Flag im AOSP-Code nicht zeilengenau geprüft.
- Verzögerung: Der PACKAGE_CHANGED-Broadcast wird mit DONT_KILL_APP um 1 s verzögert, kurz nach Systemstart um 10 s (`BROADCAST_DELAY`, https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/PackageManagerService.java). Launcher-eigene Latenz: nicht belegt.
- Shortcuts: Dynamische Shortcuts, deren Activity kein Main-Activity mehr ist, werden deaktiviert (`DISABLED_REASON_APP_CHANGED`, …/pm/ShortcutPackage.java, gleicher AOSP-Pfad). Widgets, Samsung One UI, Pixel Launcher: **nicht belegt**.
- Ganz verstecken geht nicht: ab Android 10 erscheint sonst eine synthetische Activity (https://developer.android.com/reference/android/content/pm/LauncherApps#getActivityList). Haupt-Icon bleibt also an.
- Play-Richtlinie (Deceptive Behavior 2.1): „We don't allow apps that make changes to the user's device settings or features outside of the app without the user's knowledge and consent. Device settings and features include … shortcuts, icons, widgets, and the presentation of apps on the homescreen." 2.2.1 verlangt leichte Umkehrbarkeit (https://support.google.com/googleplay/android-developer/answer/9888077). Ein sichtbarer Schalter mit Aus-Weg erfüllt das.

#### 7. Referenz-Player (Auswahl ist mein Urteil; Zahlen GitHub-API 2026-11-16)
- **Auxio** – https://github.com/OxygenCobalt/Auxio, GPL-3.0, 4470 Sterne, v4.1.5 vom 2026-08-04. Kopieren: eigener Tag-Index mit TagLib statt MediaStore-Metadaten, ReplayGain für MP3/FLAC/Ogg/Opus/MP4 (README).
- **Gramophone** – https://github.com/FoedusProgramme/Gramophone, GPL-3.0, 2401 Sterne, 1.1.2 vom 2026-08-20. Kopieren: Media3 als Submodul, synchronisierte Liedtexte (LRC/TTML/SRT), ReplayGain 2.0 (readme).
- **Retro Music** – https://github.com/RetroMusicPlayer/RetroMusicPlayer, GPL-3.0, 5403 Sterne, v7.0.3 vom 2026-08-13. Kopieren (als Muster): Crossfade über zwei Player-Instanzen (`CrossFadePlayer.kt`, dort mit `android.media.MediaPlayer`).

#### 8. Aktuelle Spezifikationen
- ITU-R BS.1770-5 (11/2023, in Kraft) als Messverfahren (https://www.itu.int/rec/R-REC-BS.1770); EBU R 128-2023 (Nov. 2023), Ziel −23,0 LUFS, Streaming-Hinweise in R 128 s2 (https://tech.ebu.ch/docs/r/r128.pdf).
- CTA-2075 über `LoudnessCodecController` (Android 15), siehe 3.
- Barrierefreiheit: Tippflächen ≥ 48×48 dp, `contentDescription` für Icon-Tasten (https://developer.android.com/guide/topics/ui/accessibility/apps).
- ReplayGain-2.0-Spezifikation (hydrogenaudio) und AES77: Abruf scheiterte an Cloudflare-403 → **nicht belegt**. Perzeptuelles Audio: nichts Belastbares gefunden.

#### 9. Eingebaute Player-Oberfläche
- Die XML-Layouts wählen nach verfügbarer Fensterbreite und -höhe: kompakt als Spalte, ab 600 dp in einem hohen
  Fenster als auf 560 dp begrenzte Spalte und im kurzen bzw. ab 840 dp breiten Querformat mit Cover und Steuerung
  nebeneinander. So bleibt ein Tablet im Hochformat eine großzügige Spalte statt eines gestreckten Querformats.
- Play/Pause misst 64 dp, ab 600 dp 80 dp; Vor/Zurück messen 56 dp, Shuffle/Wiederholen 48 dp. Aktive
  Shuffle-/Wiederholmodi tragen Primärfarbe und einen 4-dp-Punkt. Das Cover hat 28-dp-Ecken.
- Für den Hintergrund wird das Cover auf 24 × 24 Pixel verkleinert, der Mittelwert der deckenden Pixel gebildet
  und mit höchstens 8 % in `colorSurface` gemischt; die Stärke sinkt automatisch, falls `colorOnSurface` sonst
  weniger als 4,5:1 Kontrast hätte. Der Wechsel dauert 300 ms, wenn Ordos Bewegungseinstellung und die
  Systemeinstellung Animationen erlauben. Diese Werte stammen aus dem Code; die Wirkung auf Geräten ist noch
  nicht neu gemessen.

#### Empfehlung für Ordo
Stack: Media3 1.11.1 (exoplayer, session, ui, inspector) + `MediaLibraryService`; Plattform-Decoder, dazu selbst gebaute FFmpeg-Erweiterung (LGPL-Konfiguration, `alac` + `flac` für API 24–26); TagLib für Tags/ReplayGain/Liedtexte; ReplayGain als eigener AudioProcessor; Crossfade erst später (Eigenbau).
1. `PlaybackService` mit Fokus, Noisy, mediaPlayback-FGS, `MediaButtonReceiver` + `onPlaybackResumption` (Queue persistieren); FGS-Erklärung samt Video in der Play Console.
2. Player-Oberfläche (XML) mit „Öffnen aus Dateiliste" und Schalter für `activity-alias` „Ordo Musik" (DONT_KILL_APP).
3. Bibliothek: MediaStore-Index + TagLib-Tags, M3U-Parser, dann ReplayGain und FFmpeg-Decoder.

Nicht geprüft: Verhalten auf echten Geräten (One UI, Pixel), Auto-Qualitätsrichtlinien im Detail, Lizenz von Kyant0/taglibs mitgelieferten Binaries. Risiko: GitHub-Suche war rate-limitiert, die Player-Auswahl stammt aus Einzelabfragen, nicht aus einem Sterne-Ranking.

## Anhang B – Video, PDF, Markdown, Office

### Ordo: Stack für „jede Datei öffnen" – Recherche-Brief (Stand 16.11.2026)
Methode: Versionen und Daten aus Google Maven, Maven Central und GitHub-API. Größen und 16-KB-Ausrichtung habe ich selbst gemessen: AAR geladen, Dateigrößen und ELF `PT_LOAD p_align` je ABI ausgelesen. Keine Codeänderung, Downloads wieder gelöscht.

#### 1 Video
- **Media3 stabil: 1.11.1** (11.09.2026); 1.12.0-beta01 vom 11.11.2026. https://dl.google.com/android/maven2/androidx/media3/media3-exoplayer/maven-metadata.xml · https://api.github.com/repos/androidx/media/releases
- **Decoder:** Standard sind die Plattform-Decoder, also geräteabhängig. Software-Decoder (AV1, VP9, FLAC, Opus, FFmpeg, MIDI, IAMF, MPEG-H) muss man selbst bauen. https://developer.android.com/media/media3/exoplayer/supported-formats
- **FFmpeg-Modul:** liefert nur `FfmpegAudioRenderer` und liegt nicht auf Google Maven. https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md
- **Container:** MP4, M4A, FMP4, WebM, Matroska, MP3, Ogg, WAV, MPEG-TS, MPEG-PS, FLV (nicht spulbar), ADTS, FLAC, AMR. AVI und WMV stehen nicht in der Tabelle. (gleiche Seite)
- **Untertitel:** WebVTT, TTML, SubRip und SSA/ASS lassen sich als Datei zuladen (gleiche Seite). Der ASS-Parser wertet bei Override-Tags aber nur `\an`, `\pos` und `\move` aus und entfernt den Rest; libass fehlt. https://github.com/androidx/media/blob/release/libraries/extractor/src/main/java/androidx/media3/extractor/text/ssa/SsaStyle.java
- **HDR / Dolby Vision:** Extraktion ja (Dolby Vision in MP4, HDR10+ in MKV/WebM); Decodierung und Anzeige hängen am Gerät. supported-formats (oben) · https://developer.android.com/media/grow/hdr-playback
- **Bild-in-Bild:** Plattform-API ab Android 8.0, Auto-Enter ab Android 12; kein Media3-Feature. https://developer.android.com/develop/ui/views/picture-in-picture
- **Tempo und Seek:** `Player.setPlaybackSpeed`; `SeekParameters.EXACT`; Scrubbing über `ExoPlayer.setScrubbingModeEnabled`. Einzelbild-Schritt: nicht belegt. https://developer.android.com/reference/androidx/media3/common/Player · https://developer.android.com/reference/androidx/media3/exoplayer/SeekParameters · https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer
- **Spurwahl:** Audio- und Textspuren über `TrackSelectionParameters` (bevorzugte Sprache, Overrides). https://developer.android.com/media/media3/exoplayer/track-selection

| Basis | Lizenz (LICENSE-Datei) | arm64 unkompr. / kompr. | 16 KB (64-Bit) | Letzter Stand |
|---|---|---|---|---|
| Media3 1.11.1 | Apache-2.0 https://raw.githubusercontent.com/androidx/media/HEAD/LICENSE | kein nativer Code im Standard | entfällt | 11.09.2026 |
| + nextlib-media3ext 1.11.1-0.18.0 | GPL-3.0 https://raw.githubusercontent.com/anilbeesetti/nextlib/HEAD/LICENSE | 10,6 / 4,7 MB | 0x4000, ok | 08.11.2026 https://api.github.com/repos/anilbeesetti/nextlib/releases |
| libmpv (`dev.jdtech.mpv:libmpv` 1.1.0) | mpv GPLv2+, LGPLv2.1+ nur mit `-Dgpl=false` https://raw.githubusercontent.com/mpv-player/mpv/HEAD/Copyright ; Wrapper MIT | 27,4 / 12,5 MB | ok | 15.11.2026 https://api.github.com/repos/jarnedemeulemeester/libmpv-android/releases |
| libVLC `libvlc-all` 3.7.7 | LGPLv2.1, App GPLv2+ https://raw.githubusercontent.com/videolan/vlc-android/HEAD/README.md | 55,3 / 24,1 MB | ok | 01.10.2026 https://repo1.maven.org/maven2/org/videolan/android/libvlc-all/maven-metadata.xml |

- **Weitere Stände:** mpv 0.42.0 vom 05.11.2026, mpv-android-App vom 17.09.2026. `videolan/vlc-android` hat keine GitHub-Releases; libVLC 4.0 ist noch EAP (4.0.0-eap29). https://api.github.com/repos/mpv-player/mpv/releases · https://api.github.com/repos/mpv-android/mpv-android/releases
- **nextlib-Umfang laut README:** Audio AC3, EAC3, DTS, TrueHD u. a.; Video H.264, HEVC, VP8, VP9, AV1 (dav1d); Untertitel ASS/SSA über libass, dazu PGS, VobSub, DVB. https://github.com/anilbeesetti/nextlib/blob/HEAD/README.md
- **GPL-3.0-Verträglichkeit:** LGPLv2.1 und Apache-2.0 sind mit GPLv3 kompatibel; GPLv2+ über „or later". https://www.gnu.org/licenses/license-list.en.html
- **Google Play:** VLC bezeichnet sich selbst als „de facto GPLv3 application" (README oben). Eine Play-Richtlinie dazu ist nicht belegt.
- **Befunde am libmpv-AAR:** Der FFmpeg-Configure-String enthält `--enable-gpl`, das AAR ist also nur für GPL-Apps geeignet (für Ordo in Ordnung). Der Compiler-Target heißt `android26`; das deutet auf API 26 und läge über Ordos minSdk 24. Nicht abschließend belegt.
- **Urteil:** Basis ist Media3 + nextlib. Gründe: dieselbe API wie der Audio-Player, kleinste Größe, Hardware-Decoding, und die häufigsten Ausfälle (AC3/DTS/TrueHD, HEVC/AV1 in Software, ASS, PGS) sind abgedeckt.
- **Grenze:** „Spielt wirklich alles" (AVI, WMV, MPEG-2, VC-1 über FFmpeg-Demuxer) können nur libmpv und libVLC. libmpv ist halb so groß wie libVLC und taugt später als Zweit-Engine für Dateien, die Media3 ablehnt.

#### 2 PDF
- **androidx.pdf 1.0.0-beta01** (26.08.2026); eine stabile Version gibt es nicht. https://dl.google.com/android/maven2/androidx/pdf/group-index.xml · https://developer.android.com/jetpack/androidx/releases/pdf
- **minSdk 28:** steht im AAR-Manifest (gemessen); laut Release Notes alpha18 sind Lesen und Rendern auf „minSdk = 28 and sdk-extension < 13" zurückportiert (Release-Seite oben).
- **Widerspruch:** Der Leitfaden nennt weiterhin API 31 + S-Extension 13, sonst `UnsupportedOperationException`. https://developer.android.com/develop/ui/views/layout/pdf/implement-pdf-viewer
- **Folge für Ordo (minSdk 24):** `tools:overrideLibrary` plus Laufzeit-Weiche; die vorhandene PdfRenderer-Activity bleibt Rückfall für API 24–27.
- **Lesen:** `PdfViewerFragment` mit Suche (seitenweise gestreamt), Textauswahl über Seiten, Links, Zwei-Seiten-Layout, Tastatur und Maus (Release-Seite).
- **Bearbeiten:** `EditablePdfViewerFragment` seit alpha13 mit Stift, Marker, Radierer, Undo/Redo, Formularfeldern und Speichern über `PdfWriteHandle`. In beta01 trägt es noch `@ExperimentalPdfApi` (Release-Seite).
- **OCR-Suche:** optional über `pdf-ocr-play-services` (Play Services ML Kit); laut POM keine Pflicht-Abhängigkeit. https://dl.google.com/android/maven2/androidx/pdf/pdf-viewer-fragment/1.0.0-beta01/pdf-viewer-fragment-1.0.0-beta01.pom
- **Plattform Android 15:** `PdfRenderer` kann Passwort-PDFs, Formular-Edit (`applyEdit`), `searchText`, `selectContent` und `write()`. Es ist ein Mainline-Modul, mit `PdfRendererPreV` zurück bis Android 11. https://developer.android.com/about/versions/15/features · https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page
- **Plattform-Annotationen:** `HighlightAnnotation`, `FreeTextAnnotation`, `StampAnnotation` erst ab API 36.1 / S-Extension 18. https://developer.android.com/reference/android/graphics/pdf/component/PdfAnnotation
- **Android 16:** Die Feature-Seite erwähnt PDF nicht. https://developer.android.com/about/versions/16/features

| Alternative | Lizenz | arm64 | 16 KB | Stand | Kann |
|---|---|---|---|---|---|
| PdfiumAndroidKt 2.0.3 | Apache-2.0 https://raw.githubusercontent.com/johngray1965/PdfiumAndroidKt/HEAD/LICENSE | 5,2 / 2,6 MB | ok | 26.07.2026 https://api.github.com/repos/johngray1965/PdfiumAndroidKt/releases | Rendern, Textseiten; Formulare und Annotationen nicht belegt |
| MuPDF `fitz` 1.28.5 | AGPL-3.0 https://raw.githubusercontent.com/ArtifexSoftware/mupdf/HEAD/COPYING | 10,8 / 5,5 MB | ok | 25.09.2026 https://maven.ghostscript.com/com/artifex/mupdf/fitz/maven-metadata.xml | `PDFAnnotation` und `PDFWidget` im Java-Binding https://github.com/ArtifexSoftware/mupdf/tree/HEAD/platform/java/src/com/artifex/mupdf/fitz |

- **AGPL und GPL-3.0:** Kombinieren ist erlaubt. Ordos Code bleibt GPL-3.0, für die Kombination gilt zusätzlich die Netzwerk-Klausel der AGPL. https://www.gnu.org/licenses/gpl-3.0.en.html#section13
- **Realistisch:** Lesen, Suchen und Auswählen gehen sofort mit androidx.pdf (ab API 28). Annotieren und Formulare gehen mit `EditablePdfViewerFragment`, aber experimentell. MuPDF ist die einzige belegte FOSS-Alternative mit Annotations- und Formular-API und kostet AGPL plus 5,5 MB je ABI.

#### 3 Markdown
| Bibliothek | Art | Abdeckung | Lizenz | Letzter Stand |
|---|---|---|---|---|
| Markwon 4.6.2 | View-nativ (Spannables), kein WebView | CommonMark, Tabellen, Task-Listen, LaTeX (`ext-latex`), Syntax-Highlight, `editor`-Modul; Fußnoten nicht belegt | Apache-2.0 | **08.02.2021**, letzter Push 17.04.2024, 102 offene Issues https://api.github.com/repos/noties/Markwon/releases · https://api.github.com/repos/noties/Markwon · https://repo1.maven.org/maven2/io/noties/markwon/ |
| commonmark-java 0.32.0 | nur Parser | GFM-Tabellen, Strikethrough, Task-Listen, Fußnoten, Alerts, Autolink; Android ab API 24 „best effort" | BSD-2 | 01.11.2026 https://api.github.com/repos/commonmark/commonmark-java/releases · https://github.com/commonmark/commonmark-java/blob/HEAD/README.md |
| JetBrains/markdown 0.7.18 | nur Parser | CommonMark- und GFM-Flavour | Apache-2.0 | 15.10.2026 https://repo1.maven.org/maven2/org/jetbrains/markdown/maven-metadata.xml |
| mikepenz multiplatform-markdown-renderer 0.46.0 | Compose (per `ComposeView` einbettbar) | „Full GFM": Tabellen, Task-Listen, Alerts; Highlighting über `-code`; Lazy Loading für große Dokumente; Mathe und Fußnoten nicht belegt | Apache-2.0 | 13.10.2026 https://repo1.maven.org/maven2/com/mikepenz/multiplatform-markdown-renderer/maven-metadata.xml · https://github.com/mikepenz/multiplatform-markdown-renderer/blob/HEAD/README.md |

- **Befund:** Einen gepflegten View-nativen Renderer habe ich nicht gefunden. Markwon ist der einzige mit vollem Umfang und seit 2021 ohne Release.
- **Empfehlung:** Leser als Compose-Insel mit dem mikepenz-Renderer (gepflegt, GFM, Highlighting). Schreiber mit Sora Editor (Abschnitt 4), TextMate-Markdown-Grammatik und Umschalter Vorschau/Quelltext.
- **Rückfall:** Wenn Compose nicht ins Projekt soll, Markwon 4.6.2 einfrieren. Es bringt als Einziges LaTeX und Editor-Highlighting mit, bleibt aber auf commonmark 0.13.0. https://repo1.maven.org/maven2/io/noties/markwon/core/4.6.2/core-4.6.2.pom

#### 4 Text- und Code-Editor
- **Sora Editor 0.24.7** (17.10.2026, auf GitHub als Pre-Release markiert), LGPL-2.1, mit GPLv3 kompatibel. Kern-AAR 0,8 MB. https://api.github.com/repos/Rosemoe/sora-editor/releases · https://raw.githubusercontent.com/Rosemoe/sora-editor/HEAD/LICENSE
- **Funktionen:** inkrementelles Highlighting, TextMate und TreeSitter, Suchen/Ersetzen, Undo, Zeilenumbruch. Das README sagt selbst „Work In Progress". https://github.com/Rosemoe/sora-editor/blob/HEAD/README.md
- **Voraussetzungen:** minSdk 21, JDK 17, LSP ab API 26, gegebenenfalls Desugaring. https://project-sora.github.io/sora-editor-docs/guide/getting-started
- **Große Dateien:** Die Doku sagt nur „High-Performance" mit inkrementeller Analyse im Hintergrund. Eine konkrete Dateigrößen-Grenze ist nicht belegt. https://project-sora.github.io/sora-editor-docs/guide/editor-overview
- **CodeView 1.3.9**, MIT, letzter Release 30.12.2023; faktisch stehen geblieben. https://api.github.com/repos/AmrDeveloper/CodeView/releases

#### 5 Office
- **LibreOffice Viewer:** Die Document Foundation nennt ihn selbst „viewer app … with experimental editing support" und verweist fürs Bearbeiten auf Collabora. https://www.libreoffice.org/download-other/
- **Aufbau:** Der gesamte LibreOffice-Kern steckt in einer `liblo-native-code.so`; die Oberfläche basiert auf Fennec mit OpenGL-Kacheln. Lizenz MPL-2.0/GPLv3. https://github.com/LibreOffice/core/blob/HEAD/android/README.md
- **Größe:** Das APK hat 80–95 MB je ABI (F-Droid 26.2.7.3, 09.11.2026). https://f-droid.org/api/v1/packages/org.documentfoundation.libreoffice · https://f-droid.org/repo/org.documentfoundation.libreoffice_135.apk
- **Als Bibliothek:** Ein einbindbares AAR ist nicht belegt.
- **Collabora:** Das GitHub-Repo ist „Issue tracker only. Active development is on Gerrit". Android-Kern, Größe und Einbettbarkeit sind auf GitHub nicht belegt. https://api.github.com/repos/CollaboraOnline/online
- **Apache POI 6.0.0** (22.10.2026, Apache-2.0): ein reines Datenmodell ohne Layout-Engine. Folien-Rendering braucht `java.awt.Graphics2D`; diese Klasse hat in der Android-Referenz keine Seite (404). https://repo1.maven.org/maven2/org/apache/poi/poi-ooxml/maven-metadata.xml · https://poi.apache.org/components/slideshow/xslf-cookbook.html · https://developer.android.com/reference/java/awt/Graphics2D
- **POI auf Android:** Die drei geprüften POI-Seiten (FAQ, Developer-Index, Components-Index) erwähnen Android nicht; Unterstützung ist nicht belegt. Die Jars sind groß: poi 3,1 + poi-ooxml 2,1 + poi-ooxml-full 14,3 + xmlbeans 2,2 MB (Maven Central, Content-Length).
- **docx4j 17.3.6** (08.11.2026, Apache-2.0): Das README erwähnt Android nicht; nicht belegt. https://repo1.maven.org/maven2/org/docx4j/docx4j-core/maven-metadata.xml · https://github.com/plutext/docx4j/blob/HEAD/README.md
- **Klartext:** Bearbeiten in Produktionsqualität ist on-device mit FOSS nicht realistisch. Layouttreues Ansehen geht nur mit LibreOfficeKit, kostet 80–95 MB und eine eigene Native-Build-Pipeline, und selbst der Hersteller nennt das Bearbeiten experimentell.
- **Für Ordo realistisch:** Office-Dateien per Intent an eine installierte Office-App übergeben. Höchstens später eine einfache Inhaltsvorschau (DOCX-Text, XLSX-Raster) ohne Layouttreue.

#### 6 Empfehlung je Bereich
- **Video:** Media3 1.11.1 + nextlib-media3ext, libmpv später als optionale Zweit-Engine. Risiko: nextlib ist ein Ein-Personen-Projekt und an die Media3-Version gekoppelt.
- **PDF:** androidx.pdf 1.0.0-beta01 ab API 28, darunter die bestehende PdfRenderer-Activity; Annotationen und Formulare über `EditablePdfViewerFragment`. Risiko: Beta und experimentelle API, Doku widerspricht sich bei den Mindestanforderungen, also erst am Gerät prüfen.
- **Markdown:** mikepenz-Renderer in einer `ComposeView`, dazu Sora Editor zum Schreiben. Risiko: Compose zieht in eine View-App ein (Größe und Build habe ich nicht gemessen), Mathe fehlt.
- **Office:** nur Intent-Weitergabe, keine eingebaute Engine. Risiko: Die Erwartung „alles in der App" wird hier nicht erfüllt, und das sollte offen so kommuniziert werden.
- **Reihenfolge nach dem Audio-Player:** 1. Video (gleicher Media3-Stack). 2. PDF. 3. Code-Editor mit Sora, direkt danach Markdown (nutzt den Editor). 4. Office zuletzt, nur als Weitergabe.

Nicht geprüft: Verhalten auf echten Geräten (HDR, Dolby Vision, androidx.pdf auf API 28–30), APK-Zuwachs durch Compose, eine Play-Richtlinie zu GPL. Die GitHub-Suche nach weiteren Markdown-Bibliotheken war teils rate-limitiert, die Marktübersicht dort ist also nicht vollständig.

## Anhang C – Bildbetrachter und Dateitypen

**Ordo: Bildbetrachter + Dateitypen – Recherche-Brief (geprüft 2026-11-16, nur Primärquellen, keine Dateien geändert)**
Kürzel: DA=https://developer.android.com · MC=https://repo1.maven.org/maven2 · API=https://api.github.com/repos · RAW=https://raw.githubusercontent.com
**A1 Formate**
- Plattform-Decoder laut Formattabelle (Stand 2026-10-07): JPEG, PNG, GIF, BMP immer; WebP ab Android 4.0 (verlustfrei/Transparenz ab 4.2.1); HEIF ab Android 9 (geräteabhängig, `ImageDecoder.isMimeTypeSupported("image/heif")`); AVIF Baseline ab Android 12, 10 Bit optional (DA/media/platform/supported-formats).
- Animation: `ImageDecoder` (API 28) liefert für animiertes GIF/WebP ein `AnimatedImageDrawable` (DA/reference/android/graphics/ImageDecoder); HEIF-Sequenzen ab Android 11 (DA/about/versions/11/features). Animiertes AVIF: CDD 16 nennt „Image sequence" (https://source.android.com/docs/compatibility/16/android-16-cdd §5.1.6); API-Level in der Entwicklerdoku nicht belegt.
- Ultra HDR (JPEG + Gain-Map) ab Android 14/API 34, abwärtskompatibel: ältere Geräte zeigen SDR (DA/media/grow/ultra-hdr). Android 16 ergänzt Ultra HDR in HEIC, AVIF „in Arbeit" (DA/about/versions/16/features).
- RAW: CDD 16 verlangt Decoding von ARW, CR2, DNG, NEF, NRW, ORF, PEF, RAF, RW2, SRW (§5.1.5/5.1.6, URL wie oben). JPEG XL steht weder in der Formattabelle noch im CDD 16 → Plattform-Decoder nicht belegt.
- minSdk 24 muss selbst mitbringen: HEIF für API 24–27 und AVIF für API 24–30 (avif-coder, MIT, API 24+, 3.0.2 vom 2026-11-15: RAW/awxkee/avif-coder/master/README.md, MC/io/github/awxkee/avif-coder/); JPEG XL für alle (jxl-coder, Apache-2.0, API 21+, stabil 2.6.1 vom 2026-05-10: RAW/awxkee/jxl-coder/master/README.md, MC/io/github/awxkee/jxl-coder/). Unter API 28 bleibt bei Coil nur `GifDecoder`, `ImageDecoderDecoder` braucht API 28 (RAW/coil-kt/coil/2.x/coil-gif/README.md); animiertes WebP unter API 28: nicht belegt.
**A2 Ultra HDR und Farbraum**
- Richtig anzeigen: `Bitmap.hasGainmap()` (API 34) prüfen, dann `window.colorMode = ActivityInfo.COLOR_MODE_HDR`, sonst `COLOR_MODE_DEFAULT`; dynamisch pro Bild, nicht im Manifest; Screenshots bleiben SDR (DA/media/grow/ultra-hdr/display).
- `Window.setColorMode` ab API 26, wirkt nicht auf SurfaceView; `Window.setDesiredHdrHeadroom` ab API 35 begrenzt den HDR-Spielraum, gedacht für Übergänge und Vorschaubilder (DA/reference/android/view/Window). `Gainmap` ab API 34, Richtungs-Konstanten ab API 36 (DA/reference/android/graphics/Gainmap). Wide Color: `colorMode="wideColorGamut"` bzw. `setColorMode` (DA/training/wide-color-gamut).
- Was schiefgeht: (1) Ohne Farbmodus-Wechsel bleibt das Fenster SDR (Quelle wie oben). (2) Drehen/Zuschneiden/Skalieren über Bitmap-APIs erhält die Gain-Map; Filter und Overlays lassen sie unverändert und damit falsch (DA/media/grow/ultra-hdr/edit). (3) Coil: Hardware-Decode verliert auf Android 14 mit OpenGL und A8-Gain-Map die Gain-Map stillschweigend; Issue seit 2024-01-31 offen, PRs #3577 und #3612 ungemergt geschlossen (https://github.com/coil-kt/coil/issues/2094, API/coil-kt/coil/pulls/3612).
- Eigener Gain-Map-Code in Coil ist nicht belegt: CHANGELOG ohne Eintrag zu Gain-Map/HDR (RAW/coil-kt/coil/main/CHANGELOG.md). Coil 2.7.0 ist vom 2024-07-17 (MC/io/coil-kt/coil/); aktuell ist 3.6.3 vom 2026-09-18 (API/coil-kt/coil/releases/latest), minSdk 23 seit 3.5.0 (CHANGELOG). Folgerung, ungetestet: Vollbild auf API 34 als Software-Bitmap dekodieren.
**A3 Sehr große Bilder**
- `BitmapRegionDecoder` (API 10) kann nur JPEG, PNG, WebP, HEIF, AVIF (DA/reference/android/graphics/BitmapRegionDecoder).
- subsampling-scale-image-view: Apache-2.0, 8008 Sterne, letzter Release 3.10.0 vom 2020-01-05, letzter Push 2024-04-22, also ungepflegt (API/davemorrissey/subsampling-scale-image-view, MC/com/davemorrissey/labs/subsampling-scale-image-view-androidx/). Fossify nutzt einen eigenen Fork nur per Commit-Hash, ohne Versionsrelease (RAW/FossifyOrg/Gallery/main/gradle/libs.versions.toml).
- ZoomImage (panpf): Apache-2.0, 659 Sterne, 1.7.0 vom 2026-09-28, API 21+, für Views und Compose, Subsampling mit Kachel-Animation, EXIF-Drehung, Lesemodus für lange Bilder, Modul `zoomimage-view-coil2` (RAW/panpf/zoomimage/main/README.md, API/panpf/zoomimage/releases/latest). Subsampling nur für Typen, die `BitmapRegionDecoder` kann (RAW/panpf/zoomimage/main/docs/subsampling.md).
- Telephoto: Apache-2.0, 0.19.0 vom 2026-04-02 (API/saket/telephoto/releases/latest, API/saket/telephoto/license); Compose-Bindung hier nicht nachgeprüft.
- Beste Wahl für Ordo: `zoomimage-view-coil2` (Views, bleibt bei Coil 2.7). Gain-Map-Erhalt in Kacheln: nicht belegt, am Gerät messen.
**A4 Verlustfrei bearbeiten**
- Plattform: `ExifInterface.rotate(int)`, `flipHorizontally()`, `flipVertically()`, `resetOrientation()` plus `saveAttributes()`; Schreiben nur für JPEG, PNG, WebP; Lesen auch HEIC (API 28+), AVIF (API 31+), DNG u. a. (DA/reference/androidx/exifinterface/media/ExifInterface). Dass `rotate` nur das Orientierungs-Tag setzt, steht dort nicht wörtlich. Version: stabil 1.4.2 (2025-12-03), 1.5.0-beta01 (2026-11-04) (DA/jetpack/androidx/releases/exifinterface).
- Echte verlustfreie JPEG-Transformation: libjpeg-turbo `tj3Transform` mit `TJXOP_ROT90` usw., `TJXOPT_CROP`, `TJXOPT_PERFECT`; BSD-artige Lizenzen; 3.2.1 vom 2026-10-27; braucht NDK-Einbindung (RAW/libjpeg-turbo/libjpeg-turbo/main/src/turbojpeg.h, RAW/libjpeg-turbo/libjpeg-turbo/main/LICENSE.md, API/libjpeg-turbo/libjpeg-turbo/releases/latest). Plattform-API dafür und gepflegter Android-Wrapper: nicht belegt.
- Zuschneide-Oberflächen: Android-Image-Cropper 4.7.0 vom 2025-11-28, Apache-2.0 (API/CanHub/Android-Image-Cropper/releases/latest, RAW/CanHub/Android-Image-Cropper/main/LICENSE.txt); uCrop 2.2.11 vom 2025-08-07, Apache-2.0 laut README, kodiert neu (Format/Qualität wählbar), kopiert EXIF, kein WebP-Ausgang (API/Yalantis/uCrop/releases/latest, RAW/Yalantis/uCrop/develop/README.md).
- Zurückschreiben: MediaStore-Eintrag mit `IS_PENDING=1`, schreiben über `openFileDescriptor(uri, "w")`, dann `IS_PENDING=0` (DA/training/data-storage/shared/media). `saveAttributes()` kopiert die ganze Datei und benennt um, ist also teuer (ExifInterface-Referenz oben).
**A5 Was gute Betrachter tun**
- Google Fotos / Samsung Galerie: herstellerdokumentiertes Viewer-Verhalten nicht belegt; belegt ist nur, dass Samsungs „Super HDR" das Ultra-HDR-Format nutzt (DA/media/grow/ultra-hdr).
- Fossify Gallery (GPL-3.0, 3923 Sterne, 1.14.2 vom 2026-11-07; API/FossifyOrg/Gallery): bringt fehlende Decoder selbst mit (jxl-coder-glide, APNG/animiertes WebP/AVIF, AndroidSVG) plus Subsampling-Fork. Übernehmen: Format-Lücken per Decoder-Plugin schließen (libs.versions.toml wie oben).
- Aves (BSD-3, 5527 Sterne, v1.15.10 vom 2026-11-10, Flutter; API/deckerst/aves): erkennt beim Scannen Motion Photos, Panoramen, 360°-Videos, GeoTIFF, mehrseitige TIFFs. Übernehmen: Sondertypen erkennen und passend darstellen (RAW/deckerst/aves/main/README.md).
- ReFra, vormals IacobIonut01/Gallery (Apache-2.0, 3023 Sterne, 5.2.2 vom 2026-10-04; API/IacobIonut01/ReFra): Viewer auf ZoomImage + Sketch, dazu jxl-coder und avif-coder-coil. Übernehmen: ZoomImage als Zoom-Basis (RAW/IacobIonut01/ReFra/main/gradle/libs.versions.toml).
**B6 Dateitypen** (MIME laut https://www.iana.org/assignments/media-types/; „n. r." = dort nicht registriert)
| Gruppe (Endungen; MIME) | Anzeigen / Bearbeiten | Bibliothek, Lizenz, letzter Release | 50 MB ohne Vollladen |
|---|---|---|---|
| JSON, JSON5, NDJSON (.json .json5 .ndjson .jsonl; application/json, JSON5/NDJSON n. r.) | Baum mit Einklappen in RecyclerView (Eigenbau; gepflegte View-Bibliothek nicht belegt), Parserfehler als Validierung, Text im Editor | Gson `JsonReader` 2.15.0, Apache-2.0, 2026-10-24, min. API 24 (MC/com/google/code/gson/gson/, RAW/google/gson/main/README.md). Jackson 2.23.0 (2026-11-08) kann Kommentare, einfache Anführungszeichen, Schlusskomma, braucht aber Android SDK 26+ (RAW/FasterXML/jackson-core/2.x/README.md, RAW/FasterXML/jackson-core/2.x/src/main/java/com/fasterxml/jackson/core/json/JsonReadFeature.java) → JSON5 für minSdk 24 nicht belegt | Streaming ja; Baum nur mit eigenem Offset-Index |
| YAML (.yaml .yml; application/yaml) | derselbe Baum + Editor | snakeyaml-engine 3.2, Apache-2.0, 2026-10-03 (MC/org/snakeyaml/snakeyaml-engine/, https://bitbucket.org/snakeyaml/snakeyaml-engine/raw/master/LICENSE.txt) | Nein: Standardlimit 3 MB, siehe B7 |
| TOML (.toml; application/toml) | derselbe Baum + Editor | tomlj 2.2.0, Apache-2.0, 2026-09-30 (MC/org/tomlj/tomlj/, RAW/tomlj/tomlj/main/LICENSE); ktoml 0.7.1, MIT, 2025-08-22 (MC/com/akuleshov7/ktoml-core/, RAW/orchestr7/ktoml/main/LICENSE) | nicht belegt |
| XML, HTML (.xml .html .htm; application/xml, text/xml, text/html) | XML: Baum + Editor; HTML: Vorschau in WebView, Quelltext im Editor | Plattform `XmlPullParser` (DA/reference/org/xmlpull/v1/XmlPullParser); jsoup 1.24.1, MIT, 2026-11-03 (MC/org/jsoup/jsoup/, RAW/jhy/jsoup/master/LICENSE) | Pull-Parser ja; jsoup nicht belegt |
| CSV, TSV (.csv .tsv; text/csv, text/tab-separated-values) | Tabelle mit fixierter Kopfzeile in RecyclerView (Eigenbau) | Apache Commons CSV 1.14.1, Apache-2.0, 2025-07-27, parst satzweise (MC/org/apache/commons/commons-csv/, RAW/apache/commons-csv/master/src/main/java/org/apache/commons/csv/CSVParser.java). FastCSV 4.4.1 scheidet aus: Java 17+/Android 34+ (RAW/osiegmar/FastCSV/main/README.md). TableView (MIT) zuletzt v0.8.9.4 vom 2021-03-18 (API/evrencoskun/TableView/releases/latest) | Ja, satzweise; Zeilenindex selbst bauen |
| Logs (.log .txt; text/plain) | seitenweise Liste mit Live-Suche über Zeilen-Offsets (Eigenbau) | Bibliothek nicht belegt | nur per Eigenbau |
| Quellcode (.kt .java .py .js .sh usw.) | Sora Editor: inkrementelles Highlighting, Suchen/Ersetzen, Undo, TextMate und tree-sitter | Sora Editor 0.24.7, LGPL-2.1, 2026-10-17, auf GitHub als Pre-Release markiert, API 21+ (MC/io/github/rosemoe/editor/, API/Rosemoe/sora-editor/releases, RAW/Rosemoe/sora-editor/main/README.md, https://project-sora.github.io/sora-editor-docs/guide/getting-started); LGPL-2.1 ist GPLv3-kompatibel (https://www.gnu.org/licenses/license-list.html#LGPLv2.1). android-tree-sitter 4.3.3, LGPL-2.1, 2026-10-14 (MC/com/itsaky/androidide/treesitter/android-tree-sitter/); ktreesitter 0.25.1, MIT, 2026-06-30, mit Android-Artefakt (MC/io/github/tree-sitter/) | nicht belegt |
| Untertitel (.srt .vtt .ass .ssa; text/vtt, SRT/ASS n. r.) | Liste Zeit + Text, Bearbeiten im Editor | Media3 `SubripParser`, `WebvttParser`, `SsaParser`, media3-extractor 1.11.1, Apache-2.0, 2026-09-11 (DA/reference/androidx/media3/extractor/text/subrip/SubripParser, https://dl.google.com/android/maven2/androidx/media3/group-index.xml, RAW/androidx/media/release/LICENSE) | Annahme: unnötig, kleine Dateien |
| E-Books (.epub; application/epub+zip) | Readium-Navigator, nur lesen | Readium Kotlin Toolkit 3.4.0, BSD-3, 2026-09-11, minSdk 24; EPUB 2/3 und PDF ja, CBZ in Arbeit, MOBI/FB2 nicht aufgeführt (RAW/readium/kotlin-toolkit/main/README.md, API/readium/kotlin-toolkit/releases/latest) | nicht belegt |
| vCard, iCalendar (.vcf .ics; text/vcard, text/calendar) | Kontakt-/Terminkarte, Rohtext im Editor | ez-vcard 0.12.2, BSD-artig, 2026-02-15, Android-kompatibel (MC/com/googlecode/ez-vcard/ez-vcard/, RAW/mangstadt/ez-vcard/master/README.md); biweekly 0.6.8, BSD-artig, 2024-01-07, Android-kompatibel (MC/net/sf/biweekly/biweekly/, RAW/mangstadt/biweekly/master/README.md) | nicht belegt |
| SQLite (.db .sqlite .sqlite3; application/vnd.sqlite3) | Tabellenliste + dieselbe Tabellenansicht, nur lesend | Plattform `SQLiteDatabase` (DA/reference/android/database/sqlite/SQLiteDatabase) | Annahme: ja, seitenweise Abfragen |
| Schriften (.ttf .otf; font/ttf, font/otf) | Vorschau mit Mustertext in mehreren Größen | Plattform `Typeface.Builder` ab API 26 (DA/reference/android/graphics/Typeface.Builder); Weg für API 24–25 nicht geprüft | entfällt |
**B7 Sicher parsen**
- JSON: Gson `JsonReader` begrenzt die Verschachtelung auf 255 (`setNestingLimit`) (RAW/google/gson/main/gson/src/main/java/com/google/gson/stream/JsonReader.java). Jackson `StreamReadConstraints` Standard: Tiefe 1000, String 20 000 000, Zahl 1000, Name 50 000 Zeichen, Dokumentlänge unbegrenzt (RAW/FasterXML/jackson-core/2.x/src/main/java/com/fasterxml/jackson/core/StreamReadConstraints.java).
- YAML (Billion Laughs): SnakeYAML 2.7 (2026-08-30, MC/org/yaml/snakeyaml/) `LoaderOptions`: `maxAliasesForCollections=50`, `allowRecursiveKeys=false`, `nestingDepthLimit=50`, `codePointLimit=3 MB`, `UnTrustedTagInspector` (https://bitbucket.org/snakeyaml/snakeyaml/raw/master/src/main/java/org/yaml/snakeyaml/LoaderOptions.java). snakeyaml-engine 3.2: Alias-Limit 50, 3 MB, keine rekursiven oder doppelten Schlüssel, keine ENV-Ersetzung (https://bitbucket.org/snakeyaml/snakeyaml-engine/raw/master/src/main/java/org/snakeyaml/engine/v2/api/LoadSettingsBuilder.java). Standardwerte beibehalten.
- XML (XXE): DTDs abschalten mit `XmlPullParserFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)`; schützt laut Android-Doku auch gegen Entity-DoS (DA/privacy-and-security/risks/xml-external-entities-injection).
**B8 Empfehlung**
1. Zwei gemeinsame Bausteine statt einem pro Typ: Sora Editor als einziger Text-/Code-Editor und ein Strukturbetrachter (Baum für JSON/YAML/TOML/XML, Tabelle für CSV/TSV/SQLite) mit Umschalter Ansicht ↔ Text. EPUB und Schriften bleiben eigene Betrachter.
2. Highlighting über tree-sitter, nicht TextMate: das TextMate-Modul enthält tm4e unter EPL-2.0 ohne Secondary-License-Vermerk, laut FSF nur mit diesem Vermerk GPL-kompatibel (RAW/Rosemoe/sora-editor/main/language-textmate/src/main/java/org/eclipse/tm4e/core/registry/Registry.java, https://www.gnu.org/licenses/license-list.html#EPL2); außerdem verlangt es unter API 33 Core-Library-Desugaring (Sora-Doku oben).
3. Reihenfolge: (1) Bildbetrachter mit ZoomImage, HDR-Farbmodus, JXL/AVIF-Decodern, EXIF-Drehung → (2) Sora Editor mit tree-sitter → (3) JSON-Baum → (4) CSV/SQLite-Tabelle → (5) YAML/TOML/XML im selben Baum → (6) Untertitel, vCard/iCal, Schriften → (7) EPUB mit Readium.
4. Risiko 1, APK-Größe: native Decoder (jxl-coder, avif-coder), tree-sitter-Grammatiken je ABI und Readium. Größen sind hier nicht gemessen; pro Baustein den APK-Diff messen, bevor er bleibt.
5. Risiko 2, Speicher: Editor und Baum nur bis zu einer festen Größenschwelle, darüber nur lesender Streaming-Modus (JsonReader, CSV satzweise, Zeilen-Offsets). YAML scheitert ohnehin am 3-MB-Limit; Soras Verhalten bei großen Dateien ist nicht belegt.
6. Die Sicherheits-Standardwerte aus B7 fest verdrahten, nicht einstellbar machen.
Nicht geprüft: Gain-Map-Verhalten von `BitmapRegionDecoder`/ZoomImage, welche Coil-Hauptversion `jxl-coder-coil` 2.6.1 und `avif-coder-coil` 3.0.0 voraussetzen, Jackson/FastCSV mit Desugaring, kotlinx.serialization-Streaming, tatsächliche APK-Größen.
Risiko: Die Lizenzfrage EPL-2.0 ↔ GPL-3.0 stützt sich auf Dateikopf und FSF-Liste, nicht auf eine Rechtsprüfung; GitHub-API lief unauthentifiziert, Sternzahlen sind Momentaufnahmen.
