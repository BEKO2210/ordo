# Klang-Blatt – Design-Spezifikation (Material 3 Expressive, Stufe „Excellent“)

Grundlage: Skill `material-3-expressive` (Referenzen: m3-sheets, m3-button-groups, m3-icon-buttons,
m3-progress-indicators, m3-shape-morph, m3-motion-physics). Hausregeln: keine Schieberegler (Halten-zum-Ziehen oder
− / +), 48-dp-Tippflächen, Kontrast 4,5 : 1 Text / 3 : 1 Bedienelemente, alles Bewegte hinter `Motion.any`.

## Kontext
- Ordo-Musik-Player, modales Bottom Sheet „Klang“; Telefon (compact), Tablet hochkant (medium/expanded, Blatt max.
  610 dp ≈ 377 × φ breit, zentriert), Tablet quer ebenso; nur die Effekt-Karten stehen zweispaltig.
- Container: `md.comp.sheet.bottom` – Fläche `surfaceContainerLow`, obere Ecken extra-large (28 dp), Griff oben.
- **Zwei Hero-Momente, nicht mehr:** (1) die Equalizer-Bühne, (2) die Voreinstellungs-Kacheln.

## Aufbau von oben nach unten
1. **Kopf** – Griff; Zeile: Symbol `equalizer` (24 dp, primary) · „Klang“ (titleLarge) · Hauptschalter rechts
   (Equalizer an/aus). Darunter eine Zeile, die die Wahrheit sagt: „Aus“ · „Rock“ · „Eigene Einstellung“ ·
   „Auf diesem Gerät nicht verfügbar“ (bodyMedium, onSurfaceVariant).
2. **Equalizer-Bühne (Hero 1)** – Karte `surfaceContainerHigh`, Ecken 28 dp, Innenabstand 20 dp.
   - Kurve: Breite : Höhe = φ : 1 (Höhe = Kurvenbreite / 1,618, begrenzt auf 160–300 dp). Raster: Linien bei 0, ±6, ±12 dB (outlineVariant, 1 dp);
     dB-Beschriftung links (labelSmall); Frequenzen unten (31 · 62 · 125 · 250 · 500 · 1k · 2k · 4k · 8k · 16k).
   - Fläche unter der Kurve mit Verlauf primary 28 % → 0 %; Linie 3 dp primary, runde Enden, glatte Spline.
   - Griffe: 18 dp Kreis primary mit 2 dp Ring surface; Tippfläche 48 dp. **Halten 150 ms** → Griff wächst auf
     1,3 (Feder), leichter Haptik-Tick, Wertblase darüber „+3 dB · 1 kHz“ (inverseSurface). Ziehen nur senkrecht,
     Raster 0,5 dB, Haptik-Tick bei 0 dB. Loslassen → Feder zurück.
   - Aus-Zustand: Kurve und Griffe 38 % Deckkraft, nicht bedienbar, Text „Equalizer ist aus“ mittig.
   - Unten rechts in der Karte: kleiner Text-Knopf „Zurücksetzen“ (nur sichtbar, wenn nicht flach).
3. **Voreinstellungen (Hero 2)** – waagerecht scrollende Kacheln 89 × 55 dp (φ : 1), Abstand 8 dp, Ecken 13 dp:
   oben eine Mini-Kurve (dieselben 10 Werte als Piktogramm, 34 × 21 dp), darunter der Name (labelLarge).
   Gewählt: `primaryContainer` / `onPrimaryContainer`, **Formwechsel** 16 → 28 dp Ecken (Shape-Morph, Feder),
   kleiner Haken oben rechts. „Eigene“ erscheint als letzte Kachel, sobald ein Griff bewegt wurde.
   Wechsel der Voreinstellung: Kurve morpht 320 ms (emphasized decelerate) zur neuen Form.
4. **Genau einstellen** (eingeklappt) – Zeile mit Symbol `tune`, Chevron dreht sich. Aufgeklappt je Band eine Zeile:
   Frequenz (bodyLarge) · Wert-Pille (labelLarge, surfaceContainerHighest) · verbundene Knopfgruppe XS mit
   `−` / `+` als **tonale** Icon-Buttons (Sichtgröße 36 dp, Tippfläche 48 dp, 2 dp Abstand). Vorverstärkung als
   letzte Zeile. TalkBack: „31 Hertz, plus 3 Dezibel, verringern / erhöhen“.
5. **Effekte** – Abschnittstitel (labelLarge, primary). Karten (Telefon 1 Spalte, Tablet 2 Spalten),
   `surfaceContainer`, Ecken 20 dp, je Karte: großes Symbol links (`speaker` für Bass, `surround_sound` für
   Raumklang) in einem Ring = bestimmter Kreis-Fortschritt (Stärke 0–100 %), Titel, Zustand („Stärke 60 %“),
   Schalter oben rechts, unten `−` / `+` (tonal). Karte nur sichtbar, wenn das Gerät den Effekt kann; sonst eine
   ruhige Zeile „Bassverstärkung: auf diesem Gerät nicht verfügbar“.
6. **Lautstärke** – Karten wie oben:
   - Lautstärke angleichen (Symbol `equal`/`graphic_eq`), Schalter, Vorverstärkung − / +.
   - Balance: Bild „Lautsprecher links ⟷ rechts“ mit einem Punkt dazwischen, der wandert; − / + und
     „Mitte“-Textknopf; Wert „L 20 %“ / „Mitte“ / „R 20 %“.
   - Mono: Zeile mit Symbol `hearing`, Schalter.
7. **Wiedergabe**
   - Tempo: verbundene Knopfgruppe (single-select) 0,75× · 1× · 1,25× · 1,5× · 2× + darunter feine − / + (0,05),
     aktueller Wert groß (headlineSmall). Hinweis „Tonhöhe bleibt gleich“.
   - Schlaf-Timer: verbundene Gruppe Aus · 15 · 30 · 45 · 60 min · Liedende; läuft er, erscheint ein Chip
     „Noch 23 min“ mit Abbrechen.
   - Stille überspringen (Symbol `fast_forward`), Wenn andere Apps Ton spielen: verbundene Gruppe
     „Pausieren | Leiser“, Weiterspielen bei Kopfhörern (Symbol `headphones`) – je Zeile mit Symbol + Untertitel.
8. Jede Zeile hat ein Symbol (Material Symbols rounded filled, über `utils/material-symbols.py` importiert).

## Raster und Schrift (Goldener Schnitt)
- Fibonacci-Stufen als `klang_space_*`: 5 · 8 · 13 · 21 · 34 dp – 8 zwischen verwandten Teilen, 13 in Karten (vertikal),
  21 Kartenrand seitlich und Abstand zwischen Karten, 34 zwischen Abschnitten. Symbole in einer 34-dp-Spalte; alle
  Texte eines Abschnitts beginnen an einer Kante (42 dp), alle Bedienelemente enden an einer Kante.
- Zwei Textstufen je Zeile: Titel bodyLarge 16 sp (onSurface, eine Zeile), Info 12 sp (onSurfaceVariant, höchstens zwei
  Zeilen, neben dem Schalter nie darunter). Werte labelLarge mit Tabellenziffern in einem 55-dp-Feld. Kein Wort wird
  getrennt (`hyphenationFrequency=none`, `breakStrategy=simple`).
- Effekt-Karten mindestens 89 dp hoch; − / + sichtbar 34 dp in 48 dp Tippfläche.

## Bewegung
- Feder für Griffe und Formwechsel (Material-Motion-Physik: schnelle räumliche Feder), Kurven-Morph 320 ms,
  Aufklappen 250 ms. `Motion.any == false` → alles sofort im Endzustand.

## Barrierefreiheit
- Kurve: jeder Griff ein eigener Accessibility-Knoten mit `ACTION_SCROLL_FORWARD/BACKWARD` (+/− 0,5 dB) und
  Zustandsbeschreibung; Kacheln als Radio-Gruppe; Knopfgruppen mit ausgewähltem Zustand.
- Kontrast: Werte und Beschriftungen ≥ 4,5 : 1 in hell und dunkel, Kurve/Griffe ≥ 3 : 1 gegen die Karte.
