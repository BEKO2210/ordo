# Ordo Video: Klang

Stand 19.11.2026, Branch `feat/video-sound`. Der Video-Player nutzt die Klang-Engine von Ordo Musik und baut nichts
davon neu: `SoundEffectController` (Equalizer, Bass, Balance über `DynamicsProcessing`/`BassBoost`, Schalten nur in
Stille oder in einer kurzen Absenkung über `TransportFader`, `EffectGate`) und `KlangAudioProcessor` (eine Float-Kette
mit Limiter und TPDF-Dither, knackfreies Ein- und Ausblenden je Stufe über 30 ms). Code: `viewer/video/sound/`.

## Kette

`DownmixProcessor` → `KlangAudioProcessor(Mono, Dialog, Nacht)` → `TruePeakLimiter` → `AvDelayProcessor`, als
Media3-`AudioProcessor`s vor dem AudioTrack (`AudioRenderersFactory`); danach im Audio-Server Equalizer, Bass, Balance
und Lautstärke angleichen.

- **Eigene Einstellungen:** `VideoSoundStore` (SharedPreferences `video_sound`, JSON mit dem `AudioSettings`-Codec der
  Musik für den Klang-Teil). Der Video-EQ ändert den Musik-EQ nicht und umgekehrt.
- **Dialog-Verstärker** (`DialogStage`, Aus/Leicht/Stark): Mitte (L+R)/2 erst sanft komprimiert (Leicht 2:1 ab −24 dB,
  Stark 3:1 ab −30 dB, Soft-Knee 6 dB, Giannoulis/Massberg/Reiss 2012), dann Präsenz-Anhebung bei 2,5 kHz, Q 0,8
  (+4/+7 dB, RBJ-Peaking-EQ). Die Seite (Musik, Effekte, Raum) bleibt unberührt. Bei 5.1/7.1 hebt er zusätzlich den
  Center im Downmix (−3 → 0 → +3 dB). Leicht ↔ Stark gleitet: Präsenz, Schwelle, Ratio und Make-up laufen mit 30 ms
  Zeitkonstante zum neuen Wert, das Filter wird alle 32 Frames neu berechnet (`dialogLevelGlidesWithoutACrack`).
- **Nachtmodus** (`NightStage`): ein verkoppelter Kompressor auf beiden Kanälen, 4:1 ab −30 dB, Attack 5 ms, Release
  250 ms, +9 dB Make-up; der Limiter der Kette fängt den Rest. An/Aus blendet über 30 ms (`KlangStage`); die Hüllkurve
  startet mit dem Pegel des ersten Frames, so kommt beim Einschalten über lauter Szene kein Make-up-Stoß durch
  (`nightModeSwitchesWithoutACrack`).
- **True-Peak-Limiter** (`TruePeakLimiter`, auch in Ordo Musik nach der Klang-Kette): hält den Ausgang bei höchstens
  −1 dBTP, auch zwischen den Samples. Spitzen per 4-facher Überabtastung (16-Tap-Hann-Sinc je Phase), Verstärkung als
  Minimum über 16 Frames Vorschau, über 16 Frames gemittelt (Rampe statt Sprung, ein Sprung macht neue
  Zwischenspitzen), Erholung 80 ms. Ziel der Erkennung −1,6 dB, weil der kurze Interpolator bandbegrenztes und
  geclipptes Material um bis zu ~0,5 dB unterschätzt (Python-Prototyp). Feste Latenz 23 Frames (0,5 ms bei 48 kHz).
  Bei Ordo Musik nur „scharf“, solange eine eigene Stufe läuft: ganz ohne Effekte bleibt es bitgenau.
  Gemessen (`TruePeakLimiterTest`, 4×-Referenz mit 64 Taps, Eingang voll ausgesteuert): fs/4 bei 45° +3,00 → −1,61 dBTP;
  0,3/0,45 fs → −1,60; Rechteck +2,08 → −1,52; bandbegrenztes Rauschen +0,83 → −1,52; geclipptes bandbegrenztes
  Rauschen +5,89 → **−1,03 dBTP** (knappster Fall).
- **Lautstärke angleichen** (Schalter im Klang-Blatt): Filme haben selten ReplayGain-Tags. `LoudnessAnalysis` (die
  BS.1770-Messung von Ordo Musik) misst eine Datei auf diesem Gerät einmal im Hintergrund und merkt sich das Ergebnis
  (gleicher Cache wie Musik); für Filme mit bis zu 10 min statt 30 s Rechenzeit. Das Ergebnis geht als Verstärkung zum
  Bezug −18 LUFS in `SoundEffectController`. Netzwerkdateien werden nicht gemessen. Gating geprüft gegen EBU Tech 3341,
  Fälle 1–5 (`tech3341GatingCases`, je ±0,1 LU).
- **Raumklang vom System** (`VideoSpatial`, API 32+): Ist der `Spatializer` verfügbar, eingeschaltet, kann er räumlich
  rendern und meldet `canBeSpatialized` für 5.1 bzw. 7.1 (Medien/Film, 48 kHz), geht diese Spur unverändert an Android;
  sonst mischt Ordo nach BS.775. Neu geprüft bei Zustandswechsel des Spatializers und beim Wechsel des Ausgabegeräts;
  wirksam beim nächsten Formatwechsel (neuer Film oder Spurwechsel), nicht mitten im Film. Das Klang-Blatt sagt
  „Raumklang vom System“ oder „Ordo-Abmischung“, Logcat-Tag `OrdoVideoSound` die Entscheidung. Bei Durchreichen wirken
  Dialog-Verstärker, Nacht und Mono nicht (die Kette rechnet nur Stereo).
- **Downmix 5.1/7.1 → Stereo** (`DownmixProcessor`, immer an bei 3–8 Kanälen, 16 Bit): ITU-R BS.775, Center −3 dB in
  beide Kanäle, Surrounds −3 dB auf ihre Seite, LFE weggelassen, alles × 1/(1+0,7071); eigener Spitzenbegrenzer mit der
  Decke der Klang-Kette (−1,5 dBFS). Kanalreihenfolge wie Androids Kanalmaske (5.1: FL FR FC LFE BL BR; 6.1: + BC; 7.1: + SL SR). Ein Wechsel der
  Center-Stärke gleitet über einen Puffer.
- **Ton-Synchronisation** (`AvDelayProcessor`, ±1000 ms in 50-ms-Schritten, je Film gemerkt): ExoPlayers Uhr
  ist die Audio-Uhr. Nach einem Flush fügt der Prozessor N Frames Stille ein (Ton später) oder verwirft N Frames (Ton
  früher). Eine Änderung wirkt per Seek auf die aktuelle Stelle (kurzes Nachladen). Bei Tempo ≠ 1 gilt der Versatz in
  Filmzeit. Gespeichert in `video_av_delay` unter demselben Schlüssel wie „Weiterschauen“ (Pfad, Größe, Änderungszeit),
  höchstens 300 Einträge; 0 ms löscht den Eintrag; „Orte nicht merken“ speichert nichts.
- **Tonspur-Blatt** (`VideoAudioTrackSheet`): Sprache (in der App-Sprache), Codec, Kanäle (Stereo, 5.1 …); Spuren ohne
  Decoder heißen „auf diesem Gerät nicht abspielbar“ und sind nicht wählbar. Darunter die Ton-Synchronisation (−/+).
- Bedienung: ⋮ › Klang, ⋮ › Tonspur. Keine Schieberegler (Schalter, Segmente, −/+), 48 dp, Texte Deutsch/Englisch.
- Schnittstelle für später (keine KI im Player): `VideoSoundEngine.settings`/`update()`/`setDelay()`.

## Rechenlast

`VideoSoundChainTest.cpuOfTheWholeChain` (JVM auf belkis-home bzw. dem Build-Rechner, 48 kHz 5.1, alles an: Downmix,
Dialog Stark, Nacht, True-Peak-Limiter, 100 ms Versatz, 20-ms-Puffer, 60 s Audio): 0,49 % eines Kerns (pop-os,
Ryzen 7 5800X, 19.11.2026; ohne Limiter 0,20 %). Der Limiter allein, beim Begrenzen, 48 kHz Stereo: 0,55 % inklusive
Umwandlung im Test (`TruePeakLimiterTest.cpuOfTheLimiter`). Auf einem schwachen ARM-Gerät
(W90) ist die Kette **noch nicht gemessen**: dafür `e2e/video-sound.sh` laufen lassen und währenddessen
`dumpsys cpuinfo` für Ordo lesen; Vergleich: die Musik-Kette mit allem an lag auf dem Emulator bei 2,7–2,8 %.

## Nicht auf Geräten geprüft

Klang mit Ohr (Dialog, Nacht, Downmix über Lautsprecher und Kopfhörer), Bass/Balance-Verfügbarkeit je Gerät,
Spatializer-Übergabe (Pixel/Galaxy mit Raumklang an, Kopfhörer), Lautheitsmessung eines ganzen Films auf dem Gerät
(Dauer, Akku), Ton-Synchronisation gegen ein Lippen-Testvideo, Rechenlast auf dem W90.
`e2e/video-sound.sh` (WRITES) prüft Öffnen, Schalten, Spurwahl und Versatz.
