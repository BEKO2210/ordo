# Play-Berechtigungen für Ordo

> Entwurf von Grok 4.7 (über Cursor), 15.11.2026. Stichprobe gegen die Quelle geprüft: „All files access“
> (Dateimanager sind erlaubter Kernzweck, Formular nötig) und Vordergrunddienste (Beschreibung und Video je Typ;
> Google ordnet Datenübertragung `dataSync` zu). Alle übrigen Angaben vor dem Einreichen noch einmal an der Quelle prüfen.

Stand: 15. November 2026. Gelesen an diesem Tag. Paket `com.beko2210.ordo`, `versionName` 0.3.2, `versionCode` 5 (`app/build.gradle`). Branch `docs/play-permissions`. Kein Commit, kein Code geändert.

Quellen nur aus dem Quelltext dieses Worktrees und aus Seiten unter `support.google.com/googleplay/android-developer` oder `developer.android.com`. Wo eine Angabe fehlt, steht **nicht geprüft**.

`llm/src/main/AndroidManifest.xml` ist nicht vorhanden. `llm` ist ein Android-Library-Modul ohne eigenes Manifest (`llm/build.gradle`). Es wurde kein Build ausgeführt. Berechtigungen, die erst im zusammengeführten Manifest aus AARs landen, sind **nicht geprüft**. Im App-Manifest wird `com.google.firebase.provider.FirebaseInitProvider` mit `tools:node="remove"` entfernt.

Play Console zeigt Installationsberechtigungen aus dem Manifest. Das Data-safety-Formular fragt getrennt, was die App erhebt und weitergibt. Beides steht unten.

Zeichenlimits der Formularfelder: **nicht geprüft**.

## 1. Inventar aus `app/src/main/AndroidManifest.xml`

### Berechtigungen

| Berechtigung | Anmerkung im Manifest |
| --- | --- |
| `ACCESS_NETWORK_STATE` | — |
| `ACCESS_WIFI_STATE` | — |
| `FOREGROUND_SERVICE` | `tools:ignore="ForegroundServicesPolicy"` |
| `FOREGROUND_SERVICE_DATA_SYNC` | — |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | — |
| `FOREGROUND_SERVICE_SPECIAL_USE` | — |
| `INTERNET` | — |
| `MANAGE_EXTERNAL_STORAGE` | `tools:ignore="AllFilesAccessPolicy,ScopedStorage"` |
| `POST_NOTIFICATIONS` | — |
| `QUERY_ALL_PACKAGES` | `tools:ignore="PackageVisibilityPolicy,QueryAllPackagesPermission"` |
| `REQUEST_INSTALL_PACKAGES` | `tools:ignore="RequestInstallPackagesPolicy"` |
| `REQUEST_DELETE_PACKAGES` | — |
| `WAKE_LOCK` | — |
| `WRITE_EXTERNAL_STORAGE` | `android:maxSdkVersion="32"` |
| `com.android.launcher.permission.INSTALL_SHORTCUT` | — |

Nicht im Manifest: Standort (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`), Fotos/Videos (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`), `READ_EXTERNAL_STORAGE`, USB-Berechtigung, Bluetooth, Kamera, Mikrofon, SMS, Kontakte, `USE_FULL_SCREEN_INTENT`, `SCHEDULE_EXACT_ALARM`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Eine Local-Network-Berechtigung ist ebenfalls nicht deklariert. In `app/build.gradle` steht der Kommentar, `ACCESS_LOCAL_NETWORK` werde noch nicht unterstützt, und `targetSdk` bleibt 36.

`uses-feature`, alle `android:required="false"`: `android.hardware.touchscreen`, `android.hardware.wifi`, `android.software.leanback`.

### Vordergrunddienste

| Komponente | `foregroundServiceType` | exportiert |
| --- | --- | --- |
| `me.zhanghai.android.files.filejob.FileJobService` | `dataSync` | Attribut fehlt, kein `intent-filter` |
| `me.zhanghai.android.files.player.AudioPlaybackService` | `mediaPlayback` | `true`, `androidx.media3.session.MediaSessionService` |
| `me.zhanghai.android.files.ftpserver.FtpServerService` | `specialUse` | Attribut fehlt, kein `intent-filter` |
| `me.zhanghai.android.files.ftpserver.FtpServerTileService` | keines | `true`, dazu `android.permission.BIND_QUICK_SETTINGS_TILE` |
| `androidx.appcompat.app.AppLocalesMetadataHolderService` | keines | `false` |

`FtpServerService` hat als `specialUse`-Dienst `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` gesetzt (Text unten beim Dienst). `USE_FULL_SCREEN_INTENT` kommt nicht vor.

`startForeground` wird ohne Typ-Argument aufgerufen: `ForegroundNotificationManager.kt` (`service.startForeground(id, notification)`) und `FtpServerNotification.kt` (`service.startForeground(NotificationIds.FTP_SERVER, notification)`). Dann gilt der Typ aus dem Manifest: `Service.startForeground(int, Notification)` übergibt `FOREGROUND_SERVICE_TYPE_MANIFEST` (Quelltext `android/app/Service.java`, SDK-Quellen android-36.1). Auf einem Gerät **nicht geprüft**.

### Exportierte Komponenten (`android:exported="true"`)

| Komponente | Filter |
| --- | --- |
| `home.HomeActivity` | `MAIN` / `LEANBACK_LAUNCHER` |
| `launcher.Home*`, `launcher.Music*`, `launcher.Video*` (`activity-alias`, je Icon-Farbe eines) | `MAIN` / `LAUNCHER`; nur das gewählte ist eingeschaltet, im Manifest nur `launcher.HomeAlias` |
| `filelist.FileListActivity` | `VIEW` für Ordner-MIME-Typen und Archive, `GET_CONTENT`, `OPEN_DOCUMENT`, `CREATE_DOCUMENT`, `me.zhanghai.android.files.intent.action.VIEW_DOWNLOADS`. Zusätzlich `android:visibleToInstantApps="true"` |
| `filelist.OpenFileActivity` | `me.zhanghai.android.files.intent.action.OPEN_FILE` |
| `ftpserver.FtpServerActivity` | `me.zhanghai.android.files.intent.action.MANAGE_FTP_SERVER`, `android.service.quicksettings.action.QS_TILE_PREFERENCES` |
| `settings.SettingsActivity` | `APPLICATION_PREFERENCES` |
| `documents.OrdoDocumentsProvider` (Provider, `${applicationId}.documents`) | `android.content.action.DOCUMENTS_PROVIDER`; `android:permission="android.permission.MANAGE_DOCUMENTS"`, also nur die System-Dateiauswahl. Nur lesen: Lesezeichen-Ordner im geteilten Speicher, kanonische Pfade, nie `/data` oder `Android/data|obb|media`, nie der Tresor |
| `viewer.saveas.SaveAsActivity` | `VIEW` mit `content`-Schema, `SEND` |
| `viewer.text.TextEditorActivity` | `VIEW` für Text- und verwandte MIME-Typen |
| `viewer.image.ImageViewerActivity` | `VIEW` `image/*` |
| `viewer.pdf.PdfViewerActivity` | `VIEW` `application/pdf` |
| `viewer.video.VideoPlayerActivity` | `VIEW` `video/*` (nur `content://` mit Lese-Grant) |
| `ftpserver.FtpServerTileService` | `android.service.quicksettings.action.QS_TILE` |
| `rikka.shizuku.ShizukuProvider` | kein Filter. `android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"`. Ordo fordert diese Berechtigung nicht selbst an |

`exported="false"`: `home.OnboardingActivity`, `AppLocalesMetadataHolderService`, `app.AppProvider`, `file.FileProvider` (`grantUriPermissions="true"`).

Viele weitere Activities sowie `filejob.FileJobReceiver` und `ftpserver.FtpServerReceiver` setzen `exported` nicht und haben keinen `intent-filter`. Der Plattform-Default dafür ist **nicht geprüft** (die Manifest-Seite zum Default wurde nicht einzeln gelesen).

### `<queries>`

Kein `<queries>`-Element im App-Manifest.

### Weitere Attribute

- `android:requestLegacyExternalStorage="true"`
- `android:requestRawExternalStorageAccess="true"`
- `android:usesCleartextTraffic="true"` und `res/xml/netework_security_config.xml`: `cleartextTrafficPermitted="true"`, Trust-Anchors `system` und `user`
- `android:allowBackup="true"`. `res/xml/backup_rules.xml` und `res/xml/data_extraction_rules.xml` schließen `sharedpref`, `file`, `database` und `external` von Cloud-Backup und Geräte-Transfer aus. Ob das jede Sicherung ausschließt, ist **nicht geprüft**
- `android:enableOnBackInvokedCallback="true"`

## 2. Target-API

`minSdk` 23, `targetSdk` 36, `compileSdk` 37 (`app/build.gradle`). `llm` hat `compileSdk` 37 und `minSdk` 23, kein eigenes `targetSdk` im gelesenen `llm/build.gradle`.

Gelesen am 15. November 2026: [Meet Google Play's target API level requirement](https://developer.android.com/google/play/requirements/target-sdk). Ab 31. August 2026 müssen neue Apps und Updates für Telefon/Tablet Android 16 (API 36) oder höher angeben. Wear OS und Android Automotive: API 35. Android TV und Android XR: API 34. Eine Verlängerung bis 1. November 2026 war vorgesehen. Dieselbe Frist steht in [Target API level requirements for Google Play apps](https://support.google.com/googleplay/android-developer/answer/11926878). Ob die Verlängerung am 15. November 2026 noch offen ist, sagt diese Seite nicht mehr eindeutig. Der hochgeladene Stand nennt bereits API 36.

Das Manifest hat `LEANBACK_LAUNCHER` und `android:banner`. Ob die App in der Play Console als Android-TV-App geführt wird, ist **nicht geprüft**.

## 3. 16-KB-Seiten

Gelesen am 15. November 2026: [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes). Apps mit Target Android 15 (API 35) oder höher müssen auf 64-Bit-Geräten 16-KB-Seiten unterstützen. Ab 1. Februar 2027 lassen sich Updates ohne diese Unterstützung nicht veröffentlichen. NDK r28 und höher erzeugt 16-KB-ELF-Alignment standardmäßig. Vorgefertigte Bibliotheken müssen einzeln passen. `useLegacyPackaging true` packt native Bibliotheken komprimiert. Das ist auf der Seite die Alternative, wenn Zip-Alignment der unkomprimierten `.so` nicht stimmt. AGP 8.5.1 oder höher richtet unkomprimierte Bibliotheken an 16 KB aus.

Im Repo: AGP `com.android.tools.build:gradle:9.4.1` (`build.gradle`). NDK `30.0.16248370` in `app/build.gradle` und `llm/build.gradle`. `packagingOptions.jniLibs.useLegacyPackaging true` in `app/build.gradle`. Eigene Bibliotheken: `app/CMakeLists.txt` (`hiddenapi`, `syscall`), `llm/src/main/cpp/CMakeLists.txt` (llama.cpp, nur `arm64-v8a` und `x86_64`). `llm/src/main/cpp/CMakeLists.txt` setzt für `libordo_llm.so` fest `-Wl,-z,max-page-size=16384`; NDK 30 tut das ohnehin, der Flag hält es auch bei einem anderen NDK. Abhängigkeiten mit nativem Code, im Gradle sichtbar, nicht inspiziert: `me.zhanghai.android.libarchive:library:1.1.7`, `me.zhanghai.android.libselinux:library:2.1.1`.

Prüfung am gebauten Paket: `utils/check-16k.sh <apk|aab>` (braucht NDK und Build-Tools, z. B. auf pop-os). Es prüft jede `lib/arm64-v8a/*.so` mit `llvm-readelf -l` (jedes `LOAD`-Segment mindestens `0x4000`) und beim APK das Zip-Alignment mit `zipalign -c -P 16 -v 4`. Gemessen am 15. November 2026 auf pop-os, `app-beta.apk` aus Branch `build/16k-pages`: alle fünf Bibliotheken (`libarchive-jni`, `libhiddenapi`, `libordo_llm`, `libselinux-jni`, `libsyscall`) haben `LOAD align 0x4000`, alle komprimiert gepackt, Ergebnis `16 KB: OK`. Gegenprobe: eine mit `max-page-size=4096` gelinkte Test-`.so` meldet das Skript als `FAIL`. Ein AAB (`bundleRelease`) ist **nicht geprüft**; die Release-Signatur aus `docs/ROADMAP.md`, P3.4, bleibt offen.

## 4. `MANAGE_EXTERNAL_STORAGE` (All files access)

**(a)** Gelesen am 15. November 2026: [Use of All files access](https://support.google.com/googleplay/android-developer/answer/10467955) und [Permissions and APIs that Access Sensitive Information](https://support.google.com/googleplay/android-developer/answer/16558241). Für Apps mit Target ab API 30 ist `MANAGE_EXTERNAL_STORAGE` nur zulässig, wenn Storage Access Framework oder MediaStore die Kernfunktion nicht tragen. Dateimanager sind ein genannter zulässiger Zweck: Zugriff, Bearbeiten und Verwalten von Dateien und Ordnern außerhalb des app-eigenen Speichers. Die Kernfunktion muss in der Store-Beschreibung stehen. Ohne genehmigtes Formular in der Play Console kann die App entfernt werden. Die App muss den Nutzer zur Systemseite „All files access“ schicken. Dieselbe Pflicht steht in der Sensitive-Information-Seite.

**(b)** Die Berechtigung steht im Manifest. `Environment.isExternalStorageManager()` wird in `home/OnboardingActivity.kt`, `home/HomeFragment.kt` und `filelist/FileListFragment.kt` geprüft. Die Systemseite öffnet `util/EnvironmentExtensions.kt` über `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`. `provider/linux/LinuxPath.kt` beschreibt, dass `MANAGE_EXTERNAL_STORAGE` den Zugriff auf die Volumes freigibt. `storage/DocumentTree.kt` kommentiert, dass damit alle Volumes genutzt werden, wenn die Plattform das hergibt. `WRITE_EXTERNAL_STORAGE` gilt nur bis API 32.

**(c)** Paste-Text:

```
Ordo is a file manager. Its core function is to list, open, copy, move, rename, and delete files and folders on shared storage, including directories outside the app's own folder. The Storage Access Framework and MediaStore cannot replace that, because the user manages whole directories rather than picking one document at a time. The app checks Environment.isExternalStorageManager() and sends the user to the system All files access screen. The user can refuse. The app does not grant this access to other apps.
```

**(d)** Ablehnungsrisiko: hoch, wenn die Store-Beschreibung den Dateimanager nicht als Kern nennt oder die Begründung nach SAF klingt. Wenn Google ablehnt, `MANAGE_EXTERNAL_STORAGE`, `requestLegacyExternalStorage` und `requestRawExternalStorageAccess` entfernen und nur noch über den Document-Tree bzw. den System-Picker arbeiten. Dann fehlen beliebige Ordner und der direkte Pfadzugriff, den `LinuxPath` an diese Freigabe bindet. `WRITE_EXTERNAL_STORAGE` (max. API 32) ist eine eigene, ältere Berechtigung. Die gelesene All-files-Seite sagt, sie gelte nicht für `READ_EXTERNAL_STORAGE`. Eine eigene Aussage zu `WRITE_EXTERNAL_STORAGE` mit `maxSdkVersion` 32 steht dort nicht.

## 5. `QUERY_ALL_PACKAGES`

**(a)** Gelesen am 15. November 2026: [Use of the broad package (App) visibility permission](https://support.google.com/googleplay/android-developer/answer/10158779) und die Package-Visibility-Abschnitt in [16558241](https://support.google.com/googleplay/android-developer/answer/16558241). Die Liste installierter Apps gilt als personenbezogene Daten. `QUERY_ALL_PACKAGES` wirkt ab Target API 30 auf Android 11 und ist nur zulässig, wenn die Kernfunktion jede installierte App sehen muss. Genannte Zwecke: Gerätesuche, Virenschutz, Dateimanager, Browser. Eine engere `<queries>`-Abfrage ist zu nehmen, wenn sie reicht. Die Liste darf nicht verkauft und nicht für Werbung oder Analytics weitergegeben werden. Deklaration in der Play Console.

**(b)** Kein `<queries>`. Aufrufe von `getInstalledApplications(0)`:

- `apps/AppsViewModel.kt`: Liste der Apps, Name, Version, APK-Pfade, optional System-Apps, Kopieren der APKs nach `Download/Ordo APKs`
- `home/HomeRepository.kt`: Zähler der Nicht-System-Apps auf der Startseite
- `fileproperties/permission/PrincipalListLiveData.kt`: Zuordnung von UID zu App-Namen in den Dateirechten

`terminal/Terminal.kt` sucht per `queryIntentActivities` eine Activity, deren Name auf `.TermHere` endet, und fällt sonst auf `jackpal.androidterm` zurück. Das ist eine gezielte Suche, keine Anzeige aller Apps. `coil/AppIconPackageNameFetcherFactory.kt` lädt Icons über `getApplicationInfo` mit `MATCH_UNINSTALLED_PACKAGES`.

**(c)** Paste-Text:

```
Ordo is a file manager. It lists installed apps on the device so the user can see each app's APK files, copy those APKs to Download/Ordo APKs, and see which app owns a file in the permissions screen. The home screen shows a count of non-system apps. A finite <queries> list cannot show apps the user installed later. The inventory stays on the device. Ordo has no ads and no analytics SDK, and this list is not sold or sent to the developer.
```

**(d)** Ablehnungsrisiko: mittel. Die Richtlinie erlaubt Dateimanager, verlangt aber, dass die breite Sichtbarkeit Kern der App ist. Wenn Google nur den Dateizugriff als Kern wertet, die Berechtigung entfernen. Die App-Liste, der Zähler und die UID-Namen fallen dann auf das, was Android ohne die Berechtigung noch zurückgibt (auf API 30+ im Wesentlichen die eigene App und wenige sichtbare Pakete; das genaue Restverhalten ist **nicht geprüft**). APK-Dateien, die der Nutzer im Dateisystem öffnet, laufen über `getPackageArchiveInfo` (`compat/PackageManagerCompat.kt`) und brauchen diese Berechtigung nicht. Für das Terminal reicht eine `<queries>`-Angabe auf `ACTION_SEND` oder das feste Paket `jackpal.androidterm`.

## 6. `REQUEST_INSTALL_PACKAGES`

**(a)** Gelesen am 15. November 2026, Abschnitt „Request Install Packages Permission“ in [16558241](https://support.google.com/googleplay/android-developer/answer/16558241). Die Berechtigung ist auf die Kernfunktion beschränkt: Pakete senden oder empfangen und eine vom Nutzer angestoßene Installation ermöglichen. Dateifreigabe, -transfer oder -verwaltung ist ein genannter zulässiger Zweck. Selbst-Updates, stille Installation und mitgelieferte APKs sind unzulässig, außer Geräteverwaltung. Ab Android 8 braucht `Intent.ACTION_INSTALL_PACKAGE` diese Berechtigung. Jede Installation muss vom Nutzer ausgehen. Deklaration in der Play Console. Eine eigene Help-Seite nur für diese Berechtigung wurde nicht abgerufen.

**(b)** `util/IntentExtensions.kt` baut `ACTION_INSTALL_PACKAGE` für den MIME-Typ APK. `filelist/FileListFragment.kt` (`installApk`) startet das, wenn der Nutzer eine APK öffnet. Liegt die Datei in einem Archiv, übernimmt `filejob/FileJobs.kt` (`InstallApkJob`) und öffnet denselben Intent, nachdem die Datei herauskopiert wurde. Der Intent geht an den System-Installer. Zusätzlich prüft `provider/linux/LinuxPath.kt` die App-Op `REQUEST_INSTALL_PACKAGES`: nur mit dieser Freigabe gilt der Zugriff auf `Android/obb` anderer Pakete als erlaubt. Das ist Plattformverhalten, das der Code kommentiert, keine zweite Installation.

**(c)** Paste-Text:

```
Ordo is a file manager. When the user opens an APK in the file list, Ordo hands that file to the system package installer with ACTION_INSTALL_PACKAGE. Ordo does not install, update, or bundle packages by itself. On Android 11 and later the same user grant is what lets a file manager read Android/obb directories other than its own. Installation still requires the user to confirm in the system installer.
```

**(d)** Ablehnungsrisiko: mittel. Wenn Google die Installation nicht als Teil des Dateimanagers akzeptiert, die Berechtigung und `createInstallPackageIntent` entfernen. APKs lassen sich dann nicht mehr aus Ordo heraus installieren. `LinuxPath` erlaubt `Android/obb` fremder Apps dann nicht mehr über diese App-Op. Nur das eigene OBB-Verzeichnis bliebe über den Pfadvergleich übrig.

## 7. Vordergrunddienste, Typen `dataSync`, `mediaPlayback` und `specialUse`

**(a)** Gelesen am 15. November 2026:

- [Understanding foreground service and full-screen intent requirements](https://support.google.com/googleplay/android-developer/answer/13392821). Ab Android 14 Typ und passende Berechtigung deklarieren. Apps mit Target ab Android 14 tragen die Typen auf der Seite App content ein: Beschreibung, Auswirkung wenn das System die Aufgabe verschiebt oder unterbricht, Video, das die Nutzeraktion zeigt. `TYPE_SPECIAL_USE` nur in begrenzten Fällen, wenn kein definierter Typ passt. Alle Typen werden geprüft. In der Tabelle der Seite stehen unter anderem `TYPE_DATA_SYNC` für vom Nutzer gestartetes Sichern, Hoch- und Herunterladen und lokales Importieren/Exportieren. `specialUse` steht in dieser Tabelle nicht. `USE_FULL_SCREEN_INTENT` nutzt Ordo nicht.
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types). `dataSync` beschreibt Datenübertragung, darunter Sichern und Wiederherstellen, Import und Export, „Local file processing“ und Übertragen über ein Netzwerk. `specialUse` gilt nur für Fälle, die kein anderer Typ abdeckt. `specialUse` braucht `FOREGROUND_SERVICE_SPECIAL_USE` und `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. Diesen Text prüft die Play Console. Dieselbe Pflicht, die Typen in der Play Console zu deklarieren, steht dort für Target ab Android 14.
- [Foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout). Für Apps mit Target ab Android 15 (API 35) dürfen `dataSync` und `mediaProcessing` zusammen 6 Stunden in 24 Stunden laufen, solange die App im Hintergrund ist. Danach `onTimeout`. Kommt die App in den Vordergrund, läuft der Zähler neu. `specialUse` nennt diese Seite nicht als von diesem Limit betroffen.

Ordo deklariert `dataSync` für `FileJobService`, `mediaPlayback` für `AudioPlaybackService` und `specialUse` für `FtpServerService`, dazu `FOREGROUND_SERVICE` und die drei typspezifischen Berechtigungen. `DocsConsistencyTest` prüft, dass jeder Typ seine Berechtigung hat und hier richtig steht. Der Kachel-Dienst ist ein Quick-Settings-Tile ohne `foregroundServiceType`.

### `AudioPlaybackService`

Der Nutzer startet die Wiedergabe durch Antippen einer Audiodatei. Media3 hält Player und Session im Dienst, zeigt die Standard-Medienbenachrichtigung und liefert Sperrbildschirm- und Headset-Steuerung. Schließen der Player-Ansicht beendet die Musik nicht. Warteschlange, Titelposition und Abspielposition werden für Medienknopf-Wiederaufnahme gespeichert. Ein Start aus `BOOT_COMPLETED` ist nicht vorhanden.

Play-Console-Text:

```
The user starts music playback by tapping an audio file in Ordo. AudioPlaybackService keeps that user-selected track playing when the player screen is closed and provides the standard Android media notification, lock-screen controls, and headset buttons. The service stops being foreground playback when Media3 no longer considers playback ongoing.
```

Auswirkung einer Unterbrechung:

```
If the system stops the service, playback stops. The saved queue and position let the user resume it with a media button or by opening the player again.
```

### `FileJobService`

**(b)** `filejob/FileJobService.kt` hält laufende Dateiaufträge und zeigt sie über `ForegroundNotificationManager` an. Aufträge in `FileJobs.kt`: Kopieren, Verschieben, Löschen, Archiv packen und entpacken, Sichern und Wiederherstellen, Tresor, Papierkorb, APK installieren. Alle startet der Nutzer in der App; einen Start aus `BOOT_COMPLETED` oder einem Zeitplan gibt es nicht. Typ `dataSync`. Ab Android 15 ruft das System nach 6 Stunden im Hintergrund `onTimeout(int, int)`; der Dienst zeigt dann die Benachrichtigung „Auftrag abgebrochen“ und ruft `stopSelf()`, `onDestroy` bricht die laufenden Aufträge ab. Eine abgebrochene Sicherung oder Wiederherstellung zeigt in Ordo „Sicherung nicht abgeschlossen“ bzw. „Wiederherstellung abgebrochen“ mit demselben Hinweis, nicht den Startbildschirm.

**(c)** Anwendungsfall im Formular: `TYPE_DATA_SYNC`, „Local processing: Other“. Paste-Text, Beschreibung:

```
FileJobService runs a file operation the user started in Ordo: copy, move, delete, archive, backup or restore, also to or from a network folder the user added. A notification shows the progress until the operation finishes or the user cancels it.
```

Auswirkung, wenn das System verschiebt oder unterbricht:

```
If the system defers or stops the service, the operation does not finish. Files already written stay as they are. The user has to start the operation again.
```

### `FtpServerService`

**(b)** `ftpserver/FtpServerService.kt`: `START_STICKY`, Wifi-Lock, Benachrichtigung mit Adresse (`FtpServerNotification`, Text aus `FtpServerUrl.getUrl()`) und Stop-Aktion. Der Subtype-Text im Manifest sagt, der Nutzer erwarte, dass der Server tagelang läuft, bis er ihn beendet. Im Code endet der Server, wenn der Dienst zerstört oder die Stop-Aktion ausgeführt wird. Eine Laufzeit über Tage ist nicht gemessen.

**(c)** Paste-Text, Beschreibung:

```
The user starts an FTP server in Ordo to serve files from the device on the local network until the user stops it. The notification shows the server address and a stop action. dataSync cannot stay running for the whole time the user left the server on, because of the 6-hour background limit for apps targeting Android 15 or higher. The service therefore declares specialUse.
```

Auswirkung:

```
If the system defers or stops the service, other devices cannot reach the files until the user starts the FTP server again.
```

specialUse: Entscheidung 15.11.2026: `FileJobService` nutzt `dataSync`, weil Android und Play Kopieren, Sichern, Import/Export und Netzübertragung genau diesem Typ zuordnen und `specialUse` nur gilt, wenn kein Typ passt; die 6 Stunden zählen nur im Hintergrund und starten neu, sobald Ordo im Vordergrund ist. `FtpServerService` bleibt `specialUse`: der Server wartet, bis der Nutzer ihn beendet, auch ohne Übertragung, und `dataSync` würde ihn nach 6 Stunden im Hintergrund beenden. Begründung für die Play Console ist der Paste-Text zu `FtpServerService` oben; im Manifest steht dazu `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`.

**(d)** `FileJobService`: Ablehnungsrisiko gering, der Anwendungsfall steht in der Play-Tabelle unter `TYPE_DATA_SYNC`. Folge: ein Auftrag, der ab Android 15 länger als 6 Stunden am Tag im Hintergrund läuft, bricht ab; Ordo meldet das per Benachrichtigung, der Nutzer startet ihn neu. `FtpServerService`: Ablehnungsrisiko hoch, denn Play nennt `specialUse` nur für Fälle, die kein definierter Typ abdeckt. Wenn Google ablehnt, auf `dataSync` umstellen (wie `FileJobService`); der FTP-Server liefe dann höchstens 6 Stunden am Tag im Hintergrund.

Das Formular verlangt ein Video pro Typ. Die Aufnahme macht Belkis. Inhalt laut Play-Seite: die Nutzeraktion, die den Dienst startet. Für die Dateiaufträge ein Kopieren oder Verschieben starten und die Benachrichtigung zeigen. Für Musik eine Audiodatei antippen, die Wiedergabe im Hintergrund und die Medienbenachrichtigung zeigen. Für den FTP-Server den Server starten, die Benachrichtigung mit Adresse und Stop zeigen.

## 8. `POST_NOTIFICATIONS`

**(a)** Gelesen am 15. November 2026: [Notification runtime permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission). Ab Android 13 ist `POST_NOTIFICATIONS` eine Laufzeitberechtigung für nicht ausgenommene Benachrichtigungen, einschließlich Vordergrunddiensten. Die Seite sagt auch: zum Starten eines Vordergrunddienstes ist die Berechtigung nicht nötig, die Benachrichtigung aber schon. Auf den gelesenen Play-Seiten (All files, Package visibility, Install packages, Foreground services, Sensitive permissions) gibt es kein eigenes Deklarationsformular für `POST_NOTIFICATIONS`.

**(b)** Manifest. `filelist/FileListFragment.kt` (`ensureNotificationPermission`) fragt die Berechtigung ab API 33 ab, mit Begründungsdialog `ShowRequestNotificationPermissionRationaleDialogFragment`. Genutzt wird sie für die Benachrichtigungen von `FileJobService` und `FtpServerService`. Der Modell-Download setzt zusätzlich `DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED` (`ai/CommandModel.kt`). Das ist die System-Download-Benachrichtigung.

**(c)** Kein Play-Paste-Text. Die Vordergrund-Benachrichtigungen gehören in die Deklarationen aus Abschnitt 7.

**(d)** Wenn die Berechtigung fehlt oder der Nutzer sie verweigert, zeigt Android die Benachrichtigungen nicht. Die Dienste bleiben im Manifest an eine sichtbare Benachrichtigung gebunden. Die Berechtigung zu entfernen verschlechtert die Sichtbarkeit der laufenden Aufträge. Eine Play-Ablehnung nur wegen dieser Berechtigung ist in den gelesenen Seiten nicht beschrieben.

## 9. Standort, Medien, USB, Netz

**Standort.** Keine Standortberechtigung im Manifest. Die Sensitive-Information-Seite beschreibt Standort-Deklarationen erst für Target API 37 und für `ACCESS_FINE_LOCATION`. Ordo hat Target 36 und fordert die Berechtigung nicht an. Nichts einzutragen, solange das Manifest so bleibt.

**Fotos und Videos.** Dieselbe Seite: `READ_MEDIA_IMAGES` und `READ_MEDIA_VIDEO` ab Target API 33 nur mit Deklaration, wenn der Photo Picker nicht reicht. Ordo deklariert diese Berechtigungen nicht. Bilder öffnet `ImageViewerActivity` über `VIEW` `image/*`.

**USB.** Keine USB-Berechtigung und kein `uses-feature` für USB-Host. `backup/BackupFragment.kt` liest `UsbManager`, um Mass-Storage-Geräte von der SD-Karte zu unterscheiden, und schreibt das Backup über den System-Dateipicker („The system file picker is the one way to write to USB drives and cloud apps alike“). Keine Play-Deklaration aus den gelesenen Seiten.

**Local Network.** In [16558241](https://support.google.com/googleplay/android-developer/answer/16558241), gelesen am 15. November 2026, steht: die Local-Network-Berechtigung ist auf die Kernfunktion beschränkt, wenn Geräteauswahldialoge nicht reichen, und Apps mit Target Android 17 (API 37) oder höher müssen sie in der Play Console deklarieren. Ordo deklariert sie nicht und hat Target 36. Ob Android 16 (API 36) den Zugriff auf das lokale Netz schon zur Laufzeit abschneidet, ist über den Gradle-Kommentar hinaus **nicht geprüft**.

**Netz-Berechtigungen, die im Manifest stehen.**

- `INTERNET`: normale Berechtigung. Genutzt für den FTP-Server, FTP/SFTP/SMB/WebDAV-Clients (OkHttp in `provider/webdav`, SSHJ, SMBJ, commons-net, jCIFS im `app/build.gradle`) und den optionalen Modell-Download. Keine eigene Play-Deklaration auf den gelesenen Seiten.
- `ACCESS_NETWORK_STATE`: `ftpserver/FtpServerUrl.kt` beobachtet `ConnectivityManager`, um die angezeigte FTP-Adresse zu aktualisieren.
- `ACCESS_WIFI_STATE`: `util/InetAddressExtensions.kt` liest `wifiManager.connectionInfo.ipAddress` und baut daraus die lokale IPv4-Adresse für die FTP-URL. Es wird nicht die SSID und nicht die MAC-Adresse gelesen. `util/WakeWifiLock.kt` erzeugt einen Wifi-Lock (`WIFI_MODE_FULL_HIGH_PERF`) für `FtpServerService`.
- `WAKE_LOCK`: der Wifi-Lock. Keine Deklarationspflicht auf den gelesenen Seiten.

`android:usesCleartextTraffic="true"` erlaubt unverschlüsseltes FTP und HTTP. Das ist für Data safety relevant, wenn dort Transfers als erhoben gelten (Abschnitt 12). Es ist keine Berechtigungsdeklaration.

## 10. Weitere Manifest-Berechtigungen

**`REQUEST_DELETE_PACKAGES`.** `apps/AppsFragment.kt` startet `Intent.ACTION_UNINSTALL_PACKAGE` für ein Paket aus der App-Liste. Auf den gelesenen Play-Seiten keine eigene Deklaration. Wenn Google die Berechtigung beanstandet, den Uninstall-Intent entfernen. Der Nutzer kann die App dann weiterhin in den Systemeinstellungen deinstallieren.

**`WRITE_EXTERNAL_STORAGE` (max. API 32).** Nur bis Android 12L. Ab API 33 zählt sie nicht mehr. Keine separate Deklaration auf der All-files-Seite gefunden. Entfernen würde den Schreibzugriff auf Geräten vor API 33 ändern. Das genaue Verhalten ohne die Berechtigung ist **nicht geprüft**.

**`INSTALL_SHORTCUT`.** `filelist/FileListFragment.kt` ruft `ShortcutManagerCompat.requestPinShortcut` auf. Die alte Launcher-Berechtigung bleibt im Manifest. Keine Play-Deklaration auf den gelesenen Seiten. Wenn sie als ungenutzt gilt, kann sie aus dem Manifest. Ob `requestPinShortcut` ab API 26 ohne sie auskommt, ist **nicht geprüft**.

**`FOREGROUND_SERVICE`.** Gehört zu Abschnitt 7. Auf der Android-Seite ist sie die allgemeine Vordergrunddienst-Berechtigung neben der typbezogenen.

## 11. Was nicht deklariert werden muss, weil es nicht im Manifest steht

Fotos/Videos, Standort, SMS, Anrufprotokoll, Körpersensoren, Health Connect, VPN, Exact Alarm, Full-Screen-Intent, Accessibility: in [16558241](https://support.google.com/googleplay/android-developer/answer/16558241) beschrieben, im Ordo-Manifest nicht vorhanden. Nichts eintragen.

`visibleToInstantApps` auf `FileListActivity`: eine Instant-Apps-Pflicht in der Play Console ist **nicht geprüft**.

## 12. Data safety

Grundlage, gelesen am 15. November 2026: [Provide information for Google Play's Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469) und [User Data](https://support.google.com/googleplay/android-developer/answer/10144311). Jede App braucht das Formular, auch wenn sie nichts erhebt, plus einen Link zur Datenschutzerklärung. „Erhoben“ heißt: Daten verlassen das Gerät, einschließlich durch Bibliotheken. Verarbeitung nur auf dem Gerät ist nicht anzugeben. „Weitergeben“ heißt: erhobene Daten an einen Dritten, auch direkt an eine andere App auf dem Gerät. Nicht als Weitergabe gilt eine vom Nutzer ausgelöste Aktion, mit der er rechnet. Die FAQ nennt außerdem: lädt der Nutzer Daten in sein eigenes Laufwerk oder Cloud-Konto hoch und die App selbst erhebt sie nicht, ist das keine Erhebung. Ende-zu-Ende-Verschlüsselung, bei der auch der Entwickler nicht mitlesen kann, ist keine Erhebung.

Die FAQ-Überschrift „How should I treat the collection and use of IP addresses?“ steht auf der Seite. Der Antworttext war im abgerufenen Inhalt nicht enthalten. Ob die IP-Adresse beim Modell-Download als „Device or other IDs“ anzugeben ist, ist deshalb **nicht geprüft**.

Konto-Löschung: [Understanding Google Play’s app account deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111). Die zusätzlichen Fragen im Data-safety-Formular gelten für alle. Ein Löschweg in der App und im Web ist nur nötig, wenn die App Konten anlegt.

### Was der Code tut

Keine Treffer in `*.kt`, `*.java` und `*.gradle` für `Firebase`, `Crashlytics`, `AccountManager`, `GoogleSignIn`, `signIn`, `createUser`. Keine Gradle-Abhängigkeit mit `analytics`, `crashlytics`, `sentry`, `bugsnag` oder `mixpanel`. Der Firebase-Init-Provider wird entfernt. Es gibt keine eigene Telemetrie-URL im App-Code. Die einzigen festen `https`-Ziele im ausführbaren Code, die gefunden wurden, sind der Modell-Download. Lizenz-URLs in `res/raw/licenses.xml` und der Rechtstext in `res/values/legal.xml` sind Texte, keine Aufrufe.

| Vorgang | Wohin | Im Code |
| --- | --- | --- |
| Dateien lesen und ändern | Gerät | Dateiliste, keine Übertragung an einen Ordo-Server gefunden |
| Installierte Apps | Gerät | `PackageManager`, Abschnitt 5. Kein Versand gefunden |
| WLAN-IP für die FTP-URL | Anzeige in der Benachrichtigung | `InetAddressExtensions.kt`. Kein Versand an einen Entwickler-Server |
| FTP-Server | andere Geräte im Netz, die der Nutzer anspricht | `FtpServerService`. Der Nutzer startet ihn |
| FTP-, SFTP-, SMB-, WebDAV-Client | Server, deren Adresse der Nutzer einträgt | Module unter `provider/`, Bibliotheken in `app/build.gradle` |
| Teilen / Öffnen mit | andere App auf dem Gerät | `ACTION_SEND` und `ACTION_VIEW` |
| Backup auf USB oder in eine Cloud-App | Ziel, das der Nutzer im System-Picker wählt | `backup/BackupFragment.kt`. Die fremde App überträgt |
| Modell-Download | `https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/e87f176479d0855a907a41277aca2f8ee7a09523/Qwen3.5-4B-Q4_0.gguf` | `ai/CommandModel.kt`, nur nach `startDownload`. `DownloadManager`, Datei unter dem app-eigenen External-Files-Ordner `models`. SHA-256-Prüfung lokal. Größe im Code: 2 583 221 408 Bytes. Der anschließende Lauf nutzt `llm` (`LlamaEngine`) auf dem Gerät |
| Android-Backup | ausgenommen | `backup_rules.xml`, `data_extraction_rules.xml` |
| Play-eigene Absturzberichte und Installationszahlen | nicht im App-Code | `legal.xml` behauptet, Google liefere zusammengefasste Zahlen, wenn der Nutzer das in Android erlaubt. Ein SDK dafür ist nicht eingebunden. Ob das Formular Play-Vitals verlangt, ist **nicht geprüft** |

`res/values/legal.xml` und `res/values-de/legal.xml` sagen, die KI-Befehle seien aus, bis der Nutzer sie einrichtet, und Hugging Face sehe dabei die IP-Adresse. Das ist der In-App-Text, keine Messung des HTTP-Verkehrs von `DownloadManager`. Welche Header `DownloadManager` sendet, ist **nicht geprüft**. `CommandModel.startDownload` setzt Titel, Netzart und Zielpfad, keinen Account und keine Werbe-ID.

Cleartext ist app-weit erlaubt (Abschnitt 1). Wenn im Formular irgendeine Erhebung angegeben wird, darf „alle Daten auf dem Transportweg verschlüsselt“ nicht angekreuzt werden. FTP kann unverschlüsselt sein. Der Modell-Download nutzt `https`.

Ein Entwickler-Konto legt der Code nicht an. Suche wie oben: keine Konto-API.

Im Repo steht als Datenschutzerklärungs-URL `https://beko2210.github.io/ordo/datenschutz.html` (`docs/ROADMAP.md`, Punkt P3.2). Die Seite selbst wurde nicht geöffnet. Ob sie denselben Stand wie `legal.xml` hat, ist **nicht geprüft**.

### Was ins Formular gehört, abgeleitet aus den Definitionen

Das ist keine Freigabe durch Google.

- Dateien, App-Liste, WLAN-IP, Tresor, Server-Passwörter in den Einstellungen: auf dem Gerät verarbeitet. Nach der Definition „nur auf dem Gerät“ nicht als Erhebung angeben.
- Kopieren auf einen vom Nutzer eingetragenen Server, Teilen an eine andere App, Backup über den System-Picker: die gelesene Ausnahme „vom Nutzer ausgelöst, und der Nutzer rechnet damit“ bzw. die Drive-FAQ sprechen dagegen, das als Erhebung oder Weitergabe durch Ordo anzugeben. Der Entwickler bekommt die Dateien nicht.
- Modell-Download: die App stößt einen GET auf Hugging Face an. Ob das eine Erhebung der IP-Adresse ist, hängt an der nicht mitgelieferten FAQ-Antwort. Bis dahin im Formular nicht als geklärt behandeln. Sachverhalt zum Eintragen, falls die Konsole nach dem Download fragt: optional, nur wenn der Nutzer die KI einrichtet, Zweck App-Funktion, Empfänger Hugging Face, Inc., keine Dateinamen und keine Befehle in diesem Request (der Request enthält nur die feste Modell-URL).
- Keine Analytics, keine Werbung, keine Crash-SDK-Daten, keine Konten.
- Löschanfrage für Entwickler-Daten: der Code löscht das Modell lokal (`CommandModel.remove`). Ein Konto beim Entwickler gibt es im Code nicht. Die Web-Lösch-URL für Konten ist dann nach der gelesenen Konto-Seite nicht erforderlich. Die einzelnen Ja/Nein-Schalter des Formulars sind **nicht geprüft**, nur die Regel.

## 13. Was Belkis in der Play Console von Hand macht

1. App-Inhalt: Datenschutzerklärung verlinken. Kandidat aus `docs/ROADMAP.md`: `https://beko2210.github.io/ordo/datenschutz.html`. Vorher den Seiteninhalt mit `legal.xml` abgleichen. Die Seite wurde hier nicht geöffnet.
2. Data safety ausfüllen, auch bei „nichts erhoben“. Abschnitt 12. Die IP-Frage zum Hugging-Face-Download selbst gegen die FAQ auf [10787469](https://support.google.com/googleplay/android-developer/answer/10787469) entscheiden. Keine Konten ankreuzen. Kein Haken bei „alles verschlüsselt auf dem Transportweg“, solange Cleartext erlaubt ist und irgendein Transfer als erhoben gilt.
3. Berechtigungsformular: `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES`. Texte aus den Abschnitten 4 bis 6. Store-Beschreibung muss den Dateimanager als Kernfunktion nennen (All-files-Seite und Package-Visibility-Seite).
4. App-Inhalt, Vordergrunddienste: `dataSync` für `FileJobService`, `specialUse` für `FtpServerService`. Texte aus Abschnitt 7. Zwei Videos aufnehmen: Dateiauftrag starten, FTP-Server starten. Kein Full-Screen-Intent.
5. Nichts eintragen für Standort, Fotos, Videos, USB, SMS, Full-Screen-Intent. Local Network erst, wenn `targetSdk` auf 37 geht.
6. Hochgeladenes AAB: `targetSdk` 36 prüfen. Das steht so im Gradle. Ob das Bundle, das in der Console liegt, dieses Gradle ist, ist **nicht geprüft**.
7. Vor dem 1. Februar 2027 das Release-AAB auf 16-KB-ELF der eigenen und der vorgefertigten `.so` prüfen (`libarchive`, `libselinux`, llama.cpp). Hier nicht gebaut.
8. Nach dem ersten Bundle das zusammengeführte Manifest in der Console oder per `processReleaseManifest` gegen Abschnitt 1 halten. Bibliotheks-Berechtigungen sind hier **nicht geprüft**.
9. Geschlossener Test steht in `docs/ROADMAP.md` (P3.5) als 12 Tester und 14 Tage. Diese Zahlen wurden nicht an einer Play-Seite gegengeprüft.

Nicht gelesen oder nicht ausgeführt: zusammengeführtes Manifest, gebaute `.so`, die Live-Seite der Datenschutzerklärung, der Antworttext der IP-FAQ, ein Laufzeitcheck von `startForeground` ohne Typ-Argument und von `onTimeout`, die Plattformpflicht für lokales Netz auf API 36. Risiko: `specialUse` für den FTP-Server kann abgelehnt werden, dann bleibt `dataSync` mit Sechs-Stunden-Limit. Vorgefertigte native Bibliotheken können an 16 KB scheitern, obwohl das NDK r30 ist.
