# Ordo Video: Klang

Stand 19.11.2026, Branch `feat/video-sound`. Der Video-Player nutzt die Klang-Engine von Ordo Musik und baut nichts
davon neu: `SoundEffectController` (Equalizer, Bass, Balance über `DynamicsProcessing`/`BassBoost`, Schalten nur in
Stille oder in einer kurzen Absenkung über `TransportFader`, `EffectGate`) und `KlangAudioProcessor` (eine Float-Kette
mit Limiter und TPDF-Dither, knackfreies Ein- und Ausblenden je Stufe über 30 ms). Code: `viewer/video/sound/`.

## Kette

`DownmixProcessor` (mit virtuellem Raumklang für Kopfhörer) → `KlangAudioProcessor(Mono, Dialog, Nacht, Leise-Hören,
Kopfhörer-Korrektur)` → `TruePeakLimiter` → `AvDelayProcessor`, als
Media3-`AudioProcessor`s vor dem AudioTrack (`AudioRenderersFactory`); danach im Audio-Server Equalizer, Bass, Balance
und Lautstärke angleichen.

- **Kopfhörer-Paket** (Klang › Kopfhörer, gemeinsam mit Ordo Musik): virtueller Raumklang für 5.1/7.1 (HRIR-Faltung,
  2,7 ms Latenz, nur mit Kopfhörer und ohne System-Spatializer), Leise hören nach ISO 226, AutoEq-Korrektur; siehe
  [KOPFHOERER.md](KOPFHOERER.md).
- **Eigene Einstellungen:** `VideoSoundStore` (SharedPreferences `video_sound`, JSON mit dem `AudioSettings`-Codec der
  Musik für den Klang-Teil). Der Video-EQ ändert den Musik-EQ nicht und umgekehrt.
- **Dialog-Verstärker als Abstand in LU** (Aus / Leicht 10 LU / Stark 15 LU): nicht „Dialog +x dB“, sondern ein
  Zielabstand der Lautheit von Sprache über dem Rest. 10 LU ist der bevorzugte Abstand Sprache über Musik, 15 LU über
  Atmo; Laien wollen im Mittel 4 LU mehr als Profis, daher zwei Stufen [Torcoli 2019]. `DialogSteering` misst Dialog und
  Rest K-gewichtet (BS.1770) über 3 s (Short-term), teilt den fehlenden Abstand halb als Anhebung des Dialogs, halb als
  Absenkung des Rests (Ducking), höchstens 18 dB zusammen, gleitet mit 0,5 s Zeitkonstante; ohne hörbaren Dialog
  (unter −60 dB) halten die Verstärkungen still, damit Pausen nicht pumpen.
  - **5.1/7.1** (`DownmixProcessor`): Dialog = der echte Center-Kanal, Rest = alle übrigen außer LFE, beide so gemessen,
    wie sie im Stereo-Ausgang landen; die Matrix gleitet je Puffer (Clean-Audio-Ansatz über den getrennten Center
    [Shirley 2006, 2015]).
  - **Stereo** (`DialogStage`): Näherung ohne Sprachmodell (Annahme: Sprache liegt meist in der Mitte). Mitte (L+R)/2
    durch ein zweimal angewandtes Bandpass 300–4000 Hz (RBJ, 0 dB und ohne Phasendrehung in der Mitte) = Dialog; Rest =
    Mitte minus Dialog plus Seite. Dialog und Rest ergeben zusammen genau das Original. Einmal angewandt rutschte Bass
    aus der Mitte mit etwa −15 dB in den Dialog und wurde mit angehoben (Test zuerst 6,3 statt 10 LU).
  - Gemessen (`stereoDialogReachesItsTargetDistance`, `surroundDialogReachesItsTargetDistance`, 20 s Mischung, dann
    Verstärkungen gehalten und Sprache und Musik getrennt nach BS.1770 gemessen): Stereo vorher −1,1 LU → 10,50 bzw.
    15,52 LU; 5.1 vorher −0,4 LU → 9,99 bzw. 14,99 LU (Ziel ±1 LU). Ist der Abstand schon groß genug, bleibt alles
    unverändert (`dialogAlreadyFarEnoughIsLeftAlone`).
  - Grenzen: Musik in der Mitte und im Sprachband zählt bei Stereo als Dialog; wird der Ton vom System räumlich
    wiedergegeben (Durchreichen), wirkt der Verstärker nicht.
- **„Besser verstehen“** (ein Tipp, oben im Klang-Blatt): Dialog auf 15 LU plus Nachtmodus. Drei belegte Hebel für
  Schwerhörige sind Sprache-zu-Hintergrund-Abstand, räumliche Trennung und Redundanz, etwa Untertitel [Ward 2019]. Ordo
  setzt den Abstand und weist auf Untertitel hin; für das Einschalten der Untertitel gibt es den Haken
  `VideoSoundEngine.onUnderstandBetter` (die Untertitel-Funktion liegt auf einem anderen Branch).
- **Nachtmodus** (`NightStage`): ein verkoppelter Kompressor auf beiden Kanälen, 4:1 ab −30 dB, Attack 5 ms, Release
  250 ms, +9 dB Make-up; der Limiter der Kette fängt den Rest. An/Aus blendet über 30 ms (`KlangStage`); die Hüllkurve
  startet mit dem Pegel des ersten Frames, so kommt beim Einschalten über lauter Szene kein Make-up-Stoß durch
  (`nightModeSwitchesWithoutACrack`).
- **True-Peak-Limiter** (`TruePeakLimiter`, auch in Ordo Musik nach der Klang-Kette): hält den Ausgang bei höchstens
  −1 dBTP, auch zwischen den Samples. Spitzen per 4-facher Überabtastung mit 64-Tap-Hann-Sinc je Phase (derselbe
  Interpolator wie das Referenz-Messgerät im Test; der erste Stand mit 16 Taps ließ geclipptes Vollband-Rauschen bei
  ~−0,6 dBTP durch). Gerechnet wird nur, solange scharf und wo das Fenster laut genug ist, dass ein Zwischenwert die
  Decke überhaupt erreichen kann (|Zwischenwert| ≤ Fensterspitze · Σ|Kern|). Verstärkung als Minimum über 16 Frames
  Vorschau, über 16 Frames gemittelt (Rampe statt Sprung, ein Sprung macht neue Zwischenspitzen), Erholung 80 ms,
  Erkennungsziel −1,6 dB. Feste Latenz 47 Frames (1 ms bei 48 kHz). Bei Ordo Musik scharf, solange die Klang-Kette den
  Puffer davor bearbeitet hat (auch während eine Stufe ausblendet): ganz ohne Effekte bleibt es bitgenau.
  Gemessen (`TruePeakLimiterTest`, 4×-Referenz mit 64 Taps, Eingang voll ausgesteuert, 19.11.2026): fs/4 bei 45°
  +3,00 → −1,60 dBTP; 0,3/0,45 fs → −1,60; Rechteck +2,08 → −1,60; bandbegrenztes Rauschen +0,83 → −1,59; geclipptes
  bandbegrenztes Rauschen +5,89 → −1,58; geclipptes Vollband-Rauschen +7,13 → −1,52 dBTP.
- **Lautheitsziele nach EBU R 128 s2-2023** (Musik und Video, Schalter „Lautstärke angleichen“ mit zwei Segmenten):
  „Original“ −23 LUFS (Bezugspegel, Empfehlung e) und „Unterwegs“ −16 LUFS (oberes Ende der Übergangsspanne −20 bis
  −16 LUFS; das Gerät darf das Ziel bei wenig Reserve oder lauter Umgebung anheben, j, mit True-Peak-Limiter, k)
  [R 128 s2]; „Aus“ ist der Schalter aus. Die Verstärkung (`LoudnessTargets.gainDb`: ReplayGain-artiger Wert zu −18 LUFS
  plus Zielabstand plus Vorverstärkung) wirkt jetzt in Ordos eigener Kette als Eingangsverstärkung des
  `TruePeakLimiter` (gleitet mit 50 ms), nicht mehr im `DynamicsProcessing` des Audio-Servers: nur so hält derselbe
  Limiter −1 dBTP. Gemessen (`LoudnessTargetTest`, 30 s musikähnliches Material mit Kick, Snare, Bass, Akkorden und
  leiserem Mittelteil, −32,07 LUFS): Original −23,00 LUFS bei −5,32 dBTP, Unterwegs −16,23 LUFS bei −1,59 dBTP (Ziel
  ±0,5 LU, ≤ −1 dBTP). Tech-3341-Fälle 1–5 je ±0,1 LU (`tech3341GatingCases`). Der Limiter rechnet 1 bis 8 Kanäle:
  jeder Kanal wird einzeln auf Zwischenspitzen geprüft, alle teilen eine Verstärkung (die Mischung behält ihr
  Verhältnis). Gemessen (`surroundTargetsAreHeldAndEveryChannelStaysBelowMinus1`, dasselbe Material als 5.1 und 7.1,
  20.11.2026): 5.1 −27,58 → −23,00 / −16,11 LUFS, 7.1 −27,05 → −23,00 / −16,03 LUFS, jeder Kanal ≤ −1,60 dBTP; eine
  Zwischenspitze nur im Center senkt alle Kanäle um denselben Wert ±0,3 dB, ohne Verstärkung bitgenau
  (`surroundIntersamplePeakInOneChannelPullsAllDownTogether`).
  Filme haben selten ReplayGain-Tags. `LoudnessAnalysis` (die
  BS.1770-Messung von Ordo Musik) misst eine Datei auf diesem Gerät einmal im Hintergrund und merkt sich das Ergebnis
  (gleicher Cache wie Musik); für Filme mit bis zu 10 min statt 30 s Rechenzeit, auf eigenem Thread, ein Filmwechsel
  bricht die Messung ab. Nur Dateien auf dem Gerät (`file:`), nie Tresor-Einträge (`ordo-vault:`) oder Netzwerk;
  mit „Orte nicht merken“ bleibt das Ergebnis nur im Speicher des offenen Players (`loudnessPolicy`, getestet). Im Log
  steht kein Dateiname (nur Debug-Builds, nur der Wert). Das Ergebnis geht als Verstärkung zum
  Bezug −18 LUFS in `SoundEffectController`. Netzwerkdateien werden nicht gemessen. Gating geprüft gegen EBU Tech 3341,
  Fälle 1–5 (`tech3341GatingCases`, je ±0,1 LU).
- **Raumklang vom System** (`VideoSpatial`, API 32+): Ist der `Spatializer` verfügbar, eingeschaltet, kann er räumlich
  rendern und meldet `canBeSpatialized` für 5.1 bzw. 7.1 (Medien/Film, 48 kHz), geht diese Spur unverändert an Android;
  sonst mischt Ordo nach BS.775. Neu geprüft bei Zustandswechsel des Spatializers und beim Wechsel des Ausgabegeräts;
  wirksam beim nächsten Formatwechsel (neuer Film oder Spurwechsel), nicht mitten im Film. Das Klang-Blatt sagt, was
  gerade wirklich läuft („Raumklang vom System“ oder „Ordo-Abmischung“), Logcat-Tag `OrdoVideoSound` die Entscheidung. Bei Durchreichen wirken
  Dialog-Verstärker, Nacht und Mono nicht (die Kette rechnet nur Stereo). Darum gibt Ordo den Mehrkanal-Ton nur durch,
  solange keiner der drei an ist; schaltet man einen davon ein, mischt Ordo selbst ab, und zwar sofort: Ist die
  laufende Spur betroffen, wird ihr Ton einmal kurz aus- und wieder eingeschaltet (eine kurze Lücke), damit das Format
  neu eingerichtet wird. Das Klang-Blatt sagt dann „Ordo-Abmischung: Solange Dialog-Verstärker, Nachtmodus oder Mono
  an sind, …“ (`spatialHeldBack`).
- **Lautstärke des Players** (`VolumeMix`): Produkt aus der Absenkung des Effekt-Schalters und der Ausblendung des
  Schlaf-Timers; jeder setzt nur seinen eigenen Faktor, keiner überschreibt den anderen (`VolumeMixTest`). Der
  Schlaf-Timer (Branch feat/video-background, `VideoSleepTimer`) muss dafür `VideoSoundEngine.volumeMix.sleep` setzen
  statt `player.volume`.
- **Downmix 5.1/7.1 → Stereo** (`DownmixProcessor`, immer an bei 3–8 Kanälen, 16 Bit): ITU-R BS.775, Center −3 dB in
  beide Kanäle, Surrounds −3 dB auf ihre Seite, LFE weggelassen, alles × 1/(1+0,7071); eigener Spitzenbegrenzer mit der
  Decke der Klang-Kette (−1,5 dBFS). Kanalreihenfolge wie Androids Kanalmaske (5.1: FL FR FC LFE BL BR; 6.1: + BC; 7.1: + SL SR). Ein Wechsel der
  Center-Stärke gleitet über einen Puffer.
- **Ton-Synchronisation** (`AvDelayProcessor`, ±1000 ms in 50-ms-Schritten, je Film gemerkt): ExoPlayers Uhr
  ist die Audio-Uhr. Nach einem Flush fügt der Prozessor N Frames Stille ein (Ton später) oder verwirft N Frames (Ton
  früher). Eine Änderung wirkt per Seek auf die aktuelle Stelle (kurzes Nachladen). Bei Tempo ≠ 1 gilt der Versatz in
  Filmzeit. Gespeichert in `video_av_delay` unter demselben Schlüssel wie „Weiterschauen“ (Pfad, Größe, Änderungszeit),
  höchstens 300 Einträge; 0 ms löscht den Eintrag; „Orte nicht merken“ liest und speichert nichts; `content:`- und
  Tresor-Einträge nie (`remembersDelay`). Wer schon verschiebt, bevor der gespeicherte Wert geladen ist, behält seinen.
- **Tonspur-Blatt** (`VideoAudioTrackSheet`): Sprache (in der App-Sprache), Codec, Kanäle (Stereo, 5.1 …); Spuren ohne
  Decoder heißen „auf diesem Gerät nicht abspielbar“ und sind nicht wählbar. Darunter die Ton-Synchronisation (−/+).
- Bedienung: ⋮ › Klang, ⋮ › Tonspur. Keine Schieberegler (Schalter, Segmente, −/+), 48 dp, Texte Deutsch/Englisch.
- Schnittstelle für später (keine KI im Player): `VideoSoundEngine.settings`/`update()`/`setDelay()`.

## Signalweg

Eine Signalweg-Anzeige gibt es in Ordo noch nicht (Idee 4 im Qualitäts-Panel). Was sie zeigen soll, liefert
`VideoSoundEngine` schon: `filmLufs` (gemessen), das Ziel (`settings.klang.loudnessTarget`), `dialogDistanceLu` und
`dialogReachedLu`, `systemSpatializes()`. Das Klang-Blatt zeigt Ziel und gemessene Lautheit („Ziel −16 LUFS · dieser
Film −27,3 LUFS“) und den Dialog-Abstand („Stimmen jetzt 10,0 LU über dem Rest (vorher −1,1 LU)“, jede Sekunde neu).

## Quellen

- [R 128 s2] EBU R 128 s2-2023, Loudness in Streaming, Empfehlungen e–l; EBU R 128-2023 (V5): −23 LUFS ±0,5 LU,
  ≤ −1 dBTP.
- [BS.1770] ITU-R BS.1770-5 (11/2023), Lautheit und True Peak; EBU Tech 3341-2023, Testsignale.
- [Torcoli 2019] Torcoli, Freke-Morin, Paulus, Simon, Shirley: Preferred Levels for Background Ducking to Produce
  Esthetically Pleasing Audio for TV with Clear Speech, JAES 67(12), 2019, doi:10.17743/jaes.2019.0052.
- [Ward 2019] Ward, Shirley: Personalization in Object-based Audio for Accessibility: A Review of Advancements for
  Hearing Impaired Listeners, JAES 67(7/8), 2019, doi:10.17743/jaes.2019.0021.
- [Shirley 2006, 2015] Shirley, Kendrick: The Clean Audio Project, Technology and Disability 18(1), 2006,
  doi:10.3233/TAD-2006-18105; Shirley, Oldfield: Clean Audio for TV broadcast, JAES 63(4), 2015,
  doi:10.17743/jaes.2015.0017.
- [BS.775] ITU-R BS.775-4 (12/2022), Abmischung 5.1 → 2.0.
- Quellenprüfung: /home/belkis/ordo-team/WISSENSCHAFT.md, Abschnitt 2.

## Rechenlast

`VideoSoundChainTest.cpuOfTheWholeChain` (JVM auf belkis-home bzw. dem Build-Rechner, 48 kHz 5.1, alles an: Downmix,
Dialog-Steuerung 15 LU im Downmix und Stereo-Dialog, Nacht, True-Peak-Limiter, 100 ms Versatz, 20-ms-Puffer, 60 s
Audio): 0,88–1,15 % eines Kerns (pop-os, Ryzen 7 5800X, 20.11.2026, geteilter Build-Rechner, schwankt je Lauf;
ohne Limiter 0,20 %). Der Limiter allein, Stereo beim Begrenzen: 1,46–2,34 %. Der 64-Tap-Limiter allein, beim
Begrenzen lauten Rauschens, 48 kHz Stereo: 1,48 % inklusive Umwandlung im Test (`TruePeakLimiterTest.cpuOfTheLimiter`). Auf einem schwachen ARM-Gerät
(W90) ist die Kette **noch nicht gemessen**: dafür `e2e/video-sound.sh` laufen lassen und währenddessen
`dumpsys cpuinfo` für Ordo lesen; Vergleich: die Musik-Kette mit allem an lag auf dem Emulator bei 2,7–2,8 %.

## Nicht auf Geräten geprüft

Klang mit Ohr (Dialog, Nacht, Downmix über Lautsprecher und Kopfhörer), Bass/Balance-Verfügbarkeit je Gerät,
Spatializer-Übergabe (Pixel/Galaxy mit Raumklang an, Kopfhörer), Lautheitsmessung eines ganzen Films auf dem Gerät
(Dauer, Akku), Ton-Synchronisation gegen ein Lippen-Testvideo, Rechenlast auf dem W90.
`e2e/video-sound.sh` (WRITES) prüft Öffnen, Schalten, Spurwahl und Versatz.
