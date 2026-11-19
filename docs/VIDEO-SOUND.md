# Ordo Video: Klang

Stand 19.11.2026, Branch `feat/video-sound`. Der Video-Player nutzt die Klang-Engine von Ordo Musik und baut nichts
davon neu: `SoundEffectController` (Equalizer, Bass, Balance über `DynamicsProcessing`/`BassBoost`, Schalten nur in
Stille oder in einer kurzen Absenkung über `TransportFader`, `EffectGate`) und `KlangAudioProcessor` (eine Float-Kette
mit Limiter und TPDF-Dither, knackfreies Ein- und Ausblenden je Stufe über 30 ms). Code: `viewer/video/sound/`.

## Kette

`DownmixProcessor` → `KlangAudioProcessor(Mono, Dialog, Nacht)` → `AvDelayProcessor`, als Media3-`AudioProcessor`s
vor dem AudioTrack (`AudioRenderersFactory`); danach im Audio-Server Equalizer, Bass und Balance.

- **Eigene Einstellungen:** `VideoSoundStore` (SharedPreferences `video_sound`, JSON mit dem `AudioSettings`-Codec der
  Musik für den Klang-Teil). Der Video-EQ ändert den Musik-EQ nicht und umgekehrt.
- **Dialog-Verstärker** (`DialogStage`, Aus/Leicht/Stark): Mitte (L+R)/2 erst sanft komprimiert (Leicht 2:1 ab −24 dB,
  Stark 3:1 ab −30 dB, Soft-Knee 6 dB, Giannoulis/Massberg/Reiss 2012), dann Präsenz-Anhebung bei 2,5 kHz, Q 0,8
  (+4/+7 dB, RBJ-Peaking-EQ). Die Seite (Musik, Effekte, Raum) bleibt unberührt. Bei 5.1/7.1 hebt er zusätzlich den
  Center im Downmix (−3 → 0 → +3 dB).
- **Nachtmodus** (`NightStage`): ein verkoppelter Kompressor auf beiden Kanälen, 4:1 ab −30 dB, Attack 5 ms, Release
  250 ms, +9 dB Make-up; der Limiter der Kette fängt den Rest.
- **Downmix 5.1/7.1 → Stereo** (`DownmixProcessor`, immer an bei 3–8 Kanälen, 16 Bit): ITU-R BS.775, Center −3 dB in
  beide Kanäle, Surrounds −3 dB auf ihre Seite, LFE weggelassen, alles × 1/(1+0,7071); eigener Spitzenbegrenzer mit der
  Decke der Klang-Kette (−1,5 dBFS). Kanalreihenfolge wie Androids Kanalmaske (5.1: FL FR FC LFE BL BR; 6.1: + BC; 7.1: + SL SR). Ein Wechsel der
  Center-Stärke gleitet über einen Puffer.
- **Ton-Synchronisation** (`AvDelayProcessor`, ±1000 ms in 50-ms-Schritten, nur für den offenen Player): ExoPlayers Uhr
  ist die Audio-Uhr. Nach einem Flush fügt der Prozessor N Frames Stille ein (Ton später) oder verwirft N Frames (Ton
  früher). Eine Änderung wirkt per Seek auf die aktuelle Stelle (kurzes Nachladen). Bei Tempo ≠ 1 gilt der Versatz in
  Filmzeit.
- **Tonspur-Blatt** (`VideoAudioTrackSheet`): Sprache (in der App-Sprache), Codec, Kanäle (Stereo, 5.1 …); Spuren ohne
  Decoder heißen „auf diesem Gerät nicht abspielbar“ und sind nicht wählbar. Darunter die Ton-Synchronisation (−/+).
- Bedienung: ⋮ › Klang, ⋮ › Tonspur. Keine Schieberegler (Schalter, Segmente, −/+), 48 dp, Texte Deutsch/Englisch.
- Schnittstelle für später (keine KI im Player): `VideoSoundEngine.settings`/`update()`/`setDelay()`.

## Rechenlast

`VideoSoundChainTest.cpuOfTheWholeChain` (JVM auf belkis-home bzw. dem Build-Rechner, 48 kHz 5.1, alles an: Downmix,
Dialog Stark, Nacht, 100 ms Versatz, 20-ms-Puffer, 60 s Audio): 0,20 % eines Kerns (pop-os, Ryzen 7 5800X, 19.11.2026). Auf einem schwachen ARM-Gerät
(W90) ist die Kette **noch nicht gemessen**: dafür `e2e/video-sound.sh` laufen lassen und währenddessen
`dumpsys cpuinfo` für Ordo lesen; Vergleich: die Musik-Kette mit allem an lag auf dem Emulator bei 2,7–2,8 %.

## Nicht auf Geräten geprüft

Klang mit Ohr (Dialog, Nacht, Downmix über Lautsprecher und Kopfhörer), Knackfreiheit beim Wechsel Leicht↔Stark,
Bass/Balance-Verfügbarkeit je Gerät, Ton-Synchronisation gegen ein Lippen-Testvideo, Rechenlast auf dem W90.
`e2e/video-sound.sh` (WRITES) prüft Öffnen, Schalten, Spurwahl und Versatz.
