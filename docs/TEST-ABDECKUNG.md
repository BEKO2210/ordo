# Testabdeckung

Kover misst die JVM-Zeilendeckung der lokalen Debug-Unit-Tests. Der Android-CI-Workflow führt im `test`-Job
`:app:koverVerifyDebug` aus; unterschreitet ein Paket seine Untergrenze, schlägt der Job fehl. Geräte- und
End-to-End-Tests fließen nicht in diese Messung ein.

Messung vom 23.11.2026 mit OpenJDK 21 auf dem Linux-Entwicklungsrechner, Debug-Variante, Kover 0.9.11 und
`./gradlew :app:koverXmlReportDebug`. Die Untergrenze liegt jeweils zwei Prozentpunkte unter dem gemessenen Wert.

| Paket | Zeilenabdeckung | Verify-Untergrenze |
| --- | ---: | ---: |
| `ai` | 22 % | 20 % |
| `filejob` | 3 % | 1 % |
| `vault` | 22 % | 20 % |
| `backup` | 12 % | 10 % |
| `ftpserver` | 5 % | 3 % |
| `provider.common` | 4 % | 2 % |

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
