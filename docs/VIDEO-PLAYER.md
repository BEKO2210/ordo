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
Speicher, SD-Karte, SMB, SFTP, WebDAV, FTP; `content://` über Media3. `onStop` sichert die Position und pausiert
(außer bei „Nur Ton“, siehe Phase 3).

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
  Tonspur, wird sie gewählt und der Hinweis sagt das. Das Video läuft weiter. Seit dem FFmpeg-Ton (unten) dekodiert
  Ordo AC3, E-AC3, DTS und TrueHD selbst; der Hinweis bleibt für AC-4 und alles andere, was weder Gerät noch FFmpeg
  kann.
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

## Serien, Gesehen, Suche, Auswahl (Bibliothek Pro)

- **Serien:** `library/VideoEpisodes` liest Folgen aus Datei- und Ordnernamen: `S01E02`/`s2e10`/`S01.E03`, `1x02`
  (nicht `1920x1080`, nicht `10x264`), „Folge 3“, „Episode 9“, „Ep. 4“, „E03“, „Teil 2“ (mit „Staffel 2“/„Season 2“/
  „S2“ im Namen oder im Ordner), Fansub-Anime „[Gruppe] Name - 03“ (mindestens zwei Ziffern, keine Auflösung wie
  720). Staffelordner „Staffel 1“, „Season_02“, „S3“; „Specials“/„OVA“ sind Staffel 0. Fehlt der Serienname vor der
  Nummer, gilt der Ordner (über dem Staffelordner). Eine Serie braucht mindestens zwei Folgen. Getestet mit 56 Namen
  in `VideoEpisodesTest` (gebaut von GitHub Copilot, Regeln nach einer Prüfung durch agy/Gemini nachgeschärft).
- **Regale** (`library/VideoShelves`, getestet in `VideoShelvesTest`): Weiterschauen, Serien (Karte → eigene Seite
  mit Staffeln und Folgen), Filme (über 40 min, in keiner Serie), Kürzlich hinzugefügt (30 Tage, höchstens 20), dann
  die Ordner-Chips und alle Videos. Eine Folge spielt mit ihrer Staffel als Warteschlange: mit „Automatisch weiter“
  läuft die Staffel durch. Ist die zuletzt gesehene Folge einer Serie fertig, bietet „Weiterschauen“ die nächste an
  („Als Nächstes · S1 F3“), außer eine Folge der Serie hat schon eine gemerkte Stelle.
- **Gesehen** (`library/VideoWatchedStore`, SharedPreferences `video_watched`, Schlüssel Pfad + Größe): der Player
  markiert bei 95 % oder weniger als 10 s Rest (nicht bei „Keine Stellen merken“ und nicht beim Wiederholen eines
  Videos), von Hand über die Auswahl. Jede Karte zeigt ihren Fortschritt, gesehene einen vollen Balken, nie
  begonnene einen Punkt in der Akzentfarbe; ⋮ › „Nur ungesehene“ filtert. Eine Stelle, die älter ist als die
  Gesehen-Marke, fällt aus „Weiterschauen“.
- **Suche** (Lupe): jedes Wort muss in Titel, Ordnername oder Serienname stehen, Groß-/Kleinschreibung und Akzente
  egal („lowenzahn“ findet „Löwenzahn“).
- **Auswahl:** Langes Drücken wählt aus (TalkBack-Aktion „Auswählen“), danach wählt jeder Tipp. Oben: Abspielen (die
  Auswahl als Warteschlange), Teilen, In den Papierkorb (mit Rückfrage; ist der Papierkorb aus: „Löschen“ mit
  Hinweis), Als gesehen/ungesehen markieren, In den Tresor (Rückfrage wie in der Dateiliste), Videoinfos (bei
  einem). Zurück beendet erst die Auswahl, dann die Suche, dann die Serienseite.

## Tresor-Videos

Ein Video im Tresor öffnet sich (Tresor entsperrt) in Ordos Player, die Videos des Tresors sind die Warteschlange.
`vault/VaultVideos` streamt es über Tinks `newSeekableDecryptingChannel` (`VaultStore.openSeekable`) als Media3-
`DataSource`: entschlüsselt wird segmentweise im Speicher, kein Klartext auf dem Speicher (anders als bei Bildern
und PDFs, die `vault-open` im Cache nutzen). Lesen darf der Player nur Einträge, die der entsperrte Tresor freigegeben
hat; Sperren (auch nach 2 min im Hintergrund) und Prozessende nehmen die Freigabe zurück. Tresor-Videos bekommen
keine gemerkte Stelle, keine Gesehen-Marke, keine Vorschau und erscheinen nie in der Sammlung (MediaStore sieht den
Tresor nicht); der Player-Bildschirm ist dann `FLAG_SECURE` (keine Bildschirmfotos, leere Vorschau in „Zuletzt“),
„Mit anderer App öffnen“ fehlt. Verlässt man den Player, schließt er sich (wie der Tresor sich sperrt); darum startet nie Hintergrund-Ton,
Benachrichtigung oder Bild-im-Bild mit einem Tresor-Video. Der Dateiname verlässt den Tresor nicht: das Medien-Item
hat die ID `ordo-vault:///<id>` und den Titel „Tresor-Video“, kein Cover, das Format nur als MIME-Typ; MediaSession,
Sperrbildschirm, Benachrichtigungs-Listener und Bluetooth sehen nur das (Test `VaultVideosTest`).

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

## Einstellungen von Ordo Video

Über das Zahnrad der Sammlung (`settings_video.xml`, Werte in `Settings.kt`, Bedeutung in `VideoPrefs`, getestet in
`VideoPrefsTest`). Nur Listen und Schalter, keine Regler; jede Zeile nennt ihren Wert.

- **Aussehen:** Farbe (siehe oben).
- **Wiedergabe:** Am Ende (dieselbe Einstellung wie die Wiederholen-Taste), Fortsetzen (Fragen mit Pille / Immer
  automatisch / Nie), Standard-Tempo (0,5–2×), Sprungweite Doppeltipp (5/10/15/30 s; gilt auch für die ±-Tasten,
  deren Symbol und TalkBack-Text; 15 s nutzt das schlichte Pfeilsymbol, weil Material Symbols kein replay_15 hat),
  Bildformat beim Start (Einpassen / Füllen / Strecken, wie die Taste), Ausrichtung (Nach Video / Wie System / Immer
  quer, nur Handy), Bildschirm bleibt an.
- **Gesten:** je ein Schalter für Helligkeit, Lautstärke, Spulen wischen, Doppeltipp spulen (aus: ein Tipp blendet nur
  die Leiste ein und aus, ohne 300 ms Wartezeit) und Halten für schneller; Tempo beim Halten (1,5/2/3×); Helligkeit
  merken (die zuletzt gewischte Helligkeit gilt beim nächsten Video und beim Zurückkehren).
- **Ton:** bevorzugte Tonspur-Sprache über `TrackSelectionParameters` (Gerätesprache, Original = Hauptspur, Deutsch,
  Englisch).
- **Untertitel:** bevorzugte Untertitel-Sprache (Gerätesprache, Deutsch, Englisch), Untertitel automatisch an.
- **Bibliothek:** Sortierung (Neueste / Name / Größe / Länge), Ansicht (Raster / Liste mit Vorschau links),
  „Weiterschauen“ anzeigen, Kameranamen lesbar, Ordner ausblenden (Auswahl aus allen Ordnern mit Videos, mit Anzahl).
- **Datenschutz:** „Weiterschauen“ löschen (mit Rückfrage), keine Stellen merken (dann auch kein Angebot zum
  Fortsetzen).

## Videoinfos

⋮ › „Videoinfos“ im Player und in der Sammlung „Videoinfos“ der Auswahl (ein Video lang drücken) öffnen ein Blatt statt der technischen
Dateieigenschaften: Titel (und echter Dateiname), Dauer, Auflösung mit Bildrate („1920 × 1080 · 30 fps“), HDR,
Video-Codec in Worten (H.264, HEVC 10-Bit, AV1 …), Bitrate, Tonspuren (Codec, Kanäle „5.1“, Sprache), Untertitel,
Größe, Ordner, Aufnahmedatum (aus der Datei, sonst Änderungsdatum). Im Player stammen die Spuren aus dem, was gerade
spielt (jede Quelle, auch SMB/SFTP und `content://`), in der Sammlung aus `MediaExtractor`. Langes Drücken auf eine
Zeile kopiert den Wert. Unten „Alle Dateidetails“ öffnet die bisherigen Eigenschaften für Besitzer, Rechte und
Prüfsummen; deren Reiter passen sich jetzt an (`tabMode="auto"`, schmalere Ränder, weiche Kante, wenn gescrollt werden
kann), auch für Audiodateien (dasselbe Fenster).

## Bild (`viewer/video/picture/`)

Quelle der Entscheidungen: `ordo-team/QUALITAET-PANEL.md`, Ideen 1–4. Logik ohne Android in `PicturePolicy` und
`SignalPath`, getestet in `PicturePolicyTest`.

- **Bildrate anpassen** (Einstellungen › Bild): Aus / Nur nahtlos (Standard) / Immer. „Nur nahtlos“ ist Media3s eigenes
  Verhalten (`Surface.setFrameRate` mit `ONLY_IF_SEAMLESS`, Bildrate × Tempo). „Immer“ schaltet Media3s Strategie aus und
  ruft ab API 31 selbst `setFrameRate(fps, FIXED_SOURCE, CHANGE_FRAME_RATE_ALWAYS)` mit der Bildrate der Datei (nicht
  × Tempo, sonst schaltet „Halten für 2×“ den Bildschirm jedes Mal um); auf API 30 gilt dann „nur nahtlos“. Beim
  Verlassen des Players, im Bild-im-Bild und bei neuer Oberfläche wird die eigene Rate zurückgenommen bzw. neu gesetzt
  (`FrameRateMatcher`). Wie lange der Bildschirm beim Umschalten schwarz wird, ist nicht gemessen.
- **Bildverbesserung** (Einstellungen › Bild: Aus / Ausgewogen (Standard) / Maximal; ⋮ › „Bildverbesserung“ zum
  Vergleichen): nur bei SDR ohne DRM, wenn das Video mindestens 1,15-fach vergrößert gezeigt wird. Dann
  `LanczosResample` (Media3, Radius 3) genau auf die Größe der Oberfläche und danach **ein** eigener Shader-Pass
  (`PictureShader`): Entbändern nach libplacebo (`pl_shader_deband`), Schärfen nach AMD FidelityFX CAS, Körnung und
  TPDF-Dither ±1 LSB. Ein Pass, weil Media3s SDR-Kette jeden Pass in 8 bit speichert. Wo das Entbändern eine glatte
  Fläche erkennt, wird nicht geschärft (CAS verstärkt sonst genau die Stufenkanten). Werte: Ausgewogen CAS 0,4, ein
  Schritt, Schwelle 3/1000, Körnung 2/1000; Maximal CAS 0,8, zwei Schritte, Schwelle 4/1000, Körnung 4/1000; Radius 16
  Quellpixel × Vergrößerung, höchstens 64.
- **Pipeline:** Media3 baut seine OpenGL-Kette nur beim Zurücksetzen des Renderers und behält sie danach
  (`MediaCodecVideoRenderer.onEnabled`). Darum wird vor `prepare()` entschieden (`PictureProbe`, `PicturePolicy.preEngage`):
  `MediaExtractor` liest bei lokalen Dateien und `content://` Größe, Drehung, Farbübertragung, Profil und PSSH des
  aktuellen und des nächsten Videos (höchstens 400 ms, je Datei gemerkt). Ist das erste Video SDR ohne DRM und wird
  es oder das nächste vergrößert (in einer der beiden Ausrichtungen), startet der Player mit der Kette (leere
  Effektliste, die echten Effekte folgen mit Spuren und Oberfläche); sonst ohne (HDR, Dolby Vision, große Videos
  gehen direkt vom Decoder auf den Bildschirm). Ohne Probe (SMB/SFTP/WebDAV/FTP/Archive, Zeitüberschreitung, HEVC
  Main 10 ohne Farbangabe), bei falscher Probe, beim Einschalten über ⋮ aus einem kettenlosen Start und bei einem
  späteren Video der Warteschlange, das als erstes vergrößert wird, setzt Ordo ersatzweise die Effekte und bereitet
  einmal neu vor (`stop()` + `prepare()`, Stelle bleibt); ein weiteres SDR-Video ohne Verbesserung bekommt eine leere Effektliste; ein HDR- oder
  DRM-Video danach baut den Bildschirm neu auf (`recreate()`), der wieder ohne Kette startet. Pausiert wird nach dem
  Umschalten an dieselbe Stelle gesprungen, damit das stehende Bild die neuen Effekte zeigt.
- **Ruckeln:** Meldet Media3 mehr als 2 verworfene Bilder pro Sekunde (in einem Block von mindestens 10), schaltet die
  Verbesserung für diesen Bildschirm ab, mit einem ruhigen Hinweis oben (einmal). Scheitert die GPU an der Kette
  (Fehler 7000/7001), versucht Ordo es bis zum nächsten Prozessstart nicht wieder.
- **Messen am Gerät:** `adb shell setprop log.tag.OrdoVideoPicture DEBUG`, Video neu öffnen, `adb logcat -s
  OrdoVideoPicture`: Entscheidung je Video, GPU-Zeit je Bild (nur mit Log, per `glFinish`) und alle 120 Bilder
  Mittel und Maximum. Wert bisher **nicht gemessen**.
- **Signalweg** (⋮ › „Signalweg“ und Knopf in „Videoinfos“): Bild: Decoder (Hardware/Software, Name), Format,
  Auflösung ein → aus (Lanczos oder vom Bildschirm skaliert), HDR-Art und ob der Bildschirm sie zeigt oder Android auf
  SDR umrechnet, Bildrate mit Bildschirm-Hz und Modus, aktive Stufen bzw. der Grund, warum nicht, verworfene Bilder.
  Ton: Format, Decoder (bei Durchreichen „keiner“), Klang-Stufen (im Video-Player bisher nur Tempo), Ausgabegerät (ab
  API 33 von Android, davor das wahrscheinlichste angeschlossene), Abtastrate gegen Androids Ausgaberate, Kanäle mit
  Abmischung oder System-Raumklang. „Unverfälscht“ steht nur, wenn nichts verändert. Aktualisiert sich jede Sekunde.
- **Bildtakt** (Zeile im Signalweg, `FramePacing`, getestet in `PictureMeasuresTest`): Abstände der von Media3
  geplanten Freigabezeiten je Bild (`VideoFrameMetadataListener`, schon auf das Vsync-Raster gelegt) geteilt durch die
  Vsync-Dauer der Anzeige, über die letzten 1200 Bilder, zurückgesetzt bei Sprung, neuem Video und Tempo. Teilt die
  Bildrate die Bildwiederholrate, steht „24 fps auf 120 Hz: 99,9 % gleichmäßig (5 Vsyncs je Bild)“, sonst
  „Pulldown 3:2“ mit Anteil und dem Anteil langer Bilder gegen den erwarteten (½ bei 3:2). Ab 48 Bildern. Das misst Ordos Planung; was das Panel wirklich zeigte, liest
  `e2e/video-picture.sh` aus `dumpsys SurfaceFlinger --latency`. Ziel nach `ordo-team/WISSENSCHAFT.md` §1.2.6: 24p auf
  120 Hz 99,9 % der Bilder genau 5 Vsyncs. Quellen: developer.android.com/media/optimize/performance/frame-rate (24p auf
  120 Hz ohne Pulldown); Chapiro, Atkins, Daly, ACM TOG 2019, doi:10.1145/3338696 (Judder hängt von der Helligkeit ab).
  **Nicht gemessen.** Debug-Log alle 240 Bilder: `pacing fps=… hz=… vsyncs={5=…}`.
- **Gamma an Raumlicht** (Einstellungen › Bild, experimentell, Standard aus; `AmbientGamma`, getestet in
  `PictureMeasuresTest`): ITU-R BT.2390-12 (03/2025), Abschnitt zu nicht genormten Umgebungen (BBC-Test, 21 Personen):
  γ_hell = γ_ref − 0,076 · log10(L_Umgebung / 5) für Umgebungen über 5 cd/m², γ_ref = 1,2 (Systemgamma nach BT.2100).
  Annahme, am Gerät zu kalibrieren: Lux des Lichtsensors → cd/m² über einen matten Raum mit 20 % Reflexion
  (L = E · 0,2 / π); volles Sonnenlicht (100 000 lx) ergibt so γ 0,96. Geglättet mit 5 s Zeitkonstante und höchstens 0,01 Gamma pro Sekunde (kein
  Pumpen). Im Shader als Exponent-Verhältnis auf der Luma wie HLGs OOTF: rgb · Y'^(γ/1,2 − 1), pro Bild gelesen, ohne
  die Kette neu zu bauen. Wirkt nur, solange der Bildverbesserungs-Pass läuft; der Sensor hört nur bei sichtbarem
  Player zu. Signalweg-Zeile „Raumlicht: 1 200 lx → Gamma 1,11 (Bezug 1,20)“.
- **HDR→SDR mit eigener EETF (BT.2408-9 Anhang 5): nicht gebaut**, weil Media3 1.11.1 es nicht zulässt. Eigene Effekte
  bekommen HDR nur dann linear (BT.2020, 16 bit), wenn die Ausgabe HDR ist (`GlEffect`-Javadoc,
  `DefaultVideoFrameProcessor` L703–722). Ist die Ausgabe SDR, rechnet schon der Eingangs-Shader mit Media3s fester
  Kurve um (`DefaultShaderProgram`, `uApplyHdrToSdrToneMapping`), bevor ein Effekt das Bild sieht. Welche Ausgabe gewählt
  wird, entscheidet `PlaybackVideoGraphWrapper.registerInput` (L568–596) selbst; der Schalter
  `setRequestOpenGlToneMapping` ist nicht öffentlich über ExoPlayer erreichbar. Mit HDR-Ausgabe würde Android die
  Umrechnung für ein SDR-Display wieder selbst machen (doppelt), und die Kette verwirft HDR-Metadaten
  (`setHdrStaticInfo(null)`) und Dolby Vision. Darum bleibt HDR wie bisher ohne Kette direkt beim Decoder.
- **Bild anpassen** (⋮ › „Bild anpassen“, `PictureAdjustSheet`): Helligkeit, Kontrast, Sättigung, Farbtemperatur und
  Schärfe je −3…+3 mit −/+ (48 dp, kein Regler), live. Alles in **demselben** Pass von `PictureShader`: eine
  4×4-Farbmatrix (`PictureAdjust.colorMatrix`, getestet in `PictureAdjustTest`; Reihenfolge Farbtemperatur → Sättigung
  um die Rec.-709-Luma → Kontrast um 0,5 → Helligkeit; je Schritt ±0,03 / ×0,08 / ×0,15 / R·B ±3 %) nach dem
  Raumlicht-Gamma und vor Körnung und Dither; Schärfe + addiert 0,2 je Schritt zu CAS, Schärfe − mischt bis 60 % zum
  3×3-Mittel. Die Werte liest der GL-Faden je Bild (`PictureLive`), ohne die Kette neu zu bauen. Läuft die Kette
  nicht (z. B. Video so groß wie der Bildschirm), gelten die bestehenden Regeln (`PicturePolicy.pipeline(…, adjusted)`,
  `preEngage(…, adjusted)`): gemerkte Werte schalten die Kette schon vor `prepare()` ein, sonst einmal neu vorbereiten.
  Ohne Bildverbesserung dann ohne Lanczos und mit `ADJUST_ONLY` (nur Dither). Nicht bei HDR, DRM oder gescheiterter
  GPU (das Blatt sagt es). Gemerkt je Video (`PictureAdjustStore`, höchstens 300): nie für Tresor-Videos,
  `content://` anderer Apps oder bei „Keine Stellen merken“; „Weiterschauen löschen“ löscht sie mit. „Zurücksetzen“.
- **Zeilensprung entfernen** (Automatisch/An/Aus im selben Blatt): erkannt aus `Format.initializationData`
  (`Deinterlace`, getestet in `DeinterlaceTest`): H.264-SPS `frame_mbs_only_flag` 0, MPEG-2-Sequenz-Erweiterung
  `progressive_sequence` 0; bei H.264 mit Film-Bildrate (≤ 24,5 fps) gilt das Flag allein als unbekannt
  (Blu-ray-Encoder setzen es auch bei Vollbildern); andere Codecs gelten als unbekannt (Schalter). Ein eigener erster Pass auf den
  Original-Zeilen vor Lanczos (`DeinterlaceEffect`): Linear-Blend 1-2-1 nur dort, wo eine Zeile von beiden Nachbarn in
  dieselbe Richtung abweicht (Kammlinien), ruhige Flächen behalten ihre Auflösung. Kein YADIF (bräuchte die Nachbarbilder).
- **Lizenzen:** CAS MIT (Text im Kopf von `PictureShader.kt`), libplacebo LGPL-2.1-or-later (nach §3 als GPL-3.0
  weitergegeben); beide in `res/raw/licenses.xml`. Neue Abhängigkeit `media3-effect` 1.11.1.
- **Gerätetest:** `e2e/video-picture.sh` (`--write`-Liste in `e2e/run.sh`).

## Hintergrund, Bild-in-Bild, Schlaf-Timer (Phase 3)

Code in `viewer/video/background/`; die Activity ruft nur `VideoBackgroundController` auf (Session bauen, `onStop`,
`onUserLeaveHint`, `onPictureInPictureModeChanged`, ⋮-Einträge, `release`). Der Player bleibt der Activity (kleinster
Umbau, kein Controller-Umweg); der Dienst beherbergt nur ihre Session.

- **Im Hintergrund weiterspielen** (Einstellungen › Wiedergabe, `VideoBackgroundPrefs.BACKGROUND`): Aus (Standard) /
  Nur Ton. Bei „Nur Ton“ startet `onStart` den eigenen `VideoPlaybackService` (`MediaSessionService`, FGS
  `mediaPlayback`) und hängt die Session des Players an; Ordo Musiks `AudioPlaybackService` und seine Warteschlange
  bleiben unberührt. Der Dienst startet, solange der Bildschirm vorn ist, weil Android 12+ das Wechseln in den
  Vordergrund aus dem Hintergrund verbietet; darum steht die Medien-Benachrichtigung bei „Nur Ton“ schon während
  des Schauens. `onStop` lässt ein laufendes Video dann weiterspielen und schaltet nur die Bildspur ab
  (`setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)`), `onStart` schaltet sie wieder ein, ohne Neustart. Schließen des
  Players oder seines Bild-in-Bild-Fensters pausiert immer (`VideoBackgroundRules.keepsPlaying`, getestet).
  Benachrichtigung: eigener Kanal `video_playback`, eigene ID 2001 (Media3s Standard 1001 ist die der Musik). Ihr
  Cover ist ein Bild des Videos (`VideoFrameLoader`: `MediaMetadataRetriever` an der aktuellen Stelle, mindestens
  10 % hinein; nur lokale Dateien und `content://`, übers Netz ohne Cover). Tippen auf Benachrichtigung oder
  Sperrbildschirm führt über `VideoSessionActivity` (zeigt nichts) zur Aufgabe des laufenden Players zurück.
  Der Dienst hat bewusst keinen `MediaSessionService`-Intent-Filter: Media3s `MediaButtonReceiver` erwartet genau
  einen solchen Dienst (den der Musik).
- **Bild-in-Bild** (Schalter, Standard an, `VideoBackgroundPrefs.PIP`; nur mit `FEATURE_PICTURE_IN_PICTURE`, sonst
  fehlt auch ⋮ › „Bild-in-Bild“): ab Android 12 `setAutoEnterEnabled` nur während der Wiedergabe, Android 8–11 über
  `onUserLeaveHint`. Seitenverhältnis des Videos (mit Pixelform, auf Androids 1:2,39–2,39:1 gekappt,
  `VideoBackgroundRules.pipAspect`), `sourceRectHint` aus der Bildfläche, `setSeamlessResizeEnabled(false)`. Aktionen
  −Sprung · Abspielen/Pause · +Sprung (Sprungweite aus den Einstellungen) über einen nicht exportierten Receiver.
  Im Fenster zeichnen Leiste, Hinweise und Pillen nichts (leere `clipBounds`, ihre Logik läuft unverändert weiter),
  Untertitel bleiben. Positionsspeicher läuft weiter (5-s-Takt). Fenster schließen pausiert.
- **Schlaf-Timer** (⋮ › „Schlaf-Timer“, `VideoSleepTimer`): Chips Aus · 15 · 30 · 45 · 60 Min. · Ende des Videos;
  läuft er, nennt das Blatt die Restzeit. Die letzten 10 s blendet der Ton aus (`SleepTimerSchedule` der Musik), dann
  Pause. „Ende des Videos“ hält auch bei „Wiederholen“ oder „Automatisch weiter“ an.
- **Audiofokus, Kopfhörer:** wie bisher ExoPlayer (`handleAudioFocus`, `setHandleAudioBecomingNoisy`) – das entspricht
  den Standards der Musik (Ducken, Pause bei kurzem Verlust und weiter danach, Pause beim Abziehen), auch im
  Hintergrund. Die Musik-Schalter „Fokus: Pause statt leiser“ und „Weiter bei Kopfhörern“ gelten für Videos nicht.
- **Medientasten bei Musik und Video:** Android schickt sie an die zuletzt aktive Session (die zuletzt gespielt hat);
  wer zuletzt gestartet wurde, gewinnt. Weil Video den Audiofokus nimmt, pausiert die Musik beim Start eines Videos.
  Ist der Video-Player geschlossen, gehört die Session wieder allein der Musik.

### Öffentliche Schnittstelle (Haken für spätere Automatisierung, keine KI)

`viewer/video/background/VideoActions.kt` wie `MusicActions`: `VideoActions` (Datei/Ordner spielen, springen, Tempo,
Ton- und Untertitelsprache, Wiederholen setzen/weiterschalten, Schlaf-Timer, Zustand abfragen), `VideoSelection`
(ohne Android, getestet in `VideoSelectionTest`: Sprachkürzel normalisieren „deu“/„de-DE“ → „de“, passende Spur
wählen, Ordner-Warteschlange nach Namen), `ControllerVideoActions` auf einem verbundenen `MediaController`;
`VideoBackground.actions(context, controller)` liefert sie fertig (Token: `VideoBackground.session?.token`). Eigene
Session-Befehle (`VideoCommands`: `SLEEP_TIMER`, `SET_AUDIO_LANGUAGE`, `SET_SUBTITLE_LANGUAGE`, `SET_REPEAT`,
`CYCLE_REPEAT`, `GET_STATE`) bekommen nur Ordos eigene Controller (nicht die Benachrichtigung) und vertrauenswürdige
System-Controller (`isTrusted`); alle anderen dürfen wie bisher nur spielen und spulen, nie eigene Einträge setzen.

**Gerätetest:** `e2e/video-background.sh` (`--write`): Schlaf-Timer-Blatt, Home → `pinned` und spielt, Medientaste
im Bild-in-Bild, „Nur Ton“ mit Bildschirm aus (spielt, Position läuft, Kanal da), Aufwachen zeigt den Player. Danach
`e2e/audio-player.sh` (Musik-Warteschlange unberührt). Offen (messen): ob Samsung One UI Bild-in-Bild für Ordo
abschaltet; Medientasten bei zwei Sessions auf echten Geräten; ob `dumpsys` `mode=pinned` so schreibt.

## Untertitel (Phase 2)

Code in `viewer/video/subtitles/`, ohne neue Bibliothek. Knopf „Untertitel“ oben in der Leiste öffnet ein Blatt:
„Aus“, die Untertitel-Dateien, die Spuren im Film, „Untertitel laden…“ (Ordos eigene Auswahl, im Ordner des Films),
für eine Datei und eine Text-Spur im Film die Verzögerung, für eine Datei die Zeichenkodierung, für alle Filme Größe (Klein/Mittel/Groß/Sehr groß), Hintergrund
(Ohne/Schatten/Kasten) und Farbe (Weiß/Gelb) als Chips. Keine Regler.

- **Dateien daneben** (`SubtitleFiles.sidecars`, getestet in `SubtitleFilesTest`): gleicher Name wie der Film, ohne
  Groß-/Kleinschreibung, Endung `.srt .vtt .ass .ssa .ttml .dfxp`, dazwischen Kürzel wie `film.de.srt`,
  `film.en.forced.srt`, `Film.DEU.ass`, `film.German.srt`, `film.mkv.srt`. Die erste Sprachangabe zählt (ISO 639-1/-2
  samt `ger`/`fre` …, englische Namen); `hi`, `cc`, `sdh` gelten als Hörgeschädigten-Kennung, nicht als Hindi. Nur für
  Ordos Pfade (auch SMB/SFTP), nie für `content://` anderer Apps; dort geht nur „Untertitel laden…“.
- **Zeichensatz** (`SubtitleFiles.decode`): BOM (UTF-8, UTF-16), dann streng UTF-8 (`REPORT`), sonst die alte
  Windows-Codepage der Gerätesprache (1250 Mitteleuropa, 1251 Kyrillisch, 1253 Griechisch, 1254 Türkisch, sonst 1252).
  Im Blatt „Automatisch (windows-1252)“ zeigt, was erkannt wurde; UTF-8, ISO-8859-15 und die Codepages lassen sich je
  Film erzwingen.
- **Darstellung:** Die Datei wird selbst gelesen und mit Media3s eigenen Parsern (`DefaultSubtitleParserFactory`:
  SubRip, SSA/ASS, WebVTT, TTML) zerlegt, dann alle 50 ms nach der Abspielstelle in die `SubtitleView` des
  `PlayerView` gelegt (`SubtitleTimeline`); die Textspuren des Players sind dabei aus. ASS: nur Text und Lage
  (`\an`, `\pos`), keine Effekte. Höchstens 8 MB je Datei.
- **Verzögerung** ±0,1 s / ±1 s (höchstens ±10 min) verschiebt nur die Suche in der Zeitleiste, ohne Neuladen.
- **Text-Spuren im Film** (SRT, ASS, WebVTT, TX3G, CEA in MKV/MP4) zeichnet Ordo selbst: `Player.Listener.onCues`
  legt jede Cue-Gruppe mit ihrer Zeit in eine `EmbeddedCueQueue` (getestet in `EmbeddedCueQueueTest`: Verzögerung,
  Pause, Tempo, Sprung, Lese-Hilfe); gezeigt wird alle 50 ms die Gruppe für Abspielstelle − Verzögerung. Pause und Tempo
  brauchen nichts Eigenes, ein Sprung leert die Warteschlange, der Renderer schickt die neue Stelle. Für „früher“
  (negative Verzögerung) fragt `SubtitleLead` (ein `ForwardingRenderer` um Media3s `TextRenderer`) den Renderer um die
  Verzögerung voraus; die Cues warten dann in der Schlange. Ändert sich dieser Vorlauf, springt Ordo an dieselbe Stelle
  (kurzes Nachladen wie bei der Ton-Verzögerung), sonst fehlten die übersprungenen Zeilen. Geänderte
  Hörgeschädigten-Einstellungen gelten für Zeilen ab dann. Grenze: weiter voraus als Media3 gepuffert hat (rund 50 s)
  kommt eine frühe Zeile zu spät. Nach einem Sprung fehlen bei positiver Verzögerung bis zu deren Länge die Zeilen
  vor der Sprungstelle. Hörgeschädigten-Optionen (Geräusche, Sprecherfarben) und die Lese-Hilfe (Zeile bleibt ihre
  Lesezeit, nie über die nächste) gelten wie bei Dateien; „Langsamer bei schnellem Text“ nur für Dateien (die Dauer
  einer Spur-Zeile ist erst an ihrem Ende bekannt). **Bild-Untertitel** (PGS, VobSub, DVB) bleiben bei Media3s eigener
  Darstellung ohne Verschiebung; das Blatt sagt „Verschieben nur für Text-Untertitel“.
- **Spuren im Film** (MKV/MP4: SRT, ASS, WebVTT, TX3G, PGS, VobSub, DVB) gehen über `TrackSelectionOverride`. Ohne Wahl
  kommen nur „erzwungene“ Spuren von selbst (`setIgnoredTextSelectionFlags(SELECTION_FLAG_DEFAULT)`). Bild-Untertitel
  (PGS, VobSub, DVB) nimmt der Stil nicht an; das Blatt sagt das.
- **Automatisch** (`SubtitleChoice.pick`, getestet in `SubtitleLogicTest`): zuerst die für diesen Film gemerkte Wahl
  (Spur nach ID, sonst nach Sprache); sonst bei „Untertitel automatisch an“ (Standard an), wenn der Ton nicht schon in
  der bevorzugten Sprache ist: Datei in der Sprache, Spur in der Sprache, Datei ohne Sprachkürzel.
- **Gemerkt je Film** (`SubtitleStore`, SharedPreferences `video_subtitles`, Schlüssel = Pfad-URI): Wahl, Verzögerung,
  Zeichensatz; höchstens 300, älteste fliegen; nicht bei „Keine Stellen merken“, gelöscht mit „Weiterschauen löschen“.
  Der Stil gilt für alle Filme.
- **Lage:** Ist die Bedienleiste sichtbar, rücken die Untertitel über sie (`setBottomPaddingFraction`), sonst Media3s 8 %.
- **Videoinfos** nennen die Dateien als weitere Untertitel-Zeilen („Deutsch · film.de.srt“).
- **Schnittstelle für später:** `VideoSubtitles.choices()`, `currentChoice()`, `choose()`, `pickFile()`; keine KI im
  Player.

### Lese-Hilfe, Hörgeschädigte, Kontrast (aus `ordo-team/WISSENSCHAFT.md` §3)

Gilt für Untertitel-Dateien (deren Zeiten Ordo selbst führt); Spuren im Film zeigt Media3 mit ihren eigenen Zeiten.

- **Lese-Hilfe** (`ReadingAid`, Einstellung Aus / Normal 17 / Langsam 13 / Schnell 20 Zeichen pro Sekunde, Standard 17):
  Jede Zeile braucht das Längste aus Zeichen ÷ Grenze, **0,3 s pro Wort** und **1 s pro Zeile**. Zeigt die Datei sie
  kürzer, bleibt sie länger stehen, aber höchstens bis zum Beginn der nächsten Zeile (nie überlappend; Überlappungen,
  die die Datei selbst hat, bleiben). Zeichen zählen mit Leerzeichen, ein Zeilenumbruch als eines. Stücke einer Zeile
  (WebVTT/TTML liefern Abschnitte) werden vorher zusammengelegt. Die Zeiten des Films ändern sich nie, Bild und Ton
  bleiben synchron.
- **Bei schnellem Text kurz langsamer** (Standard aus): Solange eine Zeile über der Grenze steht, läuft der Film mit
  0,9× seines Tempos (Tonhöhe bleibt), danach wieder wie vorher. Hat der Nutzer das Tempo inzwischen selbst geändert,
  bleibt seins.
- **Geräuschbeschreibungen zeigen** (Standard an): aus, so verschwinden `[…]` und `(GROSSBUCHSTABEN)` samt leer
  gewordener Zeilen; `(klein geschrieben)` ist Dialog und bleibt (`Sdh`).
- **Sprechernamen farbig** (Standard an): `ANNA:` am Zeilenanfang bekommt je Sprecher eine Farbe in der Reihenfolge der
  BBC (Gelb, Cyan, Grün; Weiß ist der Text), über den ganzen Film gleich.
- **Kontrast:** Standard ist jetzt „Kasten“ (Weiß auf Schwarz wie bei der BBC). Sein Alpha ist mindestens 0,75 und so
  hoch, dass Weiß und Gelb auch über reinem Weiß **≥ 4,5:1** erreichen (WCAG 2.2, 1.4.3; geprüft in `ReadingAidTest`
  über zehn Hintergründe: Weiß 10,5:1, Gelb 8,6:1). „Ohne“ und „Schatten“ hängen vom Bild ab und halten das nicht
  sicher ein.
- Von den Dateien bleiben nur Kursiv, Fett und Unterstrichen; Farben und Größen der Datei weichen der Wahl im Blatt.

Quellen (geprüft laut `WISSENSCHAFT.md` §7): Netflix *English (USA) Timed Text Style Guide* (≤ 20 Z/s Erwachsene,
≤ 17 Kinder) und *Swedish Timed Text Style Guide* (≤ 17 / ≤ 13); *BBC Subtitle Guidelines*
(bbc.co.uk/accessibility/forproducts/guides/subtitles: 0,3 s pro Wort, Weiß auf Schwarz, Sprecherfarben); Szarkowska,
Gerber-Morón 2018, PLOS ONE, doi:10.1371/journal.pone.0199331 (20 Z/s für die meisten lesbar, zu langsame Untertitel
stören); Jensema 1998, American Annals of the Deaf, doi:10.1353/aad.2012.0073 (≈ 145 Wörter/min angenehm, Probleme ab
170); Burnham et al. 2008, J. Deaf Stud. Deaf Educ., doi:10.1093/deafed/enn003 (Lesekompetenz wichtigster Faktor,
darum Stufen nach Wunsch statt nach Alter); W3C *WCAG 2.2* 1.4.3. Annahme, nicht belegt: dass das Verlängern selbst
das Verstehen verbessert (Nutzertest offen).

## FFmpeg-Ton

Günstige Geräte (Unisoc W90) haben keine Decoder für AC3, E-AC3, DTS und TrueHD; MKV-Filme liefen dort stumm. Ordo
bringt dafür Media3s FFmpeg-Erweiterung (`decoder_ffmpeg`, nicht auf Maven) mit, nur für Ton und nur mit diesen
Decodern.

- **Wahl des Decoders:** `DefaultRenderersFactory` mit `EXTENSION_RENDERER_MODE_ON` im Video-Player, in der
  Spurprüfung der Videoinfos (`VideoInfo.probeTracks`, damit „unterstützt“ stimmt) und im Musik-Player
  (`AudioRenderersFactory`, die Klang-Kette wirkt auch auf FFmpeg-Ton). Der Plattform-Decoder kommt zuerst, FFmpeg nur,
  wenn das Gerät die Spur nicht kann (Media3-README, „Using the module with ExoPlayer“). `FfmpegRendererTest` prüft
  den Konstruktor, den Media3 per Reflexion sucht.
- **Bau:** `utils/ffmpeg-audio/build.sh` folgt `libraries/decoder_ffmpeg/README.md` von Media3 1.11.1: Media3-Tag
  `1.11.1`, FFmpeg-Tag `n6.0.1` (Zweig release/6.0, wie das README empfiehlt), Media3s `build_ffmpeg.sh` mit
  `ANDROID_ABI=24` (minSdk) und den Decodern `ac3 eac3 dca truehd mlp`, NDK 30.0.16248370, danach `ffmpeg_jni.cc`
  über Media3s `CMakeLists.txt` und gestrippt. ABIs: arm64-v8a, armeabi-v7a, x86_64 (wie die übrigen nativen Teile;
  x86 nicht). Die Configure-Zeile ist die von `build_ffmpeg.sh`: `--target-os=android --enable-static
  --disable-shared --disable-doc --disable-programs --disable-everything --disable-avdevice --disable-avformat
  --disable-swscale --disable-postproc --disable-avfilter --disable-symver --enable-swresample --extra-ldexeflags=-pie
  --disable-v4l2-m2m --disable-vulkan --enable-decoder=…` je Decoder.
- **Nicht in git:** Die `.so` (je ABI gut 1 MB, zusammen über 3 MB) baut Gradle selbst: `:ffmpeg:buildFfmpegAudio`
  läuft vor `preBuild`, einmal je Checkout (rund eine Minute, braucht Netz, git, make, cmake und das NDK) und legt sie
  in `ffmpeg/build/ffmpeg-audio/<abi>/`. Neu nur, wenn sich das Skript ändert oder `clean` lief. In git liegen
  Media3s Java-Quellen und `proguard-rules.txt` (aus `libraries/decoder_ffmpeg` des Tags, unverändert); bei einem
  Media3-Update von Hand neu kopieren, das Skript fasst sie nicht an.
- **Lieferkette:** Das Skript führt fremde Skripte aus (`build_ffmpeg.sh`, FFmpegs `configure`). Darum sind beide
  Quellen auf Commits festgenagelt (`MEDIA3_SHA` 8c6678b6… für Tag 1.11.1, `FFMPEG_SHA` c41ff724… für n6.0.1); nach
  dem Klonen bricht es ab, wenn `git rev-parse HEAD` abweicht (ein umgehängter Tag fällt so auf). In der CI läuft der
  Bau in einem eigenen Schritt „Build FFmpeg audio decoders“ (NDK installieren, `:ffmpeg:buildFfmpegAudio`, `.so`
  prüfen), in `play-internal.yml` vor dem Schritt, der den Signierschlüssel schreibt; spätere Gradle-Aufrufe
  überspringen ihn mit `-x :ffmpeg:buildFfmpegAudio`.
- **16 KB:** NDK 30 linkt mit 16-KB-Seiten; `utils/check-16k.sh` auf Beta-APK und -AAB grün
  (`libffmpegJNI.so` LOAD align 0x4000).
- **Größe** (Beta, 19.11.2026, `bundletool get-size total --dimensions=ABI`, Download MIN): arm64-v8a
  10 078 893 → 10 633 839 B (+554 946), armeabi-v7a 7 563 956 → 8 111 850 B (+547 894), x86_64 10 295 238 →
  10 857 989 B (+562 751), x86 +12 420 (nur Java). Universal-APK 19 081 336 → 20 726 236 B (+1 644 900), AAB
  29 617 397 → 31 277 543 B (+1 660 146). `libffmpegJNI.so` entpackt: arm64 1 160 616, armv7 1 060 880, x86_64
  1 209 128 B.
- **Lizenz:** FFmpeg ohne `--enable-gpl`, `--enable-nonfree` und `--enable-version3`, also LGPL-2.1-or-later;
  statisch gelinkt, was bei Ordos offenem GPL-3.0-Quelltext erlaubt ist (LGPL-2.1 § 3/§ 6). Über › Lizenzen nennt
  „This software uses libraries from the FFmpeg project under the LGPLv2.1“ mit Link auf den Quell-Tarball
  `ffmpeg-6.0.1.tar.xz` (Checkliste https://ffmpeg.org/legal.html). Media3s Wrapper ist Apache-2.0 (schon gelistet).
  Patente ungeprüft (FFmpeg selbst: „We do not know“).
- **Updates:** Bei einem Media3-Update `MEDIA3_TAG` im Skript mitziehen; `FfmpegRendererTest` schlägt an, wenn der
  Konstruktor nicht mehr passt.

## Offen (messen)

Ruckler beim Drehen auf dem Tab; AC3/E-AC3/DTS in MKV auf Tab S9 Ultra und S21; Puffer und Spulen großer Filme über
SMB/SFTP; Vergleich mit Samsung Video und Google Fotos; ob `sensorLandscape` auf dem Tab unter Android 16 wirkt;
AC3/DTS über FFmpeg auf dem W90 (Ton, CPU-Last); wie `dumpsys media_session` die Session `video.<UUID>` benennt.

## Profi-Spulen (`viewer/video/seek`)

Alles in `VideoSeekPro` (Bedienung), `SeekThumbnails`, `SeekBarMarks`, `SeekKeys` und `SeekLogic` (ohne Android,
getestet in `SeekLogicTest`). Der Player-Bildschirm reicht nur Berührungen, Tasten und die Wisch-Vorschau durch;
die neuen Einträge hängt `VideoSeekPro` selbst ins ⋮-Menü und in die Ansicht.

- **Vorschaubild:** Beim Ziehen am Balken (nach dem 150-ms-Halten) und beim waagrechten Spul-Wisch steht über dem
  Balken ein Bild der Zielstelle mit dem Kapitelnamen. `MediaMetadataRetriever` auf einem Hintergrund-Thread, nächstes
  Schlüsselbild (`OPTION_CLOSEST_SYNC`, schnell, bis zu einem Schlüsselbild-Abstand daneben), 160 dp breit (Tablet
  220 dp), LRU im Speicher (12 MB). Raster: Länge / 200, mindestens 1 s, höchstens 10 s. Nur lokale Dateien und
  `content://`; SMB/SFTP/WebDAV/FTP zeigen kein Bild (ein zweiter Leser über das Netz würde mit der Wiedergabe
  konkurrieren), nur den Kapitelnamen, wenn es einen gibt.
- **Kapitel:** Media3 1.11 liest Kapitel aus MP4 (Nero/QuickTime) und Matroska als `Chapter` in den Metadaten der
  Spuren. Ab zwei Kapiteln: Striche im Balken, ⋮ › „Kapitel“ als Blatt (aktuelles fett, „Läuft gerade“ für TalkBack,
  Tippen springt), Name in der Vorschau.
- **Einzelbild:** ⋮ › „Einzelbild“ pausiert und zeigt unter der Titelleiste ‹ Bild n › ✕; Tasten `,` und `.` gehen
  auch ohne die Leiste. Ziel = Anfang des nächsten/vorigen Bildes aus der Bildrate der Videospur (sonst 30 fps),
  abgerundet auf ms; ExoPlayer sucht standardmäßig exakt (`SeekParameters.DEFAULT` = `EXACT`), dekodiert ab dem
  Schlüsselbild davor und zeigt das erste Bild ab dem Ziel. Genau bei fester Bildrate; bei variabler kann ein Schritt
  ein Bild überspringen oder wiederholen. Rückwärts kostet einen Schlüsselbild-Abstand Dekodierzeit. Abspielen beendet
  den Modus.
- **A-B-Wiederholung:** ⋮ › „Schleifenstart A setzen“ → „Schleifenende B setzen“ → „A-B-Schleife beenden“. B vor A
  tauscht, unter 0,5 s Abstand wartet sie weiter auf B. Ein `PlayerMessage` an B (`deleteAfterDelivery = false`)
  springt bildgenau zu A, jedes Mal. Marken und Band im Balken in der Akzentfarbe. Videowechsel löscht die Schleife.
- **Standbild speichern:** ⋮ › „Standbild speichern“: `PixelCopy` aus der `SurfaceView` in Videogröße (höchstens
  3840 px), JPEG 95 nach `Pictures/Ordo` (ab Android 10 über MediaStore, darunter Datei + Medienscan), Snackbar mit
  „Öffnen“. HDR kommt als SDR heraus (PixelCopy-Grenze).
- **Zoom:** Zwei Finger zoomen (1–5×) und verschieben; die Ein-Finger-Gesten bekommen dabei ein Abbrechen, es spult
  also nichts mit. Kurzer Zwei-Finger-Tipp oder die Pille „1,8×“ oben rechts setzen zurück; Videowechsel auch. Gezoomt
  wird über die Größe von `PlayerView` (nicht über eine View-Skalierung, der die SurfaceView nicht sicher folgt),
  darin gilt das Bildformat (Einpassen/Füllen/Strecken) weiter. Gesperrt kein Zoom.
- **Tasten** (Tablet-Tastatur, Chromebook, TV-Fernbedienung): Leertaste/K Abspielen, ←/→ und J/L ± Sprungweite,
  ↑/↓ Lautstärke, F Steuerung ein/aus, M stumm, `,`/`.` Einzelbild. Hat eine Taste den Fokus (Fernbedienung
  navigiert), bleiben Pfeile und Leertaste bei der Navigation; mit Strg/Alt/Meta gar nichts. Gesperrt keine Tasten.

Gerätetest: `e2e/video-seek.sh` (`--write`); Vorschaubild und Zoom sind von Hand zu prüfen (kein Bedienungshilfe-
Knoten, `adb input` kann keine zwei Finger).
