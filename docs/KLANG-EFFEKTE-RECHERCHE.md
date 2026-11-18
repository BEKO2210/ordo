# Klang-Effekte für Ordo – Recherche (Stand 17.11.2026)

Ziel: Klangoptionen auf Premium-Niveau (3D, Raumklang, Saal), aber ohne Dolby-, DTS- oder Samsung-Namen, -Logos, -Code oder -Algorithmen. Nur Plattform-APIs oder eigener GPL-3.0-Code.
Ausgangslage in Ordo: `SoundEffectController.kt` hängt an die Media3-Session `DynamicsProcessing` (API 28+, 10 Bänder + Limiter + Balance über Input-Gain) bzw. `Equalizer` + `LoudnessEnhancer` (API 24–27) sowie `BassBoost` und `Virtualizer` an. `MonoAudioProcessor` ist ein Media3-`AudioProcessor`, der nur 16-Bit-Stereo verarbeitet. In der Media3-Kette laufen keine weiteren Prozessoren.

Alle Aussagen sind an der Primärquelle geprüft. „Eigene Rechnung“ markiert Schätzungen ohne Messung.

---

## 1. android.media.audiofx: was an die eigene Session passt

Grundregeln aus der Doku zu [AudioEffect](https://developer.android.com/reference/android/media/audiofx/AudioEffect):
- *Insert*: „Specifying an audio session ID when creating the effect will insert this effect after all players in the same audio session.“
- *Auxiliary*: „Auxiliary effects must be created on session 0 (global output mix)… they must be explicitely attached to this effect and a send level must be specified.“
- „attaching insert effects (equalizer, bass boost, virtualizer) to the global audio output mix by use of session 0 is deprecated.“

| Effekt | Art | An eigene Session (Media3-`audioSessionId`)? | AOSP-Software-Implementierung | Fallen |
|---|---|---|---|---|
| **Equalizer** (API 9) | Insert | ja | LVM-Bundle (NXP, Apache-2.0), **nur 5 Bänder** ([EffectBundle.h](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libeffects/lvm/wrapper/Bundle/EffectBundle.h): `FIVEBAND_NUMBANDS 5`) | Bandzahl und Frequenzen sind geräteabhängig. Ordo bildet das schon ab (`nearestLogicalBand`) |
| **BassBoost** (API 9) | Insert | ja | LVM-Bundle | AOSP schaltet ihn auf **Lautsprecher, BT-Lautsprecher und Carkit stumm ab** ([EffectBundle.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libeffects/lvm/wrapper/Bundle/EffectBundle.cpp), `EFFECT_CMD_SET_DEVICE`: „device … is invalid for LVM_BASS_BOOST“) |
| **Virtualizer** (API 9) | Insert | ja | LVM-Bundle, **nur für Kopfhörer** (Wired, A2DP-Kopfhörer, USB-Headset, BLE-Headset) und **höchstens 2 Kanäle** (`VirtualizerIsDeviceSupported`, `VirtualizerIsConfigurationSupported`, gleiche Datei) | **Seit API 35 deprecated**, zugunsten von Spatializer und `setSpatializationBehavior` ([Virtualizer](https://developer.android.com/reference/android/media/audiofx/Virtualizer)). Bei Stereo und Kopfhörer heißt es dort: „a stereo widening effect is used“ |
| **PresetReverb** (API 9) | eigentlich Aux | laut Doku ja: „If audioSession is not 0, the PresetReverb will be attached to the MediaPlayer or AudioTrack in the same audio session“ ([PresetReverb](https://developer.android.com/reference/android/media/audiofx/PresetReverb)) | AOSP bringt Aux- **und** Insert-Varianten mit (`reverb_pre_aux`, `reverb_pre_ins`, `reverb_env_aux`, `reverb_env_ins` in [audio_effects.xml](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libeffects/data/audio_effects.xml)) | Presets: SMALLROOM, MEDIUMROOM, LARGEROOM, MEDIUMHALL, LARGEHALL, PLATE. Für Aux auf Session 0 braucht es `MODIFY_AUDIO_SETTINGS`; das hat Ordo nicht im Manifest. Ob OEMs die Insert-Variante ausliefern, ist ungeprüft |
| **EnvironmentalReverb** (API 9) | wie PresetReverb | wie PresetReverb ([Doku](https://developer.android.com/reference/android/media/audiofx/EnvironmentalReverb)) | wie oben | Die Doku sagt: „more suitable for games“, für Musik soll PresetReverb dienen. Es gibt 10 Parameter (Decay 100–20 000 ms usw.) |
| **LoudnessEnhancer** (API 19) | Insert | ja (Ordo nutzt ihn auf API 24–27) | `libldnhncr.so` ([audio_effects.xml](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libeffects/data/audio_effects.xml)) | Zur Zielverstärkung heißt es: „signals amplified outside of the sample range … are compressed“ ([Doku](https://developer.android.com/reference/android/media/audiofx/LoudnessEnhancer)) |
| **DynamicsProcessing** (API 28) | Insert (`EFFECT_FLAG_TYPE_INSERT \| INSERT_LAST`) | ja | `libdynproc.so`, Apache-2.0 ([EffectDynamicsProcessing.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libeffects/dynamicsproc/EffectDynamicsProcessing.cpp)) | siehe Abschnitt 3 |
| **HapticGenerator** (API 31) | Post-Processor | ja (`create(sessionId)`) | `libhapticgenerator.so` | Erzeugt nur Vibration und keinen Klang. Nötig sind ein Gerät mit Audio-Haptik (`isAvailable()`), die Berechtigung `VIBRATE` und `setHapticChannelsMuted(false)` ([Doku](https://developer.android.com/reference/android/media/audiofx/HapticGenerator), [AudioAttributes.Builder](https://developer.android.com/reference/android/media/AudioAttributes.Builder#setHapticChannelsMuted(boolean))). Höchstens ein Gimmick, kein Klangfeature |

**Aux mit ExoPlayer:** geht. `ExoPlayer.setAuxEffectInfo(AuxEffectInfo)` existiert ([ExoPlayer.java 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayer.java)), und `DefaultAudioSink` ruft dafür `attachAuxEffect()` und `setAuxEffectSendLevel()` auf ([DefaultAudioSink.java 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java), `setAuxEffectInfo`). Der Aux-Hall liegt aber auf Session 0, also auf dem globalen Mix: Er braucht `MODIFY_AUDIO_SETTINGS` und läuft parallel zu anderen Apps. Für Ordo ist das nicht empfehlenswert.

**Gerätestreuung:** OEMs ersetzen die AOSP-Bibliotheken über ihre eigene `audio_effects.xml`. Darum vor jeder Nutzung per `try/catch` prüfen (Ordo macht das schon) oder `AudioEffect.queryEffects()` aufrufen ([Doku](https://developer.android.com/reference/android/media/audiofx/AudioEffect#queryEffects())).

## 2. Spatializer (API 32+, für Apps sinnvoll ab API 33)

- `AudioManager.getSpatializer()`; laut Doku ist die Unterstützung optional ([Spatializer](https://developer.android.com/reference/android/media/Spatializer)). Vier Bedingungen müssen gelten: `getImmersiveAudioLevel() != NONE`, `isAvailable()`, `isEnabled()`, `canBeSpatialized(attrs, format)` ([Leitfaden Spatial Audio](https://developer.android.com/media/grow/spatial-audio)).
- **Standardmäßig wird nur Mehrkanal räumlich gerendert:** „Only multi-channel content is spatialized, not stereo content.“ Nötig ist eine Kanalmaske, die mindestens `CHANNEL_OUT_QUAD` enthält, und als Usage `USAGE_MEDIA` oder `USAGE_GAME`. OEMs dürfen das anpassen (gleiche Quelle). **Für typische MP3/FLAC-Stereo-Musik bringt der Spatializer also nichts.**
- Head-Tracking: Ab API 33 gibt es `isHeadTrackerAvailable()` und einen Listener. Die App steuert das nicht selbst, das Headset muss passen (Doku nennt z. B. Pixel Buds Pro mit Pixel). Ein- und Ausschalten erfolgt in den Systemeinstellungen.
- **Media3 erledigt das schon:** „After updating your ExoPlayer dependency to the latest release, your app just needs to include content that can be spatialized“ ([Leitfaden](https://developer.android.com/media/grow/spatial-audio#exoplayer)). Der `DefaultTrackSelector` hört ab API 32 auf Spatializer-Änderungen ([DefaultTrackSelector.java 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/trackselection/DefaultTrackSelector.java), `constrainAudioChannelCountToDeviceCapabilities`). Media3-`AudioAttributes` setzt als Standard `SPATIALIZATION_BEHAVIOR_AUTO` und reicht `setSpatializationBehavior` bzw. `setIsContentSpatialized` ab API 32 an die Plattform weiter ([AudioAttributes.java 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/AudioAttributes.java)). Ordo setzt `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC` und ist damit bereits berechtigt.
- Eine eigene Spatializer-Implementierung liefert AOSP **nicht**: `media/libeffects/spatializer` enthält nur `benchmarks` und `tests` ([Verzeichnis](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libeffects/spatializer/)). Der Effekt kommt vom Hersteller, welche Geräte ihn haben, ist herstellerabhängig. Der einzige belegte Hinweis ist Pixel + Pixel Buds Pro.
- Sinnvoll für Ordo: den Status anzeigen und einen Schalter „Räumlichen Klang des Systems nutzen“ (AUTO/NEVER) anbieten. Ist der eigene Raumklang (Abschnitt 4) aktiv, sollte `setIsContentSpatialized(true)` gesetzt werden, damit das System nicht doppelt verarbeitet ([AudioAttributes.Builder](https://developer.android.com/reference/android/media/AudioAttributes.Builder#setIsContentSpatialized(boolean))).

## 3. DynamicsProcessing (API 28+)

- Die Stufen pro Kanal sind Input-Gain, Pre-EQ, Mehrband-Kompressor (MBC), Post-EQ und Limiter. „The effect is instantiated and controlled by channels … all of their parameters are independent from other channels.“ ([Doku](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing))
- **Mehr EQ-Bänder:** Die Bandzahl ist über `Config.Builder` frei wählbar. Ordo nutzt 10. Ein 15- oder 31-Band-EQ ist technisch möglich, die UI-Bandlogik bleibt dabei gleich.
- **Lautheit ohne Clipping:** Die Kombination aus MBC und Limiter ersetzt den LoudnessEnhancer. In Ordo ist der Limiter schon aktiv, der MBC ist aus (`mbcInUse=false`). Über den MBC lassen sich „Klarheit“, „Nachtmodus“ und „Sprache betonen“ bauen.
- **Pro Kanal:** `setInputGainbyChannel`, `setPreEqBandByChannelIndex` usw. Balance läuft in Ordo bereits darüber.
- **Fallen:** In AOSP ist **nur `VARIANT_FAVOR_FREQUENCY_RESOLUTION` implementiert**, andere Varianten werden nur mit „not available“ geloggt (`DP_changeVariant` in [EffectDynamicsProcessing.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libeffects/dynamicsproc/EffectDynamicsProcessing.cpp)). Die Implementierung arbeitet FFT-basiert, mit Blockgröße 8–16384 ([DPFrequency.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libeffects/dynamicsproc/dsp/DPFrequency.cpp)) und damit mit Blocklatenz. Die Abdeckung bei OEMs ist ungeprüft; Ordo fängt `RuntimeException` ab und greift auf den Equalizer zurück. Das ist richtig so.
- **Reihenfolge der Verarbeitung:** Die Media3-`AudioProcessor`s laufen **vor** dem AudioTrack, die Session-Effekte **danach** im Mixer (siehe Insert-Definition in [AudioEffect](https://developer.android.com/reference/android/media/audiofx/AudioEffect#EFFECT_INSERT), Media3-Prozessoren laut [DefaultAudioSink.Builder](https://developer.android.com/reference/androidx/media3/exoplayer/audio/DefaultAudioSink.Builder)). Eigener Raumklang und Hall laufen also vorher, und der DP-Limiter fängt Übersteuerung danach ab. Diese Reihenfolge ist ideal.

## 4. Eigene DSP in Media3 (wie `MonoAudioProcessor`)

Einstieg: `BaseAudioProcessor` in die Kette `DefaultAudioSink.Builder.setAudioProcessors(...)` hängen, die Ordo schon nutzt. Ohne `setEnableFloatOutput(true)` wandelt `DefaultAudioSink` nach 16-Bit-PCM ([DefaultAudioSink.java 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java), `getFormatSupport`). Intern rechnet man in Float und schreibt mit Sättigung zurück.

**Wichtig:** Die mitgelieferte `ChannelMixingAudioProcessor`/`ChannelMixingMatrix` kann **keine** Stereobreite. Sie wirft bei negativen Koeffizienten „Coefficient at index … is negative“ ([ChannelMixingMatrix.java 1.11.1](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/audio/ChannelMixingMatrix.java)). Mid/Side braucht aber `L − R`, also ist ein eigener Prozessor nötig.

| Algorithmus | Wirkung | Referenz & Lizenz (an der Quelle geprüft) | CPU (eigene Rechnung, 48 kHz Stereo) |
|---|---|---|---|
| **Crossfeed nach Bauer (bs2b)** | Holt die „Im-Kopf“-Ortung bei Kopfhörern nach vorn: Tiefpass-Übersprechen auf das Gegenohr plus Höhen-Shelf auf den direkten Weg | libbs2b, **MIT** © 2005 Boris Mikhaylov ([COPYING](https://github.com/alexmarsev/libbs2b/blob/master/COPYING), GitHub-Spiegel des SourceForge-Projekts). MIT ist GPL-3.0-kompatibel, der Hinweis muss erhalten bleiben. Alternativ selbst nach der Formel schreiben | 2 Biquads 1. Ordnung je Kanal, ca. 10 Mult./Frame, < 1 Mio. Op/s, vernachlässigbar |
| **Stereobreite (Mid/Side)** | `M=(L+R)/2, S=(L−R)/2, L'=M+w·S, R'=M−w·S` | Lehrbuchformel ohne Fremdcode, also keine Lizenzfrage | 4 Op/Frame, vernachlässigbar |
| **Hall (Freeverb)** | Raum, Saal, Konzertsaal über Raumgröße, Dämpfung und Nass/Trocken | Jezar/Dreampoint, **Public Domain**: „Free, studio-quality reverb SOURCE CODE in the public domain … You may do with the code whatever you wish“ ([readme.txt](https://github.com/sinshu/freeverb/blob/main/readme.txt)). Aufbau aus 8 parallelen Kammfiltern und 4 Allpässen ([J. O. Smith, CCRMA](https://ccrma.stanford.edu/~jos/pasp/Freeverb.html)). Achtung: Forks wie Csound tragen eigene Lizenzen, deshalb nur das Original nutzen | 12 Delay-Line-Op./Kanal/Sample, ca. 1–2 Mio. Op/s, auf heutigen ARM-Kernen niedriger einstelliger %-Bereich eines Kerns. **Auf einem A15 o. ä. messen** |
| **Analoge Wärme (Sättigung)** | Sanfte Röhren-Charakteristik über `tanh`-Waveshaper und leichtes 2. Harmonisches | Mathematische Standardkurve ohne Fremdcode | vernachlässigbar (Lookup-Tabelle statt `tanh`) |

Für alle eigenen Prozessoren gilt: nur bei 2 Kanälen aktiv, sonst `NOT_SET` (wie Mono). Bei aktivem Mono sind sie wirkungslos und sollten ausgegraut sein. Keine Allokation in `queueInput`, Parameter über `@Volatile` (Muster von `MonoAudioProcessor`).

## 5. Was Samsung anbietet (nur Funktionen, neutral benannt)

Aus Samsungs eigenen Supportseiten:
- Equalizer mit Genre-Presets und manuellem Modus ([Samsung US](https://www.samsung.com/us/support/answer/ANS00080318))
- Herstellerspezifischer Surround-Klang für Film, Musik und Sprache sowie eine Spiele-Variante, also ein lizenzierter Raumklang ([Samsung SA](https://www.samsung.com/sa_en/support/mobile-devices/useful-audio-settings-on-the-galaxy-phone-including-separate-app-sound/))
- 360°-Klang mit eigenen Kopfhörern, entspricht Spatializer und Head-Tracking ([Samsung US](https://www.samsung.com/us/support/answer/ANS00080318))
- Hochrechnen der Auflösung (Bit/Bandbreite) bei Kopfhörern ([Samsung US](https://www.samsung.com/us/support/answer/ANS00080318))
- Hörprofil nach Altersgruppe oder Hörtest ([Samsung US](https://www.samsung.com/us/support/answer/ANS00080318))
- Ältere Modelle: „Surround“, Röhrenverstärker-Klang („soft timbre of a tube amplifier“) und Konzertsaal-Hall („reverb of a concert hall“). „Some sound effects can only be used when headphones … are connected“ ([Samsung IN](https://www.samsung.com/in/support/mobile-devices/how-to-personalise-the-sound-settings-on-samsung-galaxy/))
- Ton einzelner Apps auf ein anderes Gerät leiten, Dual-Audio ([Samsung SA](https://www.samsung.com/sa_en/support/mobile-devices/useful-audio-settings-on-the-galaxy-phone-including-separate-app-sound/))

Neutrale Entsprechungen in Ordo: Equalizer → EQ (vorhanden). Surround/360° → „Raumklang“ + System-Spatializer. Röhre → „Analoge Wärme“. Konzertsaal → „Hall: Saal“. Hörprofil → später, siehe Risiko unten. Upscaler → **weglassen**, weil es kein echter Qualitätsgewinn ist und Ordo nur Marketing nachbauen würde.

## 6. Empfehlung: 6 Funktionen plus 1 optionale

| # | UI-Name (Deutsch) | Weg | Min-API | Fallback | Lizenz | Risiko |
|---|---|---|---|---|---|---|
| 1 | **Raumklang für Kopfhörer** (Stufen: Leicht / Natürlich / Weit) | eigener `CrossfeedAudioProcessor` (Bauer-Formel) | 24 | aktiv nur mit Kopfhörern (`isHeadphoneDevice` gibt es in Ordo schon); am Lautsprecher automatisch aus | MIT-Port mit Hinweis oder Eigenbau | niedrig |
| 2 | **Stereobreite** (−/+ in Schritten, kein Schieberegler) | eigener `StereoWidthAudioProcessor` (M/S) | 24 | – (läuft überall) | Eigenbau | niedrig; bei w > 1,5 drohen Phasenprobleme, daher Obergrenze ca. 1,6 |
| 3 | **Hall** (Aus / Kleiner Raum / Studio / Saal / Konzertsaal) | eigener Freeverb-Prozessor, damit er auf allen Geräten gleich klingt | 24 | optional `PresetReverb(0, sessionId)` als Insert, wenn der eigene CPU-Test scheitert | Public Domain (Original-readme) | mittel (Klangabstimmung, CPU auf Low-End messen) |
| 4 | **Klarheit** (Lautheit ohne Übersteuern, „Nachtmodus“ als Preset) | `DynamicsProcessing` MBC + Limiter (in Ordo schon instanziiert, MBC nur einschalten) | 28 | API 24–27: `LoudnessEnhancer` | Plattform | niedrig |
| 5 | **Analoge Wärme** | eigener Sättigungs-Prozessor | 24 | – | Eigenbau | niedrig |
| 6 | **System-Raumklang** (Statuszeile + Schalter, Head-Tracking-Hinweis) | Media3 `AudioAttributes.setSpatializationBehavior(AUTO/NEVER)` + `Spatializer`-Listener | 32 (sichtbar ab 33) | Zeile ausblenden, wenn `getImmersiveAudioLevel()==NONE` | Plattform | niedrig; wirkt nur bei Mehrkanal |
| (7) | **Bass** neu: DP-Pre-EQ-Low-Shelf statt `BassBoost` | DP-Bänder | 28 | API < 28: BassBoost wie bisher | Plattform | niedrig. Grund: Der AOSP-BassBoost ist am Lautsprecher stumm (s. Abschnitt 1) |

Zusätzlich:
- Den **Virtualizer**-Schalter mittelfristig durch #1 ersetzen. Er ist ab API 35 deprecated, funktioniert in AOSP nur mit Kopfhörern und klingt je nach Hersteller anders.
- **Klang-Presets** als Kombination, z. B. „Live“ = Hall Saal + Breite 1,3 + Wärme leicht, „Fokus“ = Crossfeed + Klarheit. Damit entsteht das Premium-Gefühl ohne zusätzlichen DSP.
- **Kette:** Mono → Crossfeed → Breite → Wärme → Hall (Media3) → AudioTrack → DP (EQ, MBC, Limiter). Bei aktivem eigenen Raumklang `setIsContentSpatialized(true)` setzen.
- **Namen:** „Konzertsaal“, „Raumklang“ und „Wärme“ sind Gattungsbegriffe. Nicht verwenden: Dolby, Atmos, DTS, SoundAlive, UHQ, Adapt Sound, „Tube Amp“, 360 Audio. Eine Markenregister-Prüfung wurde **nicht** gemacht.
- **Hörprofil (Samsung-Äquivalent):** zurückgestellt. Es berührt Hörgesundheit, und eine alters- oder hörtestbasierte Kurve wirkt schnell wie eine medizinische Aussage.
