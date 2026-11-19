# Ordos Video-Player – Phase 1

Stand: 19.11.2026. Dieser Stand beschreibt den Code, nicht einen Gerätetest. Plan und Quellen:
`ordo-team/VIDEO-PLAN.md` (Abschnitte 3 und 4, Phase 1).

## Öffnen

Ein Video aus der Dateiliste (`MimeType.isVideo`) öffnet `viewer/video/VideoPlayerActivity`. Die Warteschlange sind
die Videos desselben Ordners in der sichtbaren Reihenfolge, ab dem angetippten; bei mehr als 1000 ein Fenster um ihn
(`audioQueue`, wie beim Musik-Player). Sie reist nicht im Intent: `AudioQueueRequests` hält sie im Prozess, der Intent
trägt nur ein Token (dieselbe Klasse wie bei der Musik, nicht verallgemeinert: kleinerer Diff). Das Token bleibt
gültig, bis der Player sich schließt, damit eine neu aufgebaute Activity es wiederfindet; nach Prozessende bleibt nur
das Video aus dem Instanzzustand. Verknüpfungen (`OpenFileActivity`) öffnen eine Warteschlange mit diesem einen Video.
Videos in Archiven gehen wie bisher über `FileJobService.open` an andere Apps; „Öffnen mit“ bietet weiter alle Apps an.

Die Activity ist für `video/*` exportiert. Von anderen Apps spielt sie nur eine `content://`-URI mit Lese-Grant, nie
eine von Ordos eigenen Providern; `file://` und Pfad-Extras werden abgelehnt (Hinweis und Schließen).

## Wiedergabe

Die Activity besitzt ihren `ExoPlayer` (kein Dienst in Phase 1) und eine `MediaSession` mit der ID `video.<UUID>` (die
Musik-Session im selben Prozess hat die Standard-ID; zwei Player-Bildschirme können sich beim Wechsel überschneiden):
Medientasten und `dumpsys media_session`. Controller dürfen spielen und spulen, aber keine eigenen Einträge setzen
(`onAddMediaItems` schlägt fehl), sonst ginge die Startregel unten verloren. Audiofokus übernimmt
ExoPlayer (`setAudioAttributes(…, true)`, Inhaltstyp Film), abgezogene Kopfhörer pausieren
(`setHandleAudioBecomingNoisy`), `WAKE_MODE_NETWORK` wie bei der Musik. Gelesen wird über `PathDataSource`: lokaler
Speicher, SD-Karte, SMB, SFTP, WebDAV, FTP; `content://` über Media3. `onStop` sichert die Position und pausiert;
Hintergrund-Ton kommt in Phase 3.

`PlayerView` mit `surface_view`, ohne Media3-Steuerung (`use_controller="false"`): Media3s `DefaultTimeBar` springt
schon beim Antippen und bricht die Hausregel. Drehen und Größenänderung bauen die Activity nicht neu
(`configChanges`). Auf dem Handy (kleinste Breite unter 600 dp) folgt die Ausrichtung dem Video (`sensorLandscape`
für breite, `sensorPortrait` für hohe Filme), bis der Knopf „Bildschirm drehen“ sie umstellt; auf Tablets gibt es den
Knopf nicht.

## Bedienung

- Oben Zurück, Dateiname, ⋮ mit „Mit anderer App öffnen“ (Auswahl ohne Ordos Player) und „Dateiinfo“ (nur für Ordos
  Pfade). Mitte −10 s · Abspielen/Pause · +10 s. Unten Zeit (Tippen wechselt zur Restzeit) · `PlayerSeekSlider`
  (erst 150 ms still halten greift) · Dauer, darunter Tempo, Bildformat, Sperre, Drehen (Handy), vorheriges und
  nächstes Video.
- Größen kommen aus den Musik-Player-Maßen (`audio_player_*`), die auf Tablets schon wachsen (Play 88 dp, sonst
  56–64 dp); jede Taste hat mindestens 48 × 48 dp. Eine eigene `layout-sw600dp`-Datei braucht es dafür nicht.
- Die Leiste blendet nach 4 s aus, solange das Video läuft; pausiert, nach einem Fehler oder mit TalkBack bleibt sie.
  Mit ihr kommen und gehen die Systemleisten (immersiv, `BEHAVIOR_DEFAULT`: ab Android 12 geht die Zurück-Geste bei
  versteckten Leisten beim ersten Wisch durch; das frühere `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` brauchte zwei,
  gemessen auf dem Pixel 11 Pro XL, Android 16, 19.11.2026).
- Tippen aufs Bild zeigt oder verbirgt die Leiste (nach 300 ms, dem Doppeltipp-Fenster). Doppeltipp im linken 28-%-
  Streifen springt 10 s zurück, im rechten 10 s vor, jeder weitere schnelle Tipp 10 s mehr (Anzeige „+20 s“); in der
  Mitte Abspielen/Pause (mpv-android-Zonen). Rechts-nach-links spiegelt die Seiten wie den Regler.
- Tempo im Blatt mit Chips 0,5 · 0,75 · 1 · 1,25 · 1,5 · 2, Tonhöhe bleibt. Bildformat zyklisch Einpassen → Füllen →
  Strecken, kurz eingeblendet und als Zustand des Knopfs angesagt.
- Sperre: alles verschwindet bis auf „Zum Entsperren halten“; 600 ms halten entsperrt, TalkBack entsperrt per
  Doppeltipp (Aktion „Entsperren“). Gesperrt springt kein Tipp.
- Fehler: ruhige Meldung mit Grund (Format, beschädigt, nicht lesbar, sonst allgemein, nach Media3-Fehlercode) und
  „Mit anderer App öffnen“; die mittleren Tasten (−10 s, Abspielen, +10 s) treten solange zur Seite, damit sie den
  Knopf nie verdecken. Weiter/Zurück versucht das nächste Video.
- Titel ist der Dateiname ohne Endung, wie in der Sammlung.
- **Am Ende** (Wunsch des Besitzers, 19.11.2026): standardmäßig geht es nicht automatisch weiter. Das Bild bleibt auf
  dem letzten Bild stehen (`pauseAtEndOfMediaItems`), mittig „Nochmal ansehen“ und, wenn es eines gibt, „Nächstes
  Video“; die mittleren Tasten treten zur Seite. Zurück/Weiter in der Leiste wirken weiter von Hand.
- **Wiederholen-Taste** (`VideoRepeatMode`, getestet in `VideoFormatsTest`): Am Ende anhalten (`repeat`, gedimmt) →
  Dieses Video wiederholen (`repeat_one`) → Automatisch weiter (`playlist_play`, spielt den Ordner durch) → von vorn.
  Für alle Videos gemerkt (SharedPreferences `video_settings`), Zustand für TalkBack, kurzer Hinweis mit dem Namen. Beim
  Wiederholen eines Videos wird seine Stelle nie als „fertig“ gelöscht, auch wenn es am Ende vorbeiläuft.
- **Formate, die das Gerät nicht kann:** Kann ein Decoder nicht starten, meldet Media3 oft nur „Unexpected runtime
  error“ (W90, Unisoc, Android 16, HEVC 10-Bit, 19.11.2026). Darum liest die Meldung die Ursachenkette
  (`DecoderInitializationException`, `MediaCodecDecoderException`, `MediaCodec.CodecException`) und die Videospur
  (MIME-Typ, RFC-6381-Codecs, Bittiefe) und sagt genau „Dieses Gerät kann dieses Videoformat (HEVC 10-Bit) nicht
  abspielen.“, mit „Mit anderer App öffnen“.
- **Tonspur ohne Decoder** (AC3 auf dem W90 lief stumm): `onTracksChanged` sieht eine gewählte oder einzige
  Tonspur, die das Gerät nicht dekodieren kann; dann steht oben 6 s lang ruhig „Tonspur Dolby Digital (AC3) wird auf
  diesem Gerät nicht unterstützt.“ (AC3, E-AC3, DTS, TrueHD, AC-4 mit Namen). Gibt es eine abspielbare andere
  Tonspur, wird sie gewählt und der Hinweis sagt das. Das Video läuft weiter.
- **Wischgesten** (Phase 4 vorgezogen, `VideoGestures`, getestet in `VideoGesturesTest`): Ein Wisch beginnt erst nach
  2× Touch-Slop und nur entlang einer klaren Achse (|dy| > 2·|dx| bzw. umgekehrt). Senkrecht links: Helligkeit nur
  dieses Fensters (`screenBrightness`, keine Berechtigung, mindestens 1 %, zurück auf die des Systems in `onStop`);
  senkrecht rechts: Medienlautstärke über `setStreamVolume` ohne System-Regler (nicht bei fester Lautstärke); 60 % der
  Höhe entsprechen 0–100 %, Haptik bei 0 und 100 %. Waagrecht: Vorschau „12:40 · +0:30“, volle Breite = 2:30
  (mpv-android), gekappt auf die Videolänge, gesprungen wird erst beim Loslassen; zurück nahe dem Start bricht ab.
  400 ms still halten: 2× bis zum Loslassen, dann das vorige Tempo. Jeder Wert steht in der Hinweis-Pille. Wie die
  Doppeltipps beginnen sie nie in den System-Gestenzonen oder dem oberen 5-%-Streifen; gesperrt und mit TalkBack gibt
  es keine. Einzeln abschalten (Plan P4 Ziel 9) gibt es noch nicht.

**Gesten und Systemnavigation:** keine `setSystemGestureExclusionRects`. `VideoControls.isInGestureArea` verwirft jede
Berührung, die in `systemGestures`/`mandatorySystemGestures` links, rechts oder unten oder oberhalb von
max(Statusleiste, 5 % der Höhe) beginnt: Sie zählt nie als Doppeltipp, ein Tippen dort blendet nur die Leiste ein oder
aus. Die untere Leiste sitzt über dem `navigationBars`-Inset plus 8 dp.

**TalkBack:** Jede Taste hat eine Beschreibung, Abspielen/Pause wechselt sie, Tempo, Bildformat und Sperre haben einen
Zustand, der Regler sagt „x von y“. Die Bildfläche hat die Aktionen „10 Sekunden zurück/vor“ und „Steuerung zeigen“.

**Bewegung:** Ein-/Ausblenden und Hinweise nur bei `Motion.any`, sonst sofort.

## Weiter, wo du warst

`VideoResumeStore` (eigene SharedPreferences `video_resume`): Schlüssel Pfad + Größe + Änderungszeit, Wert Position und
Speicherzeit. Höchstens 300 Einträge, die ältesten fliegen; unter 10 s, über 95 % oder mit weniger als 10 s Rest wird
der Eintrag gelöscht. „Weiterschauen“ prüft dieselbe Regel beim Lesen noch einmal mit der Länge aus MediaStore (nie
„Noch 0:01“).
Geschrieben wird alle 5 s während der Wiedergabe, bei `onStop` und beim Wechsel zum nächsten Video; `content://`
anderer Apps wird nie gemerkt. Beim Öffnen erscheint eine Pille „Weiter bei 0:41“ (bis 5 s Wiedergabe, pausiert bleibt sie); Tippen springt dorthin,
sonst beginnt das Video vorn. Getestet in `VideoResumeStoreTest` (Schlüssel, Kappung, 95-%-Regel) und
`VideoControlsTest` (Tipp-Zonen, Gestenränder).

## Ordo Video (eigenes App-Symbol)

Wie Ordo Musik: `BuiltInApp.VIDEO` mit `launcher.VideoAlias` (Blau, handgeschrieben) und sieben Farben aus
`utils/launcher-icon-colors.py`; alle Aliasse im Manifest aus, nur der Schalter unter Einstellungen › Eingebaute Apps
schaltet sie ein. Symbol: Material Symbols `play_circle` (gefüllt, Skala 0,62 wie die Note der Musik, von codex
gewählt) auf derselben Farbfläche, zugleich Monochrom-Ebene. `VideoLauncherActivity` läuft in eigener Aufgabe
(`taskAffinity` `me.zhanghai.android.files.video`, `documentLaunchMode=intoExisting`); der Player bleibt darin.

Die Sammlung: oben „Weiterschauen“ (die gemerkten Stellen aus `VideoResumeStore`, zuletzt gesehen zuerst, höchstens 20,
als waagrechte Kartenreihe mit Fortschrittsbalken und „Noch 0:41“), darunter „Alle Videos“ aus MediaStore (alle
Speicher, nur vorhandene Dateien, versteckte nur bei „Versteckte Dateien zeigen“), neueste zuerst, als Raster mit
16:9-Vorschau und Laufzeit in der Ecke; ohne Vorschau (kaputte Datei) steht ein ruhiges Video-Symbol mittig. Kamera-
Namen (`VID_`, `PXL_`, `IMG_`, `MVIMG_` oder Samsungs `JJJJMMTT_hhmmss`) heißen nach ihrem Zeitpunkt, z. B.
„18. Nov. · 14:19“ in der Gerätesprache; TalkBack nennt zusätzlich den echten Namen. Die Liste blendet oben und unten
über 55 dp weich aus wie die Musiksammlung. Eine Chip-Reihe filtert nach Ordner (Reihenfolge nach neuestem Video; ab zwei
Ordnern sichtbar). Spalten: etwa 180 dp je Spalte, mindestens zwei; ab 600 dp so breit wie der Inhalt der
Musiksammlung (`musicContentWidthDp`). Vorschauen kommen von der Plattform (`ContentResolver.loadThumbnail` ab
Android 10, darunter `ThumbnailUtils.createVideoThumbnail`) in einen Speicher-Cache. Ohne Speicherzugriff dieselbe
Bitte mit Knopf wie in der Musik, ohne Videos ein ruhiger Hinweis. Ein Tipp spielt das Video mit seinem Ordner (nach
Namen sortiert) als Warteschlange; aus „Weiterschauen“ springt der Player gleich an die Stelle (`EXTRA_RESUME`, nur
zusammen mit Ordos Token wirksam). Logik ohne Android in `VideoLibrary`, getestet in `VideoLibraryTest`.

## Gerätetest

`e2e/video-player.sh` (`--write`) prüft die Ziele 1–7 und 9 des Plans mit Clips aus `ffmpeg`;
`e2e/no-midword-breaks.sh` geht den Player, die Sperre und das Tempo-Blatt mit durch. Kopfhörer und Audiofokus
(Ziel 8) und TalkBack (Ziel 10) sind von Hand zu prüfen.

## Farbe

Alle Bildschirme von Ordo Musik und Ordo Video (Player, Sammlung, Tag-Editor, Klang-Blatt, ihre Einstellungsseite)
nehmen die Akzentfarbe aus dem Theme (`colorPrimary`/`colorOnPrimary` der Akzent-Overlays, deren Kontraste
`accent_colors.xml` hell und dunkel festlegt); fest eingebaut ist dort keine Akzentfarbe. Jede der beiden Apps hat in
ihren Einstellungen eine eigene Wahl „Farbe“: „Wie Ordo“ (Standard; folgt Ordos Akzent und, wenn eingeschaltet, den
Farben des Hintergrundbilds) oder einer von Ordos fünf Akzenten (`AccentPreference`, als `AppAccentPreference` mit
„Wie Ordo“ vorn). Eine eigene Wahl gilt auch bei eingeschalteten Hintergrundbild-Farben, denn sie ist ausdrücklich.
`CustomThemeHelper` legt das Overlay je Activity an (`AppAccentActivity`), Änderungen bauen die offenen Bildschirme
der App sofort neu auf. Die Auflösung (App-Wahl → wirksamer Akzent) ist in `AppAccentTest` getestet. Bewusst nicht
umgefärbt: die Cover-Farben des Musik-Players (Schalter „Farben aus dem Cover“), die Platzhalter-Cover, die neutralen
Schleier und Pillen über dem Video (Schwarz/Grau mit weißer Schrift). Ordo Videos Einstellungen öffnen sich über das
Zahnrad in seiner Sammlung, Ordo Musiks über sein Seitenmenü.

## Nächster Schritt (entschieden, noch nicht begonnen)

Media3-FFmpeg-Erweiterung nur für Ton, gebaut mit den Decodern ac3, eac3, dts, truehd und mlp: günstige Geräte (W90)
haben keinen AC3-Decoder. Größe vor und nach messen, bevor sie kommt.

## Offen (messen)

Ruckler beim Drehen auf dem Tab; AC3/E-AC3/DTS in MKV auf Tab S9 Ultra und S21; Puffer und Spulen großer Filme über
SMB/SFTP; Vergleich mit Samsung Video und Google Fotos; ob `sensorLandscape` auf dem Tab unter Android 16 wirkt;
APK/AAB-Größe vorher/nachher (`bundletool get-size total`); wie `dumpsys media_session` die Session `video.<UUID>` benennt.
