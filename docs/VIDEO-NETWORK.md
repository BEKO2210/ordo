# Ordo Video: Filme aus dem Netz und auf dem Fernseher

Stand: 19.11.2026. Beschreibt den Code (`viewer/video/network/`, `viewer/video/cast/`), nicht einen Gerätetest.
Ergänzt `docs/VIDEO-PLAYER.md` (Phase 1) und `docs/AUDIO-PLAYER.md` (Streaming der Musik, deren Cast-Stack hier
wiederverwendet wird).

## Große Filme von SMB, SFTP, WebDAV, FTP

- **Puffer nach Quelle** (`VideoBuffering`, getestet in `VideoBufferingTest`): Entscheidet das erste Video der
  Warteschlange (Schema `smb`, `sftp`, `ftp`, `ftps`, `ftpes`, `dav`, `davs`). Lokal bleiben Media3s Standardwerte
  (50 s, Start nach 1 s). Netz: mindestens 60 s, bis 180 s, Start nach 2,5 s, nach einem Hänger erst bei 5 s weiter
  (weniger Stottern), dazu 30 s Rückpuffer: Wer 10 s zurückspringt, liest nichts neu. Sprünge in den schon geladenen
  Bereich vorwärts bedient Media3 aus dem Puffer (`seekInsideBuffer`). Die Bytegrenze bleibt Media3s Standard, damit
  Geräte mit wenig Speicher nicht leiden.
- **Vorauslesen** (`ReadAheadDataSource`, getestet in `ReadAheadDataSourceTest`): Media3s Extraktoren lesen in kleinen
  Stücken; ohne Puffer wäre jedes ein Rundgang zum Server. Für Netzquellen liest Ordo bis 512 KiB am Stück (gibt nach
  128 KiB schon weiter) und zählt die Bytes für die Anzeige der Geschwindigkeit.
- **Wiederholen** (`VideoNetworkPlayback.RetryPolicy`): 12 Versuche mit wachsender Pause (1, 2, 3, 4, dann je 5 s),
  also rund eine Minute, bevor ein Fehler erscheint. Ein kurzer WLAN-Aussetzer wird so zum Puffern.
- **Pille beim Puffern:** erst nach 700 ms (kein Flackern), unter den mittleren Tasten, „Lädt … 42 % · 3,1 MB/s“
  (Prozent = Vorrat bis zum Weiterspielen); nach einem Ladefehler „Verbindung unterbrochen. Verbinde neu …“, das
  TalkBack einmal ansagt. Nur für Netzquellen.
- **Fehler:** Ein- oder Ausgabefehler eines Netzfilms heißen „Server nicht erreichbar. Ordo versucht es erneut, sobald
  das Netz wieder da ist.“ (fehlende Datei: „Dieses Video liegt nicht mehr auf dem Server.“). Ordo versucht es alle
  10 s (höchstens 30-mal) und sofort, wenn Android ein Netz meldet (`registerDefaultNetworkCallback`); `prepare()`
  macht an derselben Stelle weiter.
- **„Netzwerk“ in der Sammlung** (`VideoNetworkLibrary`): Ein Chip „Netzwerk“ erscheint nur, wenn es Lesezeichen auf
  Server-Ordner gibt (Opt-in über die Lesezeichen, keine neue Einstellung). Erst ein Tipp darauf liest die Ordner und
  eine Ebene Unterordner (höchstens 2000 Videos), ein Server, der nicht antwortet, wird übersprungen; das Ergebnis gilt
  5 Minuten im Prozess. Ohne Vorschau und Laufzeit (das hieße, jede Datei anzulesen), kein „Videoinfos“ per langem
  Druck. Ein Tipp spielt das Video mit den anderen desselben Ordners (nach Name) als Warteschlange.

## Auf Chromecast und Google TV

- **Bedienung:** Fernseher-Knopf in der Kopfleiste des Players („Auf Fernseher abspielen“), dasselbe Sheet wie bei der
  Musik (`CastChooserSheet` mit `Controller`/`Host`; die Musik nutzt weiter `CastManager`), mit eigenem
  Datenschutz-Satz und ohne den Klangeffekt-Hinweis. Lautsprecher ohne Bildschirm werden abgelehnt.
- **Formate** (`VideoCastFormats.contentType`, getestet): MP4/M4V/MOV als `video/mp4`, WebM, MKV
  (`video/x-matroska`). Alles andere (AVI, WMV, FLV, MPEG, TS, 3GP) meldet vor dem Verbinden „Fernseher können
  AVI-Videos nicht abspielen. MP4, WebM und MKV gehen.“ Lehnt der Fernseher ein Video trotzdem ab (Codec), steht in der
  Fernbedienung „Der Fernseher kann dieses Video nicht abspielen …“.
- **Ablauf** (`VideoCastSession`): Verbindung, Device Auth und Geräte-Pins wie bei der Musik (`CastClient`, dieselben
  `cast_device_pins`), dann Googles Default Media Receiver (Ordos eigener Receiver ist für Musik gestaltet).
  `CastFileServer.serveAll` liefert den Film und seine Untertitel unter je eigenem Zufallspfad, nur an die IP des
  Fernsehers, mit Range und CORS. LOAD (`VideoCastProtocol`, getestet) mit `MovieMediaMetadata`, Startzeit = Stelle
  auf dem Handy, Autoplay wie auf dem Handy.
- **Untertitel:** SRT/VTT neben dem Film (`Film.srt`, `Film.de.srt`, `Film.ger.forced.srt` …), Gerätesprache zuerst,
  höchstens 8 und je 4 MiB. SRT wird beim Senden zu WebVTT (Komma → Punkt, Stunden zweistellig, Positionsangaben und
  ASS-Tags wie `{\an8}` entfernt), Zeichensatz wie im Player (`TextCharset`: BOM, UTF-8, sonst die Codepage zur Sprache der Datei). Die erste Spur ist eingeschaltet.
- **Fernbedienung:** Solange der Film auf dem Fernseher läuft, deckt eine Fläche den Player ab (TalkBack liest nur sie):
  Gerät, Titel, Zustand, Zeit mit `PlayerSeekSlider` (erst halten, dann ziehen), −/+ Sprung (Weite und Symbol wie im
  Player), Abspielen/Pause, Leiser/Lauter in 20 Stufen (keine Regler) mit „Lautstärke 40 %“, die Lautstärketasten
  ebenso, und „Auf diesem Handy weiterschauen“. Im Querformat scrollt sie.
- **Übergabe:** Handy → Fernseher an der aktuellen Stelle, das Handy pausiert. Fernseher → Handy an der Stelle des
  Fernsehers, pausiert, und die Stelle wird für „Weiterschauen“ gespeichert. Läuft der Film auf dem Fernseher zu Ende, schließt Ordo die App dort und zeigt am Handy das Ende („Nochmal ansehen“). Während der Verbindung pausiert das Handy schon, damit die Stelle stimmt. Klappt das Verbinden nicht (Format, Gerät nicht erreichbar, abgebrochen), spielt das Handy an derselben Stelle weiter, wenn es vorher lief, mit dem Grund und „Das Video läuft hier weiter.“ Alles, was Netz oder einen fremden Anbieter berührt (Dateiname einer `content://`-Datei, Formatprüfung, Verbindung, Schließen), läuft nicht auf dem Haupt-Thread. Verbindung weg: „Verbindung zu X verloren.
  Das Video ist hier pausiert.“ Schließt man den Player, endet auch die Wiedergabe auf dem Fernseher (ohne Player gäbe
  es nichts, was sie steuert). WLAN- und Wake-Lock wie bei der Musik.

## Welche KI was gebaut hat

codex: Review-Durchgang (7 Befunde, 6 umgesetzt; offen: `CastFileServer.close()` schließt laufende Verbindungen nicht selbst, Musik-Code, unverändert), `VideoCastFormats` (SRT→WebVTT, Zeichensatz, Untertitel neben dem Film, Content-Type), `VideoBuffering`
(Pufferwahl, Pausen, Prozent, Geschwindigkeit) und deren Tests. Claude: alles andere, Prüfung von codex' Teil.

## Offen / von Hand

`e2e/video-network.sh` (`--write`, `CAST_TV=<Name>`) prüft Knopf, Sheet, Fernbedienung, Pause, Lautstärke und
Rückgabe. Von Hand, weil die Testumgebung keinen SMB/SFTP-Server hat: großer Film per SMB/SFTP (Pille, Rückspringen
ohne Neuladen, WLAN aus/an), der Chip „Netzwerk“, Untertitel auf dem Fernseher, ein MKV mit HEVC auf Chromecast mit
Google TV. Nicht gebaut: Untertitel während des Castens wechseln, nächstes Video der Warteschlange auf dem Fernseher,
Vorschaubilder für Netzvideos.
