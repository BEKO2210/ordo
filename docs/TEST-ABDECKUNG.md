# Testabdeckung

Kover misst die JVM-Zeilendeckung der lokalen Debug-Unit-Tests. Der Android-CI-Workflow führt im `test`-Job
`:app:koverVerifyDebug` aus; unterschreitet ein Paket seine Untergrenze, schlägt der Job fehl. Geräte- und
End-to-End-Tests fließen nicht in diese Messung ein.

Die Kover-Instrumentierung schließt `org.apache.ftpserver.*` und `org.apache.mina.*` aus: Instrumentierte
Versionen dieser Bibliotheksklassen verändern das Verhalten des echten, prozessinternen FTP-Testservers.
Außerdem ausgenommen: `me.zhanghai.android.files.ftpserver.*`, `provider.root.*` und `provider.linux.*`. Instrumentiert ändert sich die Klassen-Initialisierung, und `ProviderFtpFileTest` erreicht dann Root-/Linux-Provider, die Android- bzw. nativen Code brauchen. FTP deckt der Gerätetest ab, deshalb gibt es für `ftpserver` keine Untergrenze.

Kover 0.9.11 ordnet den Bericht explizit der Android-Variante `debug` und deren Produktions-Source-Sets
`main` und `debug` zu. Instrumentierung und Bericht sind auf `me.zhanghai.android.files` begrenzt; Paketfilter
schließen Unterpakete ein. Eine neue Messung erfolgt mit OpenJDK 21 und
`./gradlew :app:koverXmlReportDebug`. Danach ist die Untergrenze jeweils
`max(0, floor(gemessene Zeilenabdeckung) - 2)`.

| Paketfilter | Zeilenabdeckung | Verify-Untergrenze |
| --- | ---: | ---: |
| `ai` | ausstehend | ausstehend |
| `filejob` | ausstehend | ausstehend |
| `vault` | ausstehend | ausstehend |
| `backup` | ausstehend | ausstehend |
| `provider.common` | ausstehend | ausstehend |

Die Verify-Regeln für `ai`, `filejob`, `vault`, `backup` und `provider.common` sind angelegt, enthalten aber bis
zur vollständigen Messung keine Untergrenzen. `ftpserver` bleibt wie oben beschrieben von der Instrumentierung
ausgeschlossen und besitzt deshalb keine Verify-Regel.

## Bekannte Lücken

- `ai`: Android-Ansichten, Sheet-Lebenszyklus und der lokale Modellaufruf brauchen weiterhin Geräte- bzw.
  Integrationstests; die JVM-Suite deckt vor allem Planen, Prüfen, Zählen und Ausführen ab.
- `filejob`: Die großen, Android-gebundenen Jobabläufe und Benachrichtigungen dominieren das Paket. Unit-Tests
  decken derzeit die ausgelagerten Regeln für Konflikte, atomisches Schreiben, Cache-Bereinigung und
  Übertragungsschätzung ab.
- `vault`: Verschlüsselter Speicher und Freigabe-Tokens sind unit-getestet; Activity, Fragment, ViewModel,
  Keystore-Anbindung und Provider-Lebenszyklus fehlen in der JVM-Messung.
- `backup`: Pfadvalidierung, Konfliktnamen und Quellstream-Fehler sind abgedeckt. Picker, UI und die in
  `FileJobs` liegenden vollständigen Sicherungs- und Wiederherstellungsabläufe fehlen.
- `ftpserver`: `ProviderFtpFile` läuft in Unit-Tests gegen ein echtes temporäres Dateisystem. Service,
  Benachrichtigung, Tile, Netzwerkadresse und laufender FTP-Listener brauchen Android-/Netzwerktests.
- `provider.common`: Die atomaren Fremdprovider-Helfer sind teilweise abgedeckt; Channels, Watch-Service,
  Pfadimplementierungen und providerabhängige Fehlerpfade sind weitgehend offen.

Der HTML-Bericht entsteht mit `./gradlew :app:koverHtmlReportDebug` unter
`app/build/reports/kover/debug/html`. Der XML-Bericht liegt unter
`app/build/reports/kover/debug/report.xml`.
