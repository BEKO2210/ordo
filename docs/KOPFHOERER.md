# Kopfhörer-Paket (Ordo Musik und Ordo Video)

Stand 20.11.2026, Branch `feat/headphones`. Drei Stufen ohne KI/ML in der gemeinsamen Klang-Kette, Code in
`player/headphones/`, Bedienung im Blatt **Kopfhörer** (Klang › Kopfhörer, in Musik und Video gleich). Keine
Schieberegler: Schalter, drei gleich breite Segmente, eine Suchliste mit 48-dp-Zeilen. Alle Stufen sind ab Werk aus.

## 1. Virtueller Raumklang (5.1/7.1)

- Jeder Lautsprecherkanal wird mit dem Kopf-Impulsantwort-Paar (HRIR) seiner Richtung gefaltet, beide Ohren summiert
  (`Binaural`, `PartitionedConvolver`). Winkel: vorn ±30°, Mitte 0°, Surround ±110° (BS.775), bei 7.1 hinten ±150° und
  seitlich ±90°, 6.1 hinten Mitte 180°; LFE bleibt weg wie im BS.775-Downmix.
- **Daten:** SADIE II, Person D1 (Kunstkopf Neumann KU100), 48 kHz, 256 Taps, diffusfeld-entzerrt. Lizenz
  **Apache-2.0**, an der Quelle geprüft (Zenodo-Datensatz 12092466 `license: apache2.0`, SOFA-Attribut `License`:
  „Copyright 2018, University of York, Licensed under the Apache License, Version 2.0“); GPL-3-verträglich, Hinweis in
  `licenses.xml`. Nur die 10 benötigten Richtungen (Höhe 0°) sind eingebettet: 20 480 Bytes
  (`hrir_sadie2_d1_48k.bin`, erzeugt mit `tools/headphones/extract_hrir.py`). Andere Abtastraten: einmal pro Format
  per gefenstertem Sinc umgerechnet (`Binaural.resample`, Pegel bei 100 Hz/1 kHz/5 kHz auf ±0,3 dB gleich).
- **Faltung:** gleichmäßig partitioniertes Overlap-Save (UPOLS) mit Frequenzbereichs-Verzögerungslinie, Block 128,
  FFT 256, 2 Partitionen je HRIR. **Latenz: 128 Frames = 2,7 ms bei 48 kHz (2,9 ms bei 44,1 kHz).** Beim Umschalten
  springt die Ton-Uhr deshalb um 2,7 ms, das liegt weit unter jeder wahrnehmbaren Lippen-Versetzung.
- **Wann:** nur mit Kopfhörer (`HeadphoneOutput`, `AudioDeviceInfo`: kabelgebunden, USB, Bluetooth A2DP/SCO/LE,
  Hörgeräte) und nur, wenn Androids Spatializer diese Kanäle nicht selbst rendert (`VideoSpatial`: dann gehen sie wie
  bisher unverändert an das System). Im Video wirkt der Schalter sofort, in Musik ab dem nächsten Titel (Musik-
  Mehrkanal geht ohne Raumklang wie bisher unverändert an Android; nur für Raumklang läuft sie durch Ordos Downmix).
- **Knackfrei:** An/Aus und Kopfhörer rein/raus blenden in 30 ms zwischen Matrix und Faltung über; aus rechnet die
  Faltung gar nicht, der Downmix bleibt bitgleich zu vorher. Der Dialog-Verstärker (LU-Abstand) wirkt auch hier: die
  Center- und Rest-Verstärkungen greifen vor der Faltung.
- **Pegel:** `Binaural.GAIN = 0,475`, so dass rosa Rauschen auf allen Kanälen gleich laut ist wie im BS.775-Downmix
  (numpy: 0,480 für 5.1, 0,471 für 7.1, `tools/headphones/binaural_gain.py`).
- **Grenze:** Ein Kunstkopf statt der eigenen Ohren; Vorn/Hinten-Vertauschungen sind mit fremden HRTFs häufig
  [Wenzel 1993]. Hörer bevorzugen fremde Sätze aber oft gegenüber den eigenen [Armstrong 2018]. Eine Auswahl aus
  mehreren Sätzen (WISSENSCHAFT §2.2.5) ist noch nicht gebaut.

## 2. Leise hören mit vollem Klang (ISO 226)

- Ersetzt die frühere Näherung über die Equalizer-Bänder (`LoudnessCompensation`, gelöscht): jetzt zwei Shelving-
  Filter in Ordos eigener Kette vor dem True-Peak-Limiter, in Musik **und** Video (`QuietLoudnessStage`).
- **Kurven:** ISO 226:2003, Tabelle 1 und Formel 1 (die Fassung 2023 ist nicht zugänglich, iso.org gesperrt); dieselben
  Parameter stehen mit Herleitung in [Suzuki 2004]. Korrektur für Hörpegel L = (L-phon-Kurve − L) − (80-phon-Kurve −
  80), also 0 bei 1 kHz und 0 ab 80 phon.
- **Filter:** Low-Shelf 125 Hz (Q 0,5) und High-Shelf 12 kHz (Q 0,707), Verstärkungen per kleinster Quadrate gegen
  die Korrektur von 31,5 Hz bis 12,5 kHz gefittet, gedeckelt bei +10 dB (Bass) und +6 dB (Höhen). Bis 60 phon (dort
  greift die Kappe noch nicht) folgt der Filtergang der Kurvendifferenz auf **±1,5 dB** (`twoShelvesFollowTheContour…`).
  Gemessen durch die Kette bei 60 phon: Anhebung 31,5 Hz gegen 1 kHz = Rechnung ±0,3 dB.
- **Hörpegel:** Medienlautstärke in dB (ab API 28 `AudioManager.getStreamVolumeDb` für das aktuelle Ausgabegerät,
  sonst Androids Referenzkurve `DEFAULT_MEDIA_VOLUME_CURVE` 1/−58, 20/−40, 60/−17, 100/0 dB aus AOSP
  `default_volume_tables.xml`) + angenommene **100 dB SPL bei voller Lautstärke** (Annahme, EN 50332 nicht gelesen) +
  Kalibrierung **Leiser / Normal / Lauter = −10 / 0 / +10 dB**. Der echte Schalldruck hängt vom Kopfhörer ab; die
  Kalibrierung ist dafür da.
- **Knackfrei / bitgenau:** Verstärkungen gleiten (alle 32 Frames ein Schritt mit 15 ms Zeitkonstante); ab 80 phon ist
  die Stufe aus statt flach, die Kette kopiert die Musik dann bitgenau.

## 3. Kopfhörer-Korrektur (AutoEq)

- **Daten:** AutoEq von Jaakko Pasanen, **MIT-Lizenz** (GitHub-API `license.spdx_id = MIT`, LICENSE „Copyright (c)
  2018-2022 Jaakko Pasanen“); das Repository nennt keine abweichende Lizenz für `results/`. Messquellen (oratory1990,
  crinacle, Rtings …) stehen je Modell in der Liste. Hinweis in `licenses.xml`.
- **Auswahl:** die 500 Modelle, die die meisten unabhängigen Quellen in `results/INDEX.md` gemessen haben (Annahme:
  oft gemessen = verbreitet); je Modell Quelle oratory1990 > Rtings (B&K 5128) > crinacle (GRAS) > Rtings > crinacle >
  andere. Jede Voreinstellung: Vorverstärkung, 1 Low-Shelf, 1 High-Shelf, 8 Peaking-Filter. **99 369 Bytes JSON**
  (`autoeq_presets.json`, gzip 32 897), gebaut mit `tools/headphones/pick.py`, `fetch.py`, `build_autoeq.py`.
- **Anwendung:** Vorverstärkung plus 10 RBJ-Biquads (doppelte Genauigkeit) als letzte Stufe vor dem Limiter
  (`HeadphoneEqStage`), nur mit Kopfhörer. Gemessen: Pegel bei 1 kHz = Vorverstärkung + Summe der Filtergänge ±0,1 dB.
  Ein Modellwechsel blendet in 30 ms aus, tauscht die Filter und blendet wieder ein.
- **Suche:** ohne Groß/Klein, Akzente und Satzzeichen, jedes Wort muss vorkommen, auch zusammengeschrieben („hd650“
  findet „Sennheiser HD 650“); höchstens 30 Treffer werden gezeigt.

## Rechenlast und Größe

JVM auf pop-os (Ryzen 7 5800X, geteilter Build-Rechner), 48 kHz, 60 s Audio in 20-ms-Puffern, 20.11.2026; zwei Läufe:

| Messung (Test) | Anteil eines Kerns |
|---|---|
| `PartitionedConvolver` allein, 6 Eingänge × 256 Taps, Block 128 (`cpuOfTheConvolver`) | 0,88–0,89 % |
| Downmix 5.1 mit virtuellem Raumklang (`cpuOfVirtualSurround`) | 0,97–0,99 % |
| Downmix 7.1 mit virtuellem Raumklang | 1,20–1,24 % |

Zum Vergleich: die ganze Video-Kette ohne Raumklang, alles an, 1,14 % (`VideoSoundChainTest.cpuOfTheWholeChain`, gleicher
Lauf). Das Ziel aus WISSENSCHAFT §2.2.5 (≤ 5 % auf einem Mittelklasse-Kern, Latenz ≤ 20 ms) ist auf dem JVM-Rechner mit
weitem Abstand erfüllt; ARM-Geräte sind **nicht gemessen**.

Pegel (`binauralLevelMatchesTheDownmix`, rosa Rauschen je Kanal, 10 s, BS.1770): 5.1 Downmix −15,32 / Raumklang
−15,39 LUFS, 7.1 −14,36 / −14,26 LUFS (Ziel ±1 LU).

APK (`:app:assembleBeta`, R8, 20.11.2026): 21 017 593 → 21 087 298 Bytes, **+69 705 Bytes (+68 KB)**; davon die
Daten 99 369 + 20 480 Bytes, im APK komprimiert (Java-Ressourcen mit absolutem Pfad, unter R8 vorhanden).

## Nicht auf Geräten geprüft

Klang mit Ohr (Ortung, Vorn/Hinten, Pegel), `getStreamVolumeDb` je Gerät und bei Bluetooth-Absolutlautstärke,
Spatializer-Weiche mit Raumklang-Handys, CPU auf schwachem ARM (W90). Kein Gerät wurde angefasst.

## Bekannte Grenzen (aus der codex-Prüfung, bewusst offen)

- Während der 30-ms-Überblendung Matrix ↔ Raumklang liegen beide Wege 2,7 ms auseinander (kurze Kammfilterung, kein
  Sprung; `binauralOffIsBitExactAndSwitchingCrossFades` misst keinen Knack). Den Matrix-Weg dauerhaft zu verzögern
  hätte „aus = bitgleich wie vorher“ gebrochen.
- Ein Modellwechsel der Korrektur und der erste Raumklang-Puffer eines Formats legen ihre Filter auf dem Audio-Thread
  an (einmal je Wechsel, < 1 ms, nicht gemessen auf ARM).
- „Kopfhörer verbunden“ heißt wie beim Crossfeed: ein Kopfhörer ist angeschlossen, nicht der tatsächlich geroutete
  Ausgang. Die Spatializer-Weiche fragt auch für Musik mit Film-Attributen.

## Quellen

- [Armstrong 2018] Armstrong, Thresh, Murphy, Kearney: A Perceptual Evaluation of Individual and Non-Individual HRTFs:
  A Case Study of the SADIE II Database, Applied Sciences 8(11), 2018, doi:10.3390/app8112029.
- [Wenzel 1993] Wenzel, Arruda, Kistler, Wightman: Localization using nonindividualized head-related transfer
  functions, JASA 94(1), 1993, doi:10.1121/1.407089.
- [Suzuki 2004] Suzuki, Takeshima: Equal-loudness-level contours for pure tones, JASA 116(2), 2004,
  doi:10.1121/1.1763601; ISO 226:2003, Tabelle 1.
- [BS.775] ITU-R BS.775-4 (12/2022).
- RBJ Audio EQ Cookbook, https://www.w3.org/TR/audio-eq-cookbook/
- AOSP `frameworks/av/services/audiopolicy/config/default_volume_tables.xml` (DEFAULT_MEDIA_VOLUME_CURVE).
- Quellenprüfung: /home/belkis/ordo-team/WISSENSCHAFT.md §2.
