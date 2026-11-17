# Ordo – Maßsystem: der Goldene Schnitt

Stand: 17.11.2026, Vorgabe von Belkis: „Der goldene Schnitt soll sich über alles ziehen – ganz Ordo.“

Ordo misst alle eigenen Abstände, Größen, Proportionen und Rundungen mit dem Goldenen Schnitt φ ≈ 1,618. Praktisch
heißt das: die Fibonacci-Reihe in dp, in der jeder Schritt etwa das φ-fache des vorigen ist. Die Werte stehen als
Ressourcen in `app/src/main/res/values/dimens_phi.xml`; Layouts nutzen nur diese, keine freien Zahlen.

## Die Reihe

| Ressource | dp | Rolle |
|---|---|---|
| `phi_3` | 3 | Haarabstände (Indikator unter einem Knopf) |
| `phi_5` / `space_related` | 5 | Zusammengehöriges: Symbol und Beschriftung, Titel und Info-Text |
| `phi_8` / `space_item` | 8 | Zeilen und Bedienelemente einer Gruppe |
| `phi_13` / `space_card_v` | 13 | Innenabstand von Karten senkrecht |
| `phi_21` / `space_card_h`, `space_gutter` | 21 | Innenabstand waagrecht, Abstand zwischen Karten, Bildschirmrand |
| `phi_34` / `space_section` | 34 | Abstand zwischen Abschnitten; Symbolfeld einer Zeile |
| `phi_55` | 55 | Mindesthöhe einer Zeile, Kachelhöhe |
| `phi_89` | 89 | Kachelbreite, Mindesthöhe einer Karte |
| `phi_144` … `phi_610` | 144–610 | große Flächen; `phi_610` = Inhaltsbreite auf großen Bildschirmen (377 × φ) |

Rundungen: 8 · 13 · 21 · 34 dp (klein, mittel, groß, sehr groß).

## Proportionen

- Kacheln, Vorschaubilder und Bühnen (z. B. die Equalizer-Bühne) haben das Seitenverhältnis φ : 1.
- Zweispaltige Ansichten auf Tablets teilen 61,8 % : 38,2 % (Hauptinhalt : Nebeninhalt), nicht 50 : 50.
- Eine Karte, die neben einer zweiten steht, ist entweder gleich breit (Raster) oder φ-mal so breit – nie beliebig.

## Schrift

Die Schriftgrößen bleiben die von Material 3 (12, 14, 16, 22, 24, 28 sp …): Nutzer stellen sie in Android größer,
und die Barrierefreiheit hängt an diesen Stufen. Ihr Verhältnis zueinander liegt nahe √φ ≈ 1,27 (12 → 16 → 22 → 28).
In jeder Zeile gibt es genau zwei Ebenen: Titel 16 sp (bodyLarge), Info-Text 12 sp (bodySmall, gedämpfte Farbe)
direkt darunter, höchstens zwei Zeilen, nie unter einem Bedienelement. Silbentrennung ist aus; ein Wort wird nie geteilt.

## Grenzen

- Fremde Bausteine (Android-Systemdialoge, Material-Standardzeilen der Einstellungen) behalten ihre Maße, solange sie
  nicht durch eigene ersetzt werden.
- Tippflächen bleiben mindestens 48 dp, auch wenn das sichtbare Element auf der Reihe 34 dp groß ist.

## Umstellung, Bildschirm für Bildschirm

1. Musik-Player und Klang-Blatt
2. Startseite
3. Einstellungen (eigene Zeilen, Auswahl-Dialoge)
4. KI-Blatt
5. Dateiliste und Seitenmenü
6. Speicheranalyse, Tresor, Sicherung

Jeder Schritt: Abstände auf die Reihe umstellen, auf Telefon (412 dp) und Tablet hochkant und quer messen
(Symbol-Text-Versatz, Kanten), die automatischen Prüfungen laufen lassen, Bilder vorher/nachher.

### Startseite (umgestellt)

- Abstände, Größen und Rundungen der Startseite nehmen nur noch die φ-Reihe: Bildschirmrand 21 dp (Listenrand
  `phi_13` + Kartenrand `phi_8`), Abschnitte 34 dp, Karteninnenrand 13/21 dp, Kacheln 8 dp.
- Kategorie-, Schnellzugriff- und „Zuletzt“-Kacheln sind φ : 1 breit zu hoch (`PhiTileCardView`, `PhiImageView`); braucht
  der Inhalt mehr Höhe (große Schrift, schmales Telefon), wächst die Kachel mit, statt etwas abzuschneiden.
- Speicherkarte: Beschriftung zu Prozentwert 61,8 : 38,2; „Öffnen“ und „Analysieren“ bleiben gleich breit.
- Rundungen: Karten 21 dp, Kacheln 13 dp, Suchleiste 21 dp (Höhe 48 dp bleibt). Keine Silbentrennung auf der Startseite.
- Spaltenzahlen (3/9, 4/6/8, 3/5/8) bleiben; die Inhaltsbreite ist auf der Startseite **nicht** auf 610 dp begrenzt,
  weil die neun Kategorien ab 840 dp in einer Reihe stehen und sonst unter die Lesbarkeit schrumpfen.
- Titel in Kacheln bleiben bei 14 sp (Subtitle2), damit „Dokumente“ auf 360 dp nicht abgeschnitten wird.

### Einstellungen (umgestellt)

- Gilt nur für Ordos eigene Zeilen: Kopf „Akzentfarbe“, Kategoriezeilen der Übersicht, Auswahl-Dialoge mit Bildern.
  Die Standardzeilen von AndroidX behalten ihre Maße.
- Akzentfarben: sichtbarer Kreis 34 dp in einer 55-dp-Tippfläche (≥ 48 dp), Auswahlring 3 dp stark im Abstand von
  3 dp zum Kreis; Farbvorschau 144 × 89 dp (φ : 1). Abstände 8 / 13 / 21 dp.
- Auswahl-Dialoge: Bildfläche 89 × 55 dp (φ : 1, Rundung 13 dp), Zeilenrand 21 dp waagrecht und 13 dp senkrecht,
  Auswahlrahmen 3 dp mit Rundung 21 dp.
- Kategoriezeilen: Symbolfeld 34 dp, Zeilenrand 21/13 dp, Mindesthöhe 55 dp, Titel 16 sp, Info-Text 12 sp (Caption).
- Ausrichtung: die Symbolspalte des Akzent-Kopfes bleibt 56 dp breit wie `icon_frame` der AndroidX-Zeilen, damit
  Symbole und Textkante mit den Standardzeilen fluchten. Der erste Farbkreis beginnt deshalb 45,5 dp (56 − (55 − 34) / 2)
  nach dem Zeilenrand und die Vorschau bei 56 dp: diese beiden Werte folgen der AndroidX-Kante, nicht der Reihe.
- Keine Silbentrennung, höchstens zwei Ebenen (Titel, Info-Text). Nicht gemessen: noch kein Lauf von
  `e2e/settings-visual.sh` und kein Versatz-Messwert nach der Umstellung.

### KI-Blatt (umgestellt)

- Blattrand 21 dp, unten 34 dp; Karten 21/13 dp, Abstände 5/8/13/21 dp. Kugeln: Kopf 55, Denken 89, Einrichtung 144 dp.
- Symbole 34 dp (Innenrand 5 dp); Tasten und Prüfzeilen mindestens 55 dp hoch, Eingabefeld und Tasten mit Rundung 34 dp.
- Info-Texte (Untertitel, Route, Nachricht, Dateiliste, Notiz) 12 sp in der zweiten Textfarbe; keine Silbentrennung
  (auch im TextSwitcher aus `CommandSheetFragment`).
- `ai_glass_card` hat jetzt Rundung 21 dp; die Karte teilen sich Einführung und Startseitenkarten (`Widget.Ordo.*`).
- Nicht gemessen: kein Lauf auf einem Gerät; Unschärferadius (28) und Haarlinien der Chips bleiben.

### Dateiliste und Seitenmenü (umgestellt)

- Listenzeile: mindestens 55 dp (wächst bei zwei Zeilen), Symbolfeld 55 dp (Tippfläche) mit sichtbarem Symbol 34 dp,
  Info-Text 12 sp, Menü-Taste 48 dp. Auswahl- und Häkchen-Darstellung unverändert.
- Raster: Vorschaubild φ : 1 (1,618; bisher 1,78), Rundung 8 dp, Rand 21/13 dp; kompaktes Raster bleibt quadratisch.
  Namenszeile mindestens 55 dp. Größen `FileItemSize`: 55/34, 68/34, 89/34 dp Zeile/Symbol, Spalten 89/120/180 dp
  (vier/drei/zwei Spalten bei 411 dp bleiben).
- Seitenmenü: Zeile 55 dp, Symbol **24 dp sichtbar in einem 34-dp-Feld**, Auswahl-/Ziehfläche Rundung 34 dp
  (`phi_radius_xl`), Kopf mit 34-dp-Symbol, Textkante des Kopfes fluchtet mit den Einträgen.
- `ordo_row_ripple` hat Rundung 21 dp (gilt auch für Einstellungs- und Analysezeilen). Keine Silbentrennung.
- Nicht gemessen: Symbol-Text-Versatz im Menü, Zeilenhöhen auf dem Gerät.

### Speicheranalyse, Tresor, Sicherung (umgestellt)

- Karten der Analyse: Rand 21/13 dp, Abstand 21 dp, Rundung 21 dp; Titel und Untertitel (12 sp, zweite Textfarbe) in
  zwei Ebenen, keine Silbentrennung. Ring, Karte und Wachstum 233 dp; Ringstärke 21 dp; Treemap-Rundung 8 dp.
- Zeilen: mindestens 55 dp, Symbolfeld 34 dp (Innenrand 5), Name zu Größe 61,8 : 38,2, Fortschrittsbalken 5 dp.
- Zwei Spalten breiter Bildschirme bleiben **gleich breit**: die Ordnerkarte braucht die „daneben“-Breite.
- Tresor: Hinweis mit 34-dp-Symbolfeld, Sperrbild 89 dp; Sicherung: Zeilen mindestens 55 dp, Symbolfeld 34 dp,
  Ränder 21/13 dp, Info-Texte 12 sp.
- Unverändert: Zeichenlogik der Diagramme, `ScanProgressView` (Ringstärke 16 dp), Diagramm-Festformate (Breite im XML
  unbekannt, daher kein φ : 1). Nicht gemessen: kein Gerätelauf, kein `e2e/`-Lauf.
