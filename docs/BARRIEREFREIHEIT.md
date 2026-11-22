# Barrierefreiheit: Kontrast der Akzentfarben

Ordo bietet fünf Akzentfarben (Aurora, Blau, Mint, Violett, Bernstein). Jede muss im hellen und im dunklen Design
lesbar sein. `AccentContrastTest` (`app/src/test/java/me/zhanghai/android/files/theme/AccentContrastTest.kt`)
liest dazu die Farbwerte aus den Ressourcen-Dateien (`accent_overlays.xml`, `accent_colors.xml`, `ordo_m3_colors.xml`,
`colors.xml`, `ai_colors.xml`; `values-night` für dunkel), folgt den `@color`-Verweisen, rechnet nach der Formel
aus [WCAG 2.2](https://www.w3.org/TR/WCAG22/#dfn-contrast-ratio) und lässt den Build fehlschlagen, wenn ein Paar
unter dem Grenzwert liegt. Die Fehlermeldung nennt jedes geprüfte Paar mit seinem Verhältnis.

## Was automatisch geprüft wird

| Kriterium | Paar | Grenzwert |
| --- | --- | --- |
| 1.4.3 Kontrast (Text) | Akzent als Text (Prozentzahlen, Links, gewählte Zusammenfassungen) auf Seite und Karten | 4,5 : 1 |
| 1.4.3 Kontrast (Text) | `onPrimary` auf `primary` (gefüllte Knöpfe, „Zugriff erlauben“) | 4,5 : 1 |
| 1.4.3 Kontrast (Text) | `onPrimaryContainer` auf `primaryContainer` (getönte Knöpfe, „Analysieren“) | 4,5 : 1 |
| 1.4.3 Kontrast (Text) | Snackbar-Aktion (`primaryInverse`) auf der inversen Fläche | 4,5 : 1 |
| 1.4.11 Kontrast von Nicht-Text | Akzent als Balken, Schalter, Auswahlring, Symbol auf Seite und Karten | 3 : 1 |

Als Hintergründe gelten die echten Werte beider Theme-Familien: Material 3 (Seite, alle fünf Kartenstufen),
Material Components (`color_surface`), die Glaskarte der Analyse (halbtransparent über der Seite) und im dunklen
Design zusätzlich der reine Schwarz-Modus mit seinen Kartenstufen. Ein weiterer Test stellt sicher, dass außerhalb
der KI-Identität (Aurora-Kugel, Befehlsblatt, Einführung) keine feste Markenfarbe mehr für den Akzent steht: Balken,
Ringe, Treemap, Prozentzahlen und Knöpfe folgen `?colorPrimary` des gewählten Themes.

## Gemessene Verhältnisse

Der Akzent als Text oder Element gegen den ungünstigsten Hintergrund (in beiden Designs die hellste Kartenstufe
„container highest“; auf der Seite selbst liegen die Werte höher). Stand: gerechnet aus den Werten im Repository
(WCAG-2.2-Formel), nicht auf einem Gerät gemessen. „vorher“ ist der Stand von 0.12.0 vor dieser Änderung.

| Design | Akzent | Primärfarbe (neu) | Akzent / ungünstigster Hintergrund | onPrimary / Primär | onContainer / Container | Snackbar-Aktion |
| --- | --- | --- | --- | --- | --- | --- |
| hell | Aurora | `#0B6680` | 5,28 (vorher 4,35) | 6,51 (5,36) | 11,97 | 10,09 |
| hell | Blau | `#1D4ED8` | 5,44 | 6,70 | 12,04 | 8,11 |
| hell | Mint | `#03684B` | 5,52 (vorher 4,45) | 6,81 (5,48) | 13,36 | 9,60 |
| hell | Violett | `#6D28D9` | 5,76 | 7,10 | 12,83 | 7,92 |
| hell | Bernstein | `#965400` | 4,78 (vorher 4,07) | 5,89 (5,02) | 13,45 | 10,15 |
| dunkel | Aurora | `#67E8F9` | 10,33 | 9,24 | 6,49 | 5,52 (vorher 4,54) |
| dunkel | Blau | `#93C5FD` | 8,30 | 8,15 | 8,49 | 5,68 |
| dunkel | Mint | `#6EE7B7` | 9,82 | 9,94 | 6,78 | 5,77 (vorher 4,65) |
| dunkel | Violett | `#C4B5FD` | 8,11 | 8,25 | 7,57 | 6,02 |
| dunkel | Bernstein | `#FCD34D` | 10,38 | 10,39 | 6,37 | 4,99 (vorher 4,26) |

Vorher unter dem Grenzwert: im hellen Design Aurora, Mint und Bernstein als Text auf der hellsten Kartenstufe
(4,35 / 4,45 / 4,07 gegen 4,5), im dunklen Design die Snackbar-Aktion in Bernstein (4,26). Behoben nach dem
Material-3-Prinzip: hell eine dunklere Stufe des Farbtons (um Tonwert 40) mit weißem `onPrimary`, dunkel eine
hellere (um Tonwert 80) mit dunklem `onPrimary`, Container bei 90 und 30. Blau und Violett lagen schon darüber.

## Was nicht automatisch geprüft wird

- **Tippflächen:** im Code sind interaktive Elemente 48 dp hoch; ein Test dafür gibt es nicht.
- **TalkBack:** Beschriftungen (`contentDescription`), Lesereihenfolge und Ansagen.
- **Schriftgröße:** Verhalten bei großer Systemschrift und Anzeigegröße. Die Stufenwahl der Tastenzeilen (Schrift
  verkleinern, Symbole ausblenden, untereinander stellen) ist per Unit-Test geprüft, das Aussehen nicht.
- Kontrast von Text auf Fotos, Diagrammfarben der Dateitypen und der KI-Identität (feste Markenfarben).
- Zustände wie deaktiviert, gedrückt oder fokussiert.

Empfehlung: vor jeder Veröffentlichung Googles **Accessibility Scanner** auf einem Gerät über die Hauptbildschirme
laufen lassen (Start, Dateiliste, Analyse, Einstellungen, KI-Blatt), in hell und dunkel und mit einem anderen Akzent
als Aurora, und mit TalkBack einmal den Weg von der Startseite zur Analyse durchgehen.
