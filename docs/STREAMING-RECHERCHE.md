# Ordo: Streaming an Bluetooth, Chromecast, DLNA (Recherche, Stand 17.11.2026)

Nur gelesen, kein Gerät angefasst. Jede Aussage hat eine Quelle. Als **Schluss** markierte Stellen sind
meine Ableitung und nicht wörtlich belegt.

**Ausgangslage im Repo**: minSdk 24, targetSdk 36, compileSdk 37 (`app/build.gradle` Z. 15–23). Media3
1.11.1 mit exoplayer, extractor, session und ui, kein `media3-cast` (Z. 139–143). `AudioPlaybackService` baut den
ExoPlayer mit `setHandleAudioBecomingNoisy(false)`, einem eigenen Noisy-Receiver und `WAKE_MODE_NETWORK`. Die Session
bekommt einen `ForwardingPlayer` (Z. 177–214). `PathDataSource` liest über `java8.nio.file.Path`, also auch
SMB/SFTP/WebDAV. Das Manifest hat bereits einen FTP-Server-FGS (`specialUse`), `usesCleartextTraffic="true"` und
`QUERY_ALL_PACKAGES`. GMS gibt es nicht.

---

## 1. Bluetooth und Kabel: was heute schon kostenlos läuft

| Punkt | Stand | Quelle |
|---|---|---|
| A2DP- und Kabel-Routing | ExoPlayer spielt auf die aktive System-Route. Die Systemauswahl bietet „Lautsprecher + alle verbundenen BT-Audiogeräte“ von selbst an. | [Media-Routing-Guide, Abschnitt „The output switcher“](https://developer.android.com/guide/topics/media/media-routing) |
| Headset- und AVRCP-Tasten | Die MediaSession nimmt Media-Button-Events entgegen, Media3 setzt sie in Player-Befehle um. | [Media3: Control playback](https://developer.android.com/media/media3/session/control-playback), [Media buttons](https://developer.android.com/media/legacy/media-buttons) |
| Output Switcher in der Medien-Benachrichtigung | Ab Android 11 automatisch, sobald die Session eine Medien-Benachrichtigung hat | [Media-Routing-Guide](https://developer.android.com/guide/topics/media/media-routing) |
| Kopfhörer abziehen pausiert | Bereits selbst gebaut (`ACTION_AUDIO_BECOMING_NOISY`) | Repo, `AudioPlaybackService.kt` Z. 99, 183, 201 |

**Eigener „Ausgabe wählen“-Button ohne GMS: ja.**
- Artefakt: `androidx.mediarouter:mediarouter:1.8.1` (stabil seit 02.07.2025, 1.9.0-rc01 vom 21.10.2026) –
  [Release-Notes](https://developer.android.com/jetpack/androidx/releases/mediarouter)
- API: `SystemOutputSwitcherDialogController.showDialog(context): Boolean`, seit 1.4.0 –
  [Referenz](https://developer.android.com/reference/androidx/mediarouter/app/SystemOutputSwitcherDialogController)
- Verhalten laut [Quelltext](https://github.com/androidx/androidx/blob/androidx-main/mediarouter/mediarouter/src/main/java/androidx/mediarouter/app/SystemOutputSwitcherDialogController.java):
  - API 34+ ruft die Plattform-API `MediaRouter2.showSystemOutputSwitcher()` auf
    ([Referenz](https://developer.android.com/reference/android/media/MediaRouter2)).
  - API 31–33 schickt einen Broadcast an SystemUI.
  - API 30 öffnet das Settings-Panel-Intent.
  - Darunter liefert es `false`; auf Wear öffnet es stattdessen die BT-Einstellungen.
  - Die Bibliothek prüft die Empfänger per `queryBroadcastReceivers`/`queryIntentActivities`. Ordo hat
    `QUERY_ALL_PACKAGES`, die Paket-Sichtbarkeit ist also kein Problem.
- **Schluss:** Auf API 24–29 (Ordo-minSdk 24) gibt es keinen System-Picker. Dort ist der Fallback
  `Settings.ACTION_BLUETOOTH_SETTINGS`.
- Media3s eigene Variante `CastParams.getShowSystemOutputSwitcherOnCastIconClick()` (neu in 1.11) steckt in `media3-cast`
  und braucht damit GMS (siehe 6) – [RELEASENOTES.md](https://github.com/androidx/media/blob/release/RELEASENOTES.md).

## 2. Chromecast ohne Google Play services

### Lizenz: Das Problem ist bestätigt
- `play-services-cast-framework` 23.0.0 steht laut POM unter der **„Android Software Development Kit License“**, ist
  also proprietär – [maven-metadata](https://dl.google.com/android/maven2/com/google/android/gms/play-services-cast-framework/maven-metadata.xml),
  [POM 23.0.0](https://dl.google.com/android/maven2/com/google/android/gms/play-services-cast-framework/23.0.0/play-services-cast-framework-23.0.0.pom).
- GPL-FAQ [#GPLIncompatibleLibs](https://www.gnu.org/licenses/gpl-faq.html#GPLIncompatibleLibs): Wer gegen eine
  Bibliothek außerhalb der System-Library-Ausnahme linkt, braucht dafür eine Erlaubnis. „Only the copyright holders …
  can“ diese Erlaubnis geben, und für fremden GPL-Code „you cannot authorize the exception“.
- Ordo ist ein Fork von Material Files. Die Rechte am Großteil des Codes liegen bei den Upstream-Autoren, eine
  Ausnahmeklausel ist deshalb praktisch unmöglich.
- **Schluss:** Die „System Library“-Ausnahme ([GPLv3 §1](https://www.gnu.org/licenses/gpl-3.0.html), FAQ
  [#SystemLibraryException](https://www.gnu.org/licenses/gpl-faq.html#SystemLibraryException)) greift nicht, weil das AAR
  in der APK mitgeliefert wird.
- `media3-cast` hängt per `api(libs.gms.play.services.cast.framework)` an GMS
  ([build.gradle.kts Z. 27](https://github.com/androidx/media/blob/release/libraries/cast/build.gradle.kts)) und fällt
  damit ebenfalls weg.

### Protokoll Cast V2 (alles belegt)
1. **Discovery:** mDNS `_googlecast._tcp`. Die TXT-Records liefern `fn` (Name), `md` (Modell) und `id` (UUID) –
   [pychromecast discovery.py Z. 260–266, 677](https://github.com/home-assistant-libs/pychromecast/blob/master/pychromecast/discovery.py).
2. **Verbindung:** TLS auf Port 8009. pychromecast prüft das Zertifikat nicht (`CERT_NONE`, `check_hostname=False`) –
   [socket_client.py Z. 212, 352–354](https://github.com/home-assistant-libs/pychromecast/blob/master/pychromecast/socket_client.py).
3. **Framing:** 4 Byte Länge (Big-Endian) plus Protobuf `CastMessage` (socket_client.py Z. 788, 872). Die Felder
   protocol_version, source_id, destination_id, namespace, payload_type und payload_utf8/binary stehen in
   [cast_channel.proto (Chromium, BSD)](https://chromium.googlesource.com/openscreen/+/refs/heads/main/cast/common/channel/proto/cast_channel.proto).
4. **Namespaces:** `urn:x-cast:com.google.cast.tp.connection`, `.tp.heartbeat`, `.receiver`, `.media` und
   `.tp.deviceauth`. Die App-ID des Default Media Receiver ist `CC1AD845` –
   [VLC chromecast.h Z. 55–59, 70](https://github.com/videolan/vlc/blob/master/modules/stream_out/chromecast/chromecast.h).
   Der Default Media Receiver braucht keine Registrierung –
   [Cast-Doku Web Receiver](https://developers.google.com/cast/docs/web_receiver).
5. **Media-Befehle:** LOAD (`media.contentId` = URL, `contentType`, `streamType`, `autoplay`, `currentTime`), PLAY,
   PAUSE, SEEK, STOP, GET_STATUS (mit `mediaSessionId`) und VOLUME –
   [Cast media messages](https://developers.google.com/cast/docs/media/messages). Die Gerätelautstärke setzt man mit
   `SET_VOLUME` auf `.receiver` –
   [pychromecast receiver.py Z. 264](https://github.com/home-assistant-libs/pychromecast/blob/master/pychromecast/controllers/receiver.py).
6. **Formate:**
   - Container MP3, MP4, OGG, WAV, WebM, MP2T.
   - Audio FLAC (bis 96 kHz/24 bit), AAC, MP3, Opus, Vorbis, LPCM. ALAC, WMA und MKV werden nicht genannt.
   - Bilder JPEG, PNG, WEBP, GIF, BMP, APNG, ideal ≤ 1280×720.
   - Quelle: [Cast Supported Media](https://developers.google.com/cast/docs/media)

### Muss sich der Sender authentifizieren? Nein
- In `DeviceAuthMessage` schickt der *Sender* eine `AuthChallenge`. Das *Gerät* antwortet mit `AuthResponse` samt
  `client_auth_certificate` (cast_channel.proto). Geprüft wird also das Gerät, nicht der Sender.
- pychromecast nutzt `deviceauth` überhaupt nicht: Der Begriff steht im Repo nur in der Proto-Datei und im generierten
  Code ([GitHub-Code-Suche](https://github.com/search?q=repo%3Ahome-assistant-libs%2Fpychromecast+deviceauth&type=code)).
- pychromecast wird aktiv gepflegt (letzter Commit am 16.11.2026) und treibt die Cast-Integration von Home Assistant.
  **Schluss:** Aktuelle Firmwares nehmen nicht authentifizierte Sender weiterhin an. Auf einem echten Gerät habe ich
  das nicht geprüft.

### Bekannte Brüche 2024–2026
- **09.03.2025:** Bei Chromecast 2. Gen und Chromecast Audio lief ein Zwischenzertifikat ab. Folge waren
  „Untrusted device“-Fehler in Google-Sendern. Der Fix kam mit Firmware 1.56.467165/…166 –
  [Google Nest Community, offizieller Thread](https://www.googlenestcommunity.com/t5/Streaming/Regarding-an-issue-with-Chromecast-2nd-gen-and-Chromecast-Audio/m-p/684554).
  **Schluss:** Getroffen hat das die Geräteprüfung der Sender. Ein Sender ohne deviceauth-Prüfung wäre nicht
  betroffen gewesen.
- **pychromecast [#963](https://github.com/home-assistant-libs/pychromecast/issues/963) (10/2024):** Der
  TLS-Handshake scheitert an einem Receiver mit alten Ciphers. Deshalb auf alten Geräten testen.
- Einen Hinweis, dass Google fremde Sender aussperrt, habe ich nicht gefunden: Durchgesehen sind die
  pychromecast-Issues seit 2024. Das ist aber kein Beweis, dass es keine Sperre gibt.

### Play-Policy-Falle: TLS ohne Prüfung
- Ein `X509TrustManager`, der alles akzeptiert, gilt bei Google Play als „unsafe“. Die App kann entfernt werden, und
  ein Update kann in der Prüfung durchfallen – [Play-Hilfe 6346016](https://support.google.com/faqs/answer/6346016).
- **Lösung:** `checkServerTrusted` wirft `CertificateException`, wenn das Zertifikat nicht das für diese Geräte-`id`
  gepinnte ist (Trust on first use).
- Besser wäre die echte deviceauth-Prüfung gegen die Cast-Root-CA. Als Vorbild dient der
  [openscreen cast/common/certificate](https://chromium.googlesource.com/openscreen/+/refs/heads/main/cast/common/certificate/)
  (BSD). Das ist mehr Aufwand.
- **Offen:** Ob Chromecasts ihr Peer-Zertifikat rotieren, muss ein Gerätetest zeigen.

### Referenzen (GitHub-API, Abruf 17.11.2026)
| Repo | Lizenz | letzter Commit | Größe | Bewertung |
|---|---|---|---|---|
| [home-assistant-libs/pychromecast](https://github.com/home-assistant-libs/pychromecast) | MIT | 16.11.2026 | 1,7 MB, 2711★ | beste lebende Referenz (Python) |
| [vitalidze/chromecast-java-api-v2](https://github.com/vitalidze/chromecast-java-api-v2) | Apache-2.0 | 20.11.2020 | 0,4 MB | veraltet; zieht protobuf-java 2.6.0, jackson, jmdns, slf4j ([pom.xml](https://github.com/vitalidze/chromecast-java-api-v2/blob/master/pom.xml)) – nicht einbinden |
| [thibauts/node-castv2](https://github.com/thibauts/node-castv2) | MIT | 04.09.2019 | 56 KB | kompakteste Protokoll-Referenz |
| [VLC stream_out/chromecast](https://github.com/videolan/vlc/tree/master/modules/stream_out/chromecast) | LGPL-2.1+ | aktiv | C++ | Referenz: GPL-Projekt castet ohne GMS |

Laut [FSF-Lizenzliste](https://www.gnu.org/licenses/license-list.html) sind Expat/MIT, Apache-2.0 (nur GPLv3),
Modified BSD und LGPL-2.1 mit GPLv3 kompatibel.

**Schluss:** Eine eigene Implementierung lohnt sich. Der Protobuf-Codec für 6 Felder lässt sich von Hand schreiben,
JSON geht mit `org.json` aus dem SDK. Grob 400–500 Zeilen Kotlin, ohne neue Abhängigkeit.

## 3. DLNA/UPnP (Samsung, LG, Sony, Kodi, AV-Receiver)

- **Ablauf:**
  - SSDP `M-SEARCH` an 239.255.255.250:1900 mit `ST: urn:schemas-upnp-org:device:MediaRenderer:1`.
  - Geräte-XML laden, darin die controlURL für AVTransport und RenderingControl suchen.
  - Dann SOAP-Aufrufe: `SetAVTransportURI`, `Play`, `Pause`, `Stop`, `Seek(REL_TIME)`, `GetPositionInfo` und
    `SetVolume`/`GetVolume`.
  - Specs: [UPnP DA 1.1](https://upnp.org/specs/arch/UPnP-arch-DeviceArchitecture-v1.1.pdf),
    [AVTransport v1](https://upnp.org/specs/av/UPnP-av-AVTransport-v1-Service.pdf),
    [RenderingControl v1](https://upnp.org/specs/av/UPnP-av-RenderingControl-v1-Service.pdf)
- **Metadaten:**
  - Laut Spec darf `CurrentURIMetaData` leer sein („NULL (empty string)“, AVTransport v1 §2.4.1).
  - In der Praxis schickt man trotzdem minimales DIDL-Lite: `dc:title`, `upnp:class`
    (`object.item.audioItem.musicTrack` / `videoItem` / `imageItem`) und
    `<res protocolInfo="http-get:*:<MIME>:*">URL</res>`. Bei Samsung kommt optional `sec:SubtitleUri` dazu.
  - Genau so macht es UPnPCast:
    [MetadataBuilder.kt Z. 60–71](https://github.com/yinnho/UPnPCast/blob/master/app/src/main/java/com/yinnho/upnpcast/internal/util/MetadataBuilder.kt),
    [DIDL-Syntax: ContentDirectory v1](https://upnp.org/specs/av/UPnP-av-ContentDirectory-v1-Service.pdf).
  - `DLNA.ORG_PN`/`DLNA.ORG_FLAGS` und der Header `contentFeatures.dlna.org` stammen aus den nicht öffentlichen
    DLNA-Guidelines. Das lässt sich **nicht an der Quelle belegen**, nur am Gerät testen.
- **Bibliotheken:**
  - **Cling:** „EOL: no longer actively maintained“, LGPL-2.1 oder CDDL –
    [README](https://github.com/4thline/cling/blob/master/README.md). LGPL wäre erlaubt, das Projekt ist aber tot.
  - **jUPnP:** **CDDL-1.0** ([LICENSE.txt](https://github.com/jupnp/jupnp/blob/main/LICENSE.txt)). Die FSF stuft
    das als „incompatible with the GNU GPL“ ein ([#CDDL](https://www.gnu.org/licenses/license-list.html#CDDL)).
    **Ausgeschlossen.**
  - **[yinnho/UPnPCast](https://github.com/yinnho/UPnPCast):** MIT, Kotlin, minSdk 24, v1.3.0, nur über JitPack,
    letzter Commit am 02.09.2026, 41★, nutzt NanoHTTPD. Die Lizenz passt, die Community ist aber klein.
- **Schluss:** Selbst bauen und UPnPCast als Vorlage nehmen (MIT erlaubt Übernahme mit Hinweis). Grob 400 Zeilen.

## 4. Lokale Dateien an den Receiver ausliefern

| Option | Lizenz/Stand | Urteil |
|---|---|---|
| NanoHTTPD | BSD-3, letztes Release 2.3.1 am 12.08.2016, letzter Commit 03.07.2019, 162 offene Issues ([Repo](https://github.com/NanoHttpd/nanohttpd)) | ungepflegt, nein |
| Ktor Server | Apache-2.0, aktiv (3.6.1) ([Repo](https://github.com/ktorio/ktor)) | großer Abhängigkeitsbaum für einen Endpunkt, nein |
| **Eigener Server** | GPL-3 | **ja**, ca. 150–200 Zeilen; Vorlage [UPnPCast LocalFileServer.kt](https://github.com/yinnho/UPnPCast/blob/master/app/src/main/java/com/yinnho/upnpcast/internal/localcast/LocalFileServer.kt) (Token, Range, CORS) |

- **Funktionsumfang:**
  - `GET`/`HEAD` mit `Range: bytes=a-b`.
  - Antworten mit 206, `Content-Range`, `Accept-Ranges` und `Content-Type`, dazu `Access-Control-Allow-Origin`.
    Cast verlangt CORS für ABR-Streams und Untertitel ([Cast Media](https://developers.google.com/cast/docs/media)).
  - **Schluss:** Lesen über die vorhandene Path/`PathDataSource`-Schicht, dann lassen sich auch SMB/SFTP-Dateien casten.
- **Sicherheit:**
  - Nur an die WLAN-IP binden, nicht an 0.0.0.0.
  - Pro Cast einen zufälligen 128-bit-Token-Pfad vergeben, der auf genau einen Path zeigt; alles andere bekommt 404.
  - Kein Verzeichnis-Listing.
  - Nur Anfragen von der IP des gewählten Receivers annehmen.
  - Der Server läuft nur, solange die Session läuft.

## 5. Berechtigungen und Policy

- **Mit targetSdk 36 (heute):** LAN-Zugriff ist über `INTERNET` implizit erlaubt. `ACCESS_LOCAL_NETWORK` darf man
  vor targetSdk 37 **weder deklarieren noch anfragen** –
  [Local Network Permission](https://developer.android.com/privacy-and-security/local-network-permission).
- **`NEARBY_WIFI_DEVICES` ist nicht nötig.** Die Berechtigung gilt nur für Hotspot-, Wi-Fi-Aware-, P2P- und RTT-APIs,
  nicht für Sockets oder NSD – [Wi-Fi permissions](https://developer.android.com/develop/connectivity/wifi/wifi-permissions).
- **Ab targetSdk 37 (Android 17):**
  - Die Laufzeitberechtigung `ACCESS_LOCAL_NETWORK` (Gruppe NEARBY_DEVICES) wird Pflicht. Sie gilt für ausgehende
    *und eingehende* TCP-Verbindungen, UDP-Multicast und `NsdManager`.
  - **Das trifft auch den vorhandenen FTP-Server.**
  - `DiscoveryRequest.FLAG_SHOW_PICKER` (API 37) erlaubt mDNS-Auswahl und Verbindung ohne die Berechtigung –
    [DiscoveryRequest](https://developer.android.com/reference/android/net/nsd/DiscoveryRequest).
  - Der eingehende HTTP-Abruf vom TV und SSDP brauchen sie trotzdem (Tabelle „Accepting an incoming TCP connection:
    yes“). **Schluss:** Lokale Dateien casten geht bei target 37 nur mit `ACCESS_LOCAL_NETWORK`.
  - Die Output-Switcher-Ausnahme gilt nur für Google Cast über das SDK.
- **Multicast:** Für SSDP braucht es `CHANGE_WIFI_MULTICAST_STATE` (normale Berechtigung) und einen
  `WifiManager.MulticastLock` – [Referenz](https://developer.android.com/reference/android/net/wifi/WifiManager.MulticastLock).
- **Foreground-Service:**
  - `mediaPlayback` („Continue audio or video playback from the background“) passt und ist bereits deklariert –
    [FGS-Typen](https://developer.android.com/develop/background-work/services/fgs/service-types).
  - Die FGS-Typen müssen in der Play Console unter App-Inhalte deklariert sein (gleiche Seite).
  - **Schluss:** Beim Casten wirkt `WAKE_MODE_NETWORK` nicht, denn ExoPlayer spielt dann nicht. Für den Dateiserver
    braucht es deshalb selbst einen WifiLock und einen Partial-WakeLock.
- **Play:**
  - TrustManager wie in Abschnitt 2 beschrieben.
  - Data Safety: Es fließen keine Daten an den Entwickler, alles bleibt im LAN.

## 6. Media3-Integration

- `media3-cast`/`CastPlayer` braucht GMS (siehe 2) und fällt weg.
- **Eigener `SimpleBasePlayer`** ([Referenz](https://developer.android.com/reference/androidx/media3/common/SimpleBasePlayer)):
  - Implementieren: `getState()`, `handleSetPlayWhenReady`, `handleSeek`, `handleStop`, `handleSetMediaItems` und
    `handleSetDeviceVolume`
    ([SimpleBasePlayer.java](https://github.com/androidx/media/blob/release/libraries/common/src/main/java/androidx/media3/common/SimpleBasePlayer.java)).
  - `DeviceInfo` auf `PLAYBACK_TYPE_REMOTE` setzen. Dann ruft Media3 `setPlaybackToRemote(VolumeProviderCompat)` auf,
    und die Lautstärketasten steuern den TV –
    [MediaSessionLegacyStub Z. 1717–1730, 2076 ff.](https://github.com/androidx/media/blob/release/libraries/session/src/main/java/androidx/media3/session/MediaSessionLegacyStub.java).
- **Player tauschen:**
  - `MediaSession.setPlayer(Player)` ([MediaSession.java Z. 984](https://github.com/androidx/media/blob/release/libraries/session/src/main/java/androidx/media3/session/MediaSession.java)),
    oder ab 1.8.0 `ForwardingSimpleBasePlayer` mit austauschbarem Player.
  - `PlayerTransferState` (ab 1.9.0) übernimmt Queue und Position –
    [RELEASENOTES](https://github.com/androidx/media/blob/release/RELEASENOTES.md).
  - Benachrichtigung und Sperrbildschirm laufen weiter, weil sie an der Session hängen.
- **Schluss:** Ordos eigene Bausteine wirken remote nicht, weil sie am ExoPlayer hängen: AudioFocusController,
  ReplayGain, Mono, Klangeffekte und das Ausfaden des Sleep-Timers. Cast meldet den Status per Push (MEDIA_STATUS),
  DLNA muss man pollen (GetPositionInfo etwa 1 s).
- Den Metadaten-`ForwardingPlayer` (Z. 207) muss man um beide Player legen.

---

## Empfehlung: Phasen, kleinste zuerst

| Phase | Inhalt | Aufwand | Lizenz | Testen am echten Gerät |
|---|---|---|---|---|
| **0** | Button „Ausgabe wählen“: `mediarouter:1.8.1` + `SystemOutputSwitcherDialogController.showDialog()`, Fallback BT-Einstellungen unter API 30 | 0,5 Tag | Apache-2.0 ✔ | Pixel (16/17), Samsung One UI (API-31–33-Pfad über SystemUI-Broadcast!), Android-10-Gerät (Fallback), BT-Kopfhörer: Tasten, Abziehen, Wechsel während der Wiedergabe |
| **1** | Grundgerüst: Mini-HTTP-Server (Range, Token, IP-Bindung) + `RemotePlayer : SimpleBasePlayer` + Player-Tausch + WifiLock/WakeLock | 2–3 Tage | eigen, GPL-3 ✔ | Unit-Tests für Range-Parser und Token; Sperrbildschirm und Benachrichtigung beim Tausch |
| **2** | Chromecast-Sender: NsdManager, TLS mit TOFU-Pinning, Protobuf von Hand, Default Media Receiver | 3–5 Tage | eigen, Referenzen MIT/BSD ✔ | Chromecast 2./3. Gen, Chromecast mit Google TV, Google TV Streamer, TV mit eingebautem Cast, Nest-Lautsprecher; FLAC/Opus/ALAC; Seek; 30 min Bildschirm aus; WLAN-Wechsel; rotiert das Peer-Zertifikat?; Play-Pre-Launch-Report (TrustManager) |
| **3** | DLNA-Renderer: SSDP + SOAP + DIDL-Lite | 3–5 Tage | eigen, Vorlage UPnPCast MIT ✔ | Samsung Tizen, LG webOS, Sony Bravia, Kodi, AV-Receiver; Metadaten leer vs. DIDL; braucht der Renderer DLNA-Header? |
| **4** | Bilder und Video über dieselbe Remote-Schicht; nicht unterstützte Formate sperren statt transkodieren | je 2–3 Tage | ✔ | Bildgröße ≤ 1280×720, Video-Codecs je Gerät |
| **später** | targetSdk 37: Ablauf für `ACCESS_LOCAL_NETWORK` (betrifft auch den FTP-Server), für Cast optional `FLAG_SHOW_PICKER` | 1–2 Tage | – | Android 17: Ablehnen, Entziehen, erneut fragen |

**Nicht geprüft:**
- Ob eigene `MediaRouteProviderService`-Routen ohne GMS im System-Output-Switcher für die eigene Session erscheinen.
  Bis zu einem Gerätetest weglassen.
- Echtes Geräteverhalten von Cast und DLNA. Hier ist alles nur aus Quellen abgeleitet.
