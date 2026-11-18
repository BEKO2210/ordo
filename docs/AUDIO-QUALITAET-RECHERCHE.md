# Audio-Qualität für Ordo – Recherche (Stand 17.11.2026)

Auftrag: „bester Player der Welt“, „Upscaling wie Raytracing, nur für Audio“ und kein Knacken beim Drücken von Tasten.
Grundlage ist der Code im Worktree `agent-af11264b4f653bd39` (Commit `26b433c4`), `player/*.kt`. Media3 wurde am Tag
**1.11.1** geprüft (`8c6678b6`, per Sparse-Clone gelesen). AOSP stammt aus `refs/heads/main` von heute, Zeilennummern
können sich verschieben. „Annahme“ markiert Aussagen ohne Primärbeleg oder Messung.

Kurzfassung: Das Knacken lässt sich fast vollständig beheben, und das bringt am meisten. „Upscaling“ kann nichts
wiederherstellen. Möglich ist nur eine vorsichtige, abschaltbare Höhen-Ergänzung für stark komprimierte Dateien.
Hi-Res/Bit-perfect und Effekte schließen sich in Media3 1.11.1 gegenseitig aus.

---

## 1. Klicks und Knacken

### 1.1 Was Plattform und Media3 schon tun (an der Quelle geprüft)

| Ereignis | Verhalten | Beleg |
|---|---|---|
| **Pause** | Media3 ruft nur `AudioTrack.pause()` auf und blendet nicht selbst aus. Im normalen Mixer-Pfad setzt AudioFlinger die Lautstärke auf 0 und rampt **über eine Mixer-Periode** (`RAMP_VOLUME` → `mFrameCount`). Bei **FastTracks** heißt es dagegen „ramp down is not yet implemented“, und **Direct/Offload-Ausgänge** (Hi-Res, USB) haben keine Software-Rampe | `DefaultAudioSink.pause()` L1580–1585, `AudioTrackAudioOutput.pause()` L232–237 ([1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackAudioOutput.java)); [Threads.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/audioflinger/Threads.cpp) `MixerThread::prepareTracks_l` (`isPausing()` → Lautstärke 0, `RAMP_VOLUME`), FastTrack-Zweig „ramp down is not yet implemented“, `DirectOutputThread::prepareTracks_l` (nur `setPaused()`/HW-Pause); [AudioMixerBase.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libaudioprocessing/AudioMixerBase.cpp) L451–457 |
| **Play nach Flush/Seek** | Media3 rampt die ersten **20 ms** nach jedem neuen AudioTrack linear hoch | `AUDIO_OUTPUT_VOLUME_RAMP_TIME_MS = 20`, `maybeRampUpVolume()` L144–145, L1250, L1937–1953 ([DefaultAudioSink 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java)) |
| **Seek** | Bei jedem Flush wird der AudioTrack verworfen: erst `pause()`, dann `flush()/release()` **20 ms verzögert**, „to ensure the audio system can completely ramp down“. Release Notes 1.5.0: „Ensure smooth volume ramp down when seeking“, „Fix pop sounds that may occur during seeks“ | `AudioTrackAudioOutput.release()` L309–320 und `releaseAudioTrackAsync` L516–520; [RELEASENOTES 1.11.1](https://github.com/androidx/media/blob/1.11.1/RELEASENOTES.md) (Abschnitt 1.5.0) |
| **`player.volume`** | Läuft über `MSG_SET_VOLUME` → `AudioSink.setVolume` → `AudioTrack.setVolume`. Der Mixer rampt jede Änderung über eine Periode (`RAMP_VOLUME`, weil `cblk->mServer != 0`) | `MediaCodecAudioRenderer` L1026–1027; Threads.cpp (s. o.) |
| **Titelwechsel (gapless, gleiches Format)** | Kein Flush, Prozessoren behalten ihren Zustand, also kein Klick durch Ordo | `DefaultAudioSink.configure()` L746–790 |
| **Volume-API in Media3** | Es gibt nur `setVolume`, `mute()` und `unmute()` (1.9.0), **keine Rampen-API**. `GainProcessor` + `DefaultGainProvider` (`FADE_IN/OUT_LINEAR`, `…_EQUAL_POWER`, `@UnstableApi`) arbeiten mit **festen Stream-Positionen**, nicht interaktiv | [Player.java](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/Player.java) L3311–3340; [GainProcessor](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/audio/GainProcessor.java), [DefaultGainProvider](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/audio/DefaultGainProvider.java) |
| **Fade per AudioProcessor bei Pause?** | **Funktioniert nicht.** Prozessoren laufen dem Ohr um den AudioTrack-Puffer voraus, bei PCM 250–750 ms (Standard 500 ms). Ein Media3-Mitarbeiter bestätigt das: eine Processor-Ausblendung geht nur am echten Titelende | `DefaultAudioTrackBufferSizeProvider` L46–56; [androidx/media#2](https://github.com/androidx/media/issues/2) (Kommentare 26.06. und 22.07.2024) |

### 1.2 Woher Ordos Klicks kommen (Code-Befund)

1. **Effekt ein/aus = harter Schnitt.** `StereoEffectProcessor.queueInput` springt von `output.put(input)` direkt auf
   `processFrame`. Crossfeed hat eigene Filter und Gain, Breite verändert das Seitensignal. Am Umschaltpunkt entsteht ein
   Signalsprung, also ein Klick. Beim Ausschalten bricht der Hall-Schweif ab.
2. **Hall-Trockenpegel springt** beim Einschalten von 1 auf `1 − mix/2`, bei 25 % sind das −1,2 dB in einem Sample
   (`ReverbAudioProcessor.prepare`).
3. **Parameter springen pro Puffer.** `prepare()` liest Stufe, Breite und Mix einmal je Puffer, ohne Glättung. Das
   erzeugt bei Stufenwechseln Zipper-Geräusche und Klicks.
4. **DynamicsProcessing wird ein- und ausgeschaltet** (`effect.enabled = eq || gain || balance || clarity`). Beim
   Abschalten läuft das Modul in den Zustand STOPPED, danach greift `copyInputToOutput()`. Das ist ein harter Wechsel
   **plus Latenzsprung**, denn DP arbeitet blockweise per FFT ([Effects.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/audioflinger/Effects.cpp)
   `STOPPING/STOPPED`). Der Block ist eine 2er-Potenz ≥ `10 ms × fs` (Standard `CONFIG_PREFERRED_FRAME_DURATION_MS = 10`),
   bei 48 kHz also 512, mit Overlap = Block/2
   ([DynamicsProcessing.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/audiofx/DynamicsProcessing.java) L276,
   [EffectDynamicsProcessing.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libeffects/dynamicsproc/EffectDynamicsProcessing.cpp) `DP_configureVariant`).
5. **EQ-Bandwechsel sind schon fast weich.** DP rechnet mit Fenster und Overlap-Add (sqrt-Hann-Flat-Top, Overlap 256 bei
   48 kHz, also ≈ 5 ms Überblendung, [DPFrequency.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libeffects/dynamicsproc/dsp/DPFrequency.cpp)
   `configure`). Große Sprünge in einem Schritt (z. B. ±12 dB) können trotzdem „ploppen“ (Annahme, nicht gemessen).
6. **Pause auf Direct-Pfaden** (USB-DAC, Hi-Res-Ausgang) und manchen BT-Pfaden: Die Plattform rampt dort nicht (s. 1.1).

### 1.3 Standardlösungen (für Ordo konkret)

- **Pause/Play-Fade auf Player-Ebene:** In der vorhandenen `sessionPlayer`-`ForwardingPlayer` (`AudioPlaybackService`
  L213) `pause()`, `play()` und `setPlayWhenReady()` überschreiben. Beim Pausieren einen `fadeVolume`-Faktor in
  ~12 Schritten über **120 ms** (Kosinuskurve) auf 0 fahren, erst dann `player.pause()` aufrufen und den Faktor
  zurücksetzen. Beim Abspielen mit Faktor 0 starten und über **80 ms** hochfahren. Der Faktor wird in das vorhandene
  `updatePlayerVolume()` multipliziert (`sleepVolume * focusVolume * fadeVolume`). Der Mixer glättet jeden Schritt
  linear über eine Periode, so entsteht eine stückweise lineare, klickfreie Kurve.
  Ausnahmen: **Kopfhörer abgezogen** (`noisyReceiver`) pausiert sofort, damit der Lautsprecher nicht 120 ms lang laut
  weiterspielt. Fokusverlust und Sleep-Timer haben schon eigene Rampen.
  Die Alternative mit Plattform-Präzision wäre `AudioTrack.createVolumeShaper` (API 26,
  [VolumeShaper](https://developer.android.com/reference/android/media/VolumeShaper)). Den AudioTrack erreicht man in
  1.11.1 über `DefaultAudioSink.Builder.setAudioOutputProvider(ForwardingAudioOutputProvider(...))` →
  `AudioTrackAudioOutput.getAudioTrack()` (L178, `@UnstableApi`). Das ist mehr Code und erst nötig, wenn die
  Volume-Rampe messbar stuft.
- **Effekt ein/aus = Überblendung trocken↔nass:** Der Prozessor rechnet nach dem Einschalten weiter und blendet mit
  `mix` von 0→1 über **30 ms** linear ein, beim Ausschalten 1→0. Erst wenn `mix == 0` erreicht ist, schaltet er auf die
  Kopie um. Bei korreliertem Signal (Crossfeed, Breite, Wärme gegen das Original) passt eine **gleiche Amplitude**
  (linear), beim unkorrelierten Hall-Anteil **gleiche Leistung**. So beschreibt es auch Media3 in `DefaultGainProvider`
  („Summing … returns a constant gain/power of 1“). Hall beim Ausschalten: Eingang auf 0 setzen und den Schweif
  ≤ 300 ms ausklingen lassen, oder den Nass-Anteil in 80 ms ausblenden.
- **Parameter-Glättung je Sample:** Einpolige Glättung `p += (ziel − p) · k` mit `k = 1 − exp(−1/(τ·fs))`, τ ≈ 10–20 ms,
  für Breite, Mix, Trockenpegel und Wärme-Drive. Beim Crossfeed-Stufenwechsel die alte und neue Filterbank 30 ms
  parallel rechnen und überblenden, weil die Koeffizienten nicht sinnvoll interpoliert werden können.
- **DP nie abschalten, solange gespielt wird:** Nach dem Anlegen dauerhaft `enabled = true` lassen. „Aus“ heißt dann
  Bänder auf 0 dB, MBC-Bänder `enabled=false`, Input-Gain 0. Große EQ-Sprünge in Schritten von ≤ 2 dB alle 20 ms
  senden.

### 1.4 Klicks objektiv messen (JVM-Unit-Test, ohne Gerät)

Wie `StereoEffectProcessorsTest`: Prozessor direkt füttern. Ein Sinus mit 1 kHz und Amplitude A = 0,5 bei 48 kHz,
Umschalten mitten im Puffer:

```kotlin
// Stationäres Maximum des Sample-Deltas eines Sinus: 2·A·sin(π·f/fs) ≈ 0,0654
fun maxDelta(x: FloatArray, from: Int, to: Int) = (from + 1 until to).maxOf { abs(x[it] - x[it - 1]) }
val steady = maxDelta(out, 0, toggleAt - 480)
val atToggle = maxDelta(out, toggleAt - 480, toggleAt + 480)          // ±10 ms
assertTrue(atToggle <= steady * 1.2f)                                // Klick-Kriterium
// Zweites Kriterium: HF-Energie (2. Differenz) in 5-ms-Fenstern höchstens +6 dB über dem Median.
```

Dazu kommen Fälle: ein/aus/ein schnell hintereinander, Stufe 1→3, Breite 100→160 und Hall-Mix 0→50. Für DP und Pause
ist ein Gerät nötig: Aufnahme am Analog- oder USB-Ausgang mit einem Interface, dann dasselbe Delta-Kriterium.
`Visualizer` ist mit 8 Bit zu grob.

---

## 2. „Upscaling“ / Bandbreitenerweiterung

### 2.1 Was die Hersteller selbst sagen (nur Funktionen)

- **Samsung**: „Your phone's audio can sound even better with headphones when you use the UHQ upscaler features … UHQ
  stands for Ultra High Quality“, nur mit kompatiblen Kopfhörern, z. B. Galaxy Buds3
  ([Samsung US](https://www.samsung.com/us/support/answer/ANS00080318/)). **Ein Verfahren beschreibt Samsung nicht.** Die
  Angabe „Bit/Bandbreite“ in `KLANG-EFFEKTE-RECHERCHE.md` §5 steht heute nicht auf dieser Seite.
- **Sony DSEE HX**: „complements the high frequency range of compressed sound sources such as CD … or MP3 … converts to
  … maximum 96 kHz/24 bit … by raising the sampling frequency and the bit rate“
  ([Sony Help Guide](https://helpguide.sony.net/mdr/hpc/v1/en/contents/TP0001548872.html)).
- **Sony DSEE Extreme**: „uses AI technology to reproduce the frequency responses of the original sound source lost
  during compression“. Bei Einstellung [Auto] sinkt die Akkulaufzeit, und während Telefonaten ist die Funktion aus
  ([Sony Help Guide](https://helpguide.sony.net/mdr/2984/v1/en/contents/TP1001861254.html)).

### 2.2 Verfahren im Vergleich

| Verfahren | Prinzip | Echtzeit im Media3-Prozessor? | Lizenz/Patente |
|---|---|---|---|
| **SBR (Codec)** | Der Encoder sendet Hüllkurven-Seiteninfo, der Decoder transponiert das untere Band nach oben und formt es mit der Hüllkurve ([Dietz et al., AES 112, Paper 5553, 2002](https://www.aes.org/e-lib/browse.cfm?elib=11328)) | **Nicht anwendbar**: MP3 und AAC-LC enthalten keine SBR-Daten. Bei HE-AAC macht der Decoder das schon | Die Kernfamilie (Priorität 10.06.1997) ist abgelaufen, [US6680972B1](https://patents.google.com/patent/US6680972B1/en) „Expired – Lifetime“, voraussichtlich 09.06.2018. Spätere Familienmitglieder und HE-AAC-Pool **nicht geprüft** |
| **Blinde BWE (spektral)** | STFT; Grenzfrequenz erkennen; obere Oktave unter der Grenze kopieren/transponieren, mit fallender Hüllkurve (−12 dB/Okt) einsetzen | Ja. FFT 2048, Hop 512: ≈ 21 MFLOP/s je Kanal (eigene Rechnung), auf ARM-Kern einige % (Annahme, messen). Latenz ≈ 43 ms, egal wegen AudioTrack-Puffer | Lehrbuch ([Larsen & Aarts, *Audio Bandwidth Extension*, Wiley 2004](https://doi.org/10.1002/0470858710)). **Keine FTO-Recherche** |
| **Harmonische Anregung (Exciter)** | Hochpass ab ~0,5 × Grenzfrequenz → Nichtlinearität (z. B. `|x|` oder Wärme-Kurve) erzeugt Obertöne → Hochpass an der Grenzfrequenz → leise beimischen | Ja, ≈ 2 Biquads + 1 Kurve je Sample, **vernachlässigbar** (vergleichbar mit Wärme: 3,7 % mit `Math.tanh` im Emulator, mit Padé weniger, s. `WarmthAudioProcessor`) | Klassisch und im Lehrbuch 2004 beschrieben. Patente aus dieser Zeit sind in der Regel abgelaufen (20 Jahre ab Anmeldung), **keine FTO-Recherche** |
| **ML (AudioSR)** | Latent-Diffusion + Vocoder, Eingang 2–16 kHz Bandbreite → 24 kHz bei 48 kHz ([arXiv 2309.07314](https://arxiv.org/abs/2309.07314)) | **Nein.** Laut Paper ist Echtzeit „future work“; DDIM-Sampling, GPU-Klasse | Code MIT ([GitHub-API](https://github.com/haoheliu/versatile_audio_super_resolution); die LICENSE-Datei trägt seltsamerweise einen fremden Copyright-Vermerk, also vor Nutzung klären), Gewichte nicht geprüft |

### 2.3 Ehrliche Bewertung

- **Information kehrt nicht zurück.** Was der Encoder weggeschnitten hat, ist weg. BWE erfindet *plausible* Höhen.
- **Wo es überhaupt etwas zu ergänzen gibt:** Laut LAME-Quelltext liegt der Tiefpass bei 128 kbps bei **17 kHz**, bei
  192 kbps bei 18,6 kHz und bei 320 kbps bei 20,5 kHz
  ([lame.c `optimum_bandwidth`](https://sourceforge.net/p/lame/svn/HEAD/tree/trunk/lame/libmp3lame/lame.c)).
  Nutzen kann es also nur bei ≤ 128-kbps-Dateien und Altbeständen geben. Bei FLAC und 256/320 kbps bleibt die Funktion
  aus (Erkennung s. u.). Ob Erwachsene über 16–17 kHz noch viel hören, ist individuell. ISO 226:2023 deckt nur
  20 Hz–12,5 kHz für 18- bis 25-Jährige ab ([DIN-Eintrag](https://www.din.de/en/wdc-proj:din21:379070391), Normtext
  kostenpflichtig). Ein Hörgewinn ist also **nicht belegt** (Annahme: klein).
- **Risiko „scharf“:** Ein Exciter erzeugt Energie genau im Bereich, der als zischelnd und harsch empfunden wird.
  Belkis' Regel „nie scharf“ verlangt deshalb harte Grenzen: nur oberhalb der erkannten Grenzfrequenz, Pegel ≤ −24 dB
  unter dem Band darunter, Abfall ≥ 12 dB/Okt, nichts über 20 kHz, Standard **aus**, Stufen „Leicht/Mittel“, keine
  „Stark“-Stufe.
- **Abtastrate und Bittiefe hochrechnen** (Sony-Variante 96 kHz/24 bit) bringt auf Android **nichts**: Der Mixer rechnet
  mit Gerätetakt, meist 48 kHz (§4), und neue Information entsteht durch Hochrechnen nicht.

---

## 3. Hi-Res-Ausgabe

| Punkt | Befund (1.11.1) | Beleg |
|---|---|---|
| Schalter | `DefaultAudioSink.Builder.setEnableFloatOutput(true)` bzw. `DefaultRenderersFactory.setEnableAudioFloatOutput(true)`, Standard `false` | DefaultAudioSink L363–370; [DefaultRenderersFactory](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultRenderersFactory.java) L236–248 |
| **Effekte + Float = unmöglich** | Ist der Eingang Hi-Res (24/32 bit, Float), baut `configure()` die Kette nur aus Trimming → ChannelMapping → `ToFloatPcm`. **`audioProcessorChain` (Ordos Prozessoren, Silence-Skip, Speed) fällt weg.** Doku: „Audio processing … will not be available when float output is in use“ | DefaultAudioSink L752–759, L1755–1757 |
| Wann Float aktiv ist | Nur bei Hi-Res-PCM-Eingang. Den Decoder bittet Media3 nur dann um Float (`KEY_PCM_ENCODING`), wenn die Senke Float direkt kann, also nur mit eingeschaltetem Schalter. 16-Bit-Quellen bleiben im Int16-Pfad mit Effekten | [MediaCodecAudioRenderer](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/MediaCodecAudioRenderer.java) L1163–1168; AudioTrackAudioOutputProvider L530–550 |
| Heute in Ordo | `AudioRenderersFactory.buildAudioSink` ignoriert `enableFloatOutput`, deshalb ist alles 16 bit. 24-Bit-FLAC kommt als 16 bit vom Decoder; 24-Bit-WAV wird in `ToInt16PcmAudioProcessor` durch **Abschneiden der unteren Bytes ohne Dither** reduziert, Float durch `(short)(x·32767)` (Truncation) | `MonoAudioProcessor.kt` L43–56; [ToInt16PcmAudioProcessor](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/audio/ToInt16PcmAudioProcessor.java) L124–150 |
| Bit-perfect USB (API 34) | `AudioManager.setPreferredMixerAttributes(attrs, device, AudioMixerAttributes)` mit `MIXER_BEHAVIOR_BIT_PERFECT`: „audio data will be sent as is down to the HAL“. Nur `USAGE_MEDIA`, nur USB-Geräte, gleiches `AudioFormat` für Wiedergabe und Mixer, Berechtigung `MODIFY_AUDIO_SETTINGS` (Schutzstufe *normal*). Bit-perfect ist für Hersteller **optional** (nur AIDL-HAL plus Flag `AUDIO_OUTPUT_FLAG_BIT_PERFECT`). Laut AOSP gehen im BIT_PERFECT-Modus keine Lautstärke und keine Effekte | [AudioManager](https://developer.android.com/reference/android/media/AudioManager#setPreferredMixerAttributes(android.media.AudioAttributes,%20android.media.AudioDeviceInfo,%20android.media.AudioMixerAttributes)), [AudioMixerAttributes](https://developer.android.com/reference/android/media/AudioMixerAttributes), [AOSP preferred-mixer-attr](https://source.android.com/docs/core/audio/preferred-mixer-attr), [Manifest.permission](https://developer.android.com/reference/android/Manifest.permission#MODIFY_AUDIO_SETTINGS) |
| Media3 + Bit-perfect | Media3 1.11.1 kennt `AudioMixerAttributes` **nicht** (grep im Quellbaum: 0 Treffer). Media3 schreibt Float, ein DAC meldet meist 16/24/32-Bit-Integer, das Format passt also nicht. Nötig wäre ein eigener `AudioOutputProvider` mit Integer-Ausgabe | grep `MixerAttributes` in `libraries/` |

**Welche Prozessoren müssten Float können:** alle fünf (`Mono`, `Crossfeed`, `Width`, `Warmth`, `Reverb`, alle mit
`ENCODING_PCM_16BIT` in `onConfigure`). Das bringt aber nichts, solange `DefaultAudioSink` sie im Float-Pfad gar nicht
aufruft. Ein Weg wäre ein `ForwardingAudioSink`, der Float-Puffer vor dem Delegieren selbst bearbeitet. Das ist heikel,
weil `handleBuffer` denselben Puffer mehrfach anbieten kann, und es lohnt erst später.

---

## 4. Resampling und Dither

- **Was Android mit 44,1 kHz auf einem 48-kHz-Gerät macht:** Musikraten bekommen `DEFAULT_QUALITY`
  ([AudioMixerBase.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libaudioprocessing/AudioMixerBase.cpp)
  L495–507). Ohne die Systemeigenschaft `af.resampler.quality` wird daraus **`DYN_MED_QUALITY`**, das unter CPU-Last
  auf `DYN_LOW` drosseln darf ([AudioResampler.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libaudioprocessing/AudioResampler.cpp)
  L166–174). DYN_MED hat **84 dB Sperrdämpfung**, halbe Filterlänge 16 beim Hochtakten und 16-Bit-Koeffizienten. DYN_HIGH
  hätte 98 dB und 32-Bit-Koeffizienten
  ([AudioResamplerDyn.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libaudioprocessing/AudioResamplerDyn.cpp) L403–460).
  Die Gerätetaktrate liefert `AudioManager.getProperty(PROPERTY_OUTPUT_SAMPLE_RATE)`
  ([Doku](https://developer.android.com/reference/android/media/AudioManager#PROPERTY_OUTPUT_SAMPLE_RATE)).
- **Eigenes SRC in der App?** Messbar besser (≥ 120 dB mit Windowed-Sinc/Polyphase). Bei 44,1→48 kHz liegen die Bilder
  aber knapp unter 24 kHz, und 84 dB entsprechen fast dem 16-Bit-Rauschteppich. Der Hörgewinn ist **nicht belegt**
  (Annahme: unhörbar). Media3s eigenes Resampling (Sonic) interpoliert nur **linear** und taugt dafür nicht
  ([Sonic.java](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/audio/Sonic.java)
  L851–860).
  Lizenzen: libsoxr ist **LGPL-2.1** (COPYING.LGPL, [chirlu/soxr](https://github.com/chirlu/soxr)) und damit
  GPL-3.0-kompatibel, braucht aber NDK. libsamplerate ist BSD-2-Clause, r8brain-free-src MIT (GitHub-API). Ein eigener
  Kotlin-Sinc ist ohne Fremdcode möglich. **Empfehlung: jetzt nicht.**
- **Dither:** Ordo quantisiert heute **fünfmal** ohne Dither auf 16 bit (`toPcm` in jedem Prozessor, `roundToInt`).
  Jeder Prozessor hat außerdem eine eigene `softLimit`-Kurve ab −0,9 dBFS, was bei lauten Mastern gestapelte Verzerrung
  ergibt. Lipshitz/Wannamaker/Vanderkooy empfehlen „nonsubtractive, triangular-pdf dither of 2-LSB peak-to-peak
  amplitude … easy to generate … by simply summing two independent rectangular-pdf“ Zufallswerte
  ([JAES 40(5) 1992, S. 355–375](https://hajim.rochester.edu/ece/sites/zduan/teaching/ece472/reading/Lipshitz_1992.pdf)).
  Für Ordo heißt das: **eine** Float-Kette, **ein** Limiter und **ein** TPDF-Dither am Ende. Der Rauschpegel steigt
  dadurch um ≈ 4,8 dB (Faktor 3 in der Fehlervarianz), dafür gibt es keine Verzerrung im Hall-Schweif und keine
  Rauschmodulation.

---

## 5. Weitere Qualitätsmerkmale

| Merkmal | Stand / Quelle | Bewertung für Ordo |
|---|---|---|
| **Gapless** | Media3 trimmt Encoder-Delay und -Padding (`trimmingAudioProcessor.setTrimFrameCount(encoderDelay, encoderPadding)`, DefaultAudioSink L769–770). MP3 nutzt Xing/Info-Dauern (Release Notes 1.11.0) | Vorhanden. Prüfen mit LAME-Testdateien, Naht sample-genau (s. u.) |
| **Überblenden zwischen Titeln** | Nicht in Media3. [Issue #2](https://github.com/androidx/media/issues/2) ist seit 2021 offen, Mitarbeiter: „we don't have current plans“ | Bräuchte zwei Player oder Puffer-Tricks und widerspricht Gapless. **Nicht jetzt** |
| **Lautheitsangleich** | Ordo liest ReplayGain- und R128-Tags (`ReplayGainMetadata.kt`). **Befund:** `R128_TRACK_GAIN` bezieht sich auf EBU R128 ([RFC 7845 §5.2](https://www.rfc-editor.org/rfc/rfc7845#section-5.2)), also −23 LUFS ([EBU R 128](https://tech.ebu.ch/docs/r/r128-2014.pdf)). ReplayGain 2.0 zielt auf −18 LUFS (Spezifikation auf hydrogenaudio, Abruf 403, **nicht an der Quelle geprüft**). Ordo behandelt beide gleich, R128-getaggte Opus-Dateien spielen dann ≈ 5 dB leiser | Kleiner Fix (+5 dB auf R128-Werte), danach Hintergrund-Analyse für Dateien ohne Tags nach **ITU-R BS.1770-5** (K-Filter + Gating) |
| **True-Peak-Limiter** | BS.1770-5 Annex 2: True-Peak mit ≥ 4× Oversampling, „Higher … over-sampling ratios are preferred“ ([ITU](https://www.itu.int/rec/R-REC-BS.1770)). EBU R128: max. −1 dBTP | Der DP-Limiter arbeitet auf Sample-Peaks (Schwelle heute −0,1 dB). Schwelle **−1,0 dB** als Näherung, solange Lautheitsangleich aktiv ist |
| **Headroom vor Effekten** | Breite > 1, Hall und Wärme erhöhen den Spitzenpegel. Der heutige Schutz besteht aus `softLimit` pro Stufe | Bei aktiven Effekten −3 dB vor der Kette, +3 dB per DP-Input-Gain danach. Der DP-Limiter fängt das in Float ab (DP läuft nach dem AudioTrack in Float) |
| **Gehörrichtiger Ausgleich bei leiser Lautstärke** | ISO 226:2023: Kurven gleicher Lautstärke, 20 Hz–12,5 kHz ([ANSI-Vorschau](https://webstore.ansi.org/preview-pages/ISO/preview_ISO+226-2023.pdf)) | Sanfte DP-Shelves abhängig vom Systemlautstärke-Index, max. +6 dB bei 60 Hz. Der echte Schalldruck ist unbekannt (Kopfhörer-Empfindlichkeit), deshalb nur Näherung |
| **Mono/Stereo-Check** | – | Korrelation L/R messen: Bei < −0,5 (gegenphasig) löscht der Mono-Mix sich aus, dann warnen oder einen Kanal nehmen. Dazu ein Links/Rechts-Testton |

---

## 6. Empfehlung (Reihenfolge = Priorität)

| # | UI-Name | Technik und Weg in Media3 | CPU | Lizenz | Objektiv messen | Risiko |
|---|---|---|---|---|---|---|
| 1 | **Sanftes Starten und Pausieren** (Schalter, Standard an) | `fadeVolume` in der vorhandenen `sessionPlayer`-`ForwardingPlayer`: 120 ms aus, 80 ms ein (Kosinus), danach `pause()`. Ausnahme Kopfhörer-Abzug | ≈ 0 | eigener Code | JVM: Rampen-Planer liefert monoton fallende Werte und Schritte ≤ 0,1. Gerät: Aufnahme, Delta-Kriterium §1.4 | niedrig. Das Pause-Symbol reagiert 120 ms später, deshalb den UI-Zustand sofort umschalten |
| 2 | **Knackfreie Klangeffekte** (unsichtbar, immer an) | `StereoEffectProcessor`: Überblendung trocken↔nass (30 ms), Glättung je Sample, Stufenwechsel per Parallelrechnung. DP dauerhaft `enabled`, EQ-Sprünge in ≤ 2-dB-Schritten | +1 Mult./Sample, beim Stufenwechsel 30 ms doppelt | eigener Code | JVM-Klicktest §1.4 für jeden Prozessor und jeden Übergang | niedrig |
| 3 | **Saubere Klangkette** (unsichtbar) | Die fünf Prozessoren in **einem** `BaseAudioProcessor` zusammenfassen: einmal lesen, alles in Float, −3 dB Headroom bei aktiven Effekten, ein Limiter, **TPDF-Dither** beim Schreiben auf 16 bit | eher weniger als heute (nur noch 1× Puffer kopieren) | eigener Code | JVM: THD+N mit 1-kHz-Sinus bei −1 dBFS (Ziel < 0,01 % bei neutralen Einstellungen), Rauschteppich bei Stille (Dither ≈ −96 dBFS RMS, keine Töne), Clip-Zähler = 0 | mittel (Umbau; vorhandene Tests umstellen) |
| 4 | **Lautstärke angleichen** (vorhanden) + Fix | R128-Offset +5 dB. Später BS.1770-Analyse im Hintergrund für Dateien ohne Tags. DP-Limiter auf −1 dB, solange der Angleich aktiv ist | Analyse: einmal je Datei im Leerlauf | eigener Code nach ITU-Text | Abweichung ≤ 0,5 LU gegen `ffmpeg -af ebur128` auf denselben Dateien | niedrig. R128-Bezug von RG2 (−18 LUFS) noch an der Quelle prüfen |
| 5 | **Höhen ergänzen** („für stark komprimierte Dateien“, Aus/Leicht/Mittel, Standard aus) | Exciter-Prozessor (§2.2). Er schaltet sich nur ein, wenn die Spektralanalyse eine Grenzfrequenz < 18 kHz findet (Energieabfall > 30 dB innerhalb von 500 Hz, gemittelt über 2 s). Ergänzung ≤ −24 dB, Abfall ≥ 12 dB/Okt | < 1 % eines Kerns (Annahme, messen) | eigener Code, klassische Technik. Keine FTO-Recherche | Spektrum vor/nach: Energie nur oberhalb der Grenze. **HF-Anteil wie in Belkis' Ton-Bench** darf nicht steigen, wo keine Grenze erkannt wurde. ABX-Hörtest blind | **mittel–hoch: Gefahr „scharf“.** Ehrlich benennen, nicht als „Hi-Res“ bewerben |
| 6 | **Leise hören mit vollem Klang** (Aus/An) | DP-Shelves je Lautstärkestufe, angelehnt an ISO 226 | ≈ 0 (DP läuft schon) | Plattform | Frequenzgang je Lautstärkestufe als Messkurve | mittel: Schalldruck unbekannt, Bass dröhnt bei empfindlichen Kopfhörern |
| 7 | **Reine Wiedergabe (Hi-Res)** | Eigener Modus, schaltet alle Effekte ab und baut den Player mit `setEnableFloatOutput(true)` neu auf (Position übernehmen). **Nie Float bei aktiven Effekten**, sonst wirken sie bei 24-Bit-Dateien still nicht | ≈ 0 | Plattform | `AnalyticsListener.onAudioTrackInitialized` → Encoding FLOAT bei 24-Bit-FLAC | mittel (Neuaufbau des Players, Session-Wechsel) |
| 8 | **USB-DAC unverfälscht** (Android 14+) | `setPreferredMixerAttributes` + eigener `AudioOutputProvider` mit Integer-Ausgabe. Nur USB, nur wenn Gerät und HAL das anbieten. Lautstärke regelt dann der DAC | ≈ 0 | Plattform | `getSupportedMixerAttributes` leer oder nicht; CTS-Muster `PreferredMixerAttributesTestActivity` (AOSP-Seite) | hoch (Geräteabdeckung, kein Testgerät mit AIDL-USB-HAL bekannt) |
| – | Eigenes Resampling, Überblenden zwischen Titeln, KI-Upscaling | §4, §5, §2.2 | – | – | – | **nicht bauen**: kein belegter Hörgewinn bzw. technisch unpassend |

**Reihenfolge-Logik:** 1–3 beseitigen hörbare Fehler, und das ist der Unterschied zwischen „gut“ und „bester Player“.
4 ist ein Bug plus Komfort. 5 erfüllt den Upscaling-Wunsch ehrlich und eng begrenzt. 6–8 sind Nischen.

**Namen:** Keine Marken (UHQ, DSEE, Dolby, Hi-Res-Audio-Logo). „Hi-Res“ nur als Beschreibung im Text, nicht als Label
(Annahme: Markenrisiko des Logos, nicht geprüft).
