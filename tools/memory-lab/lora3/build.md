# Trainingsdaten (lora_build.py)

Sätze von den LLMs: 6465
Behalten: 6335 (Training 4800, Validierung 672, getrennt nach Plan, nicht nach Satz)

Klassen: show 1759, delete 1314, move 1208, organize 534, refuse 504, copy 393, ask 273, question 167, chat 85, storq 33, refuse2 24, storfree 16, dups 13, dupsimg 12
Generatoren: local:8180:qwen3.5-9b 2090, local:8180:granite4.2-8b 1145, template 1061, local:8180:ministral3-14b 788, local:8180:qwen3.5-9b+noise 512, local:8180:granite4.2-8b+noise 287, template+noise 261, local:8180:ministral3-14b+noise 191
Netz (Regeln a-d) bei behaltenen Planfällen: plan 4158, ask 1146, refuse 71

Verworfen: duplicate 1134, source missing 499, type missing 264, time missing 127, name missing 117, time differs 100, near a test sentence 97, dest missing 69, exclusion missing 60, target misses gold 48, count missing 42, size missing 25, length 24, change said in a show 19, near a blind-3 sentence (count only) 17, delete said, other action meant 16, content missing 12, time added 11, refuse with delete verb 3

## Leck-Prüfung gegen 284 Testsätze (p0, p0b, Blind 1, Blind 2, 20 Originale, Labor-Sätze) und 60 Blind-3-Sätze (nur gezählt, nie angezeigt)

Maß: max(Wort-Jaccard, Zeichen-Ähnlichkeit) nach Kleinschreibung/Umlaut-Faltung. Ab 0,70 verworfen.

- 1.00 „wie viel Speicher ist noch frei“ ~ „Wie viel Speicher ist noch frei?“
- 1.00 „Wie viel Speicher ist noch frei?“ ~ „Wie viel Speicher ist noch frei?“
- 0.95 „Wie viel Speicherst noch frei“ ~ „Wie viel Speicher ist noch frei?“
- 0.90 „sortiere whatsapp video nach monaten“ ~ „Sortiere WhatsApp Images nach Monaten“
- 0.89 „sortiere whatsaapp video nach monaten“ ~ „Sortiere WhatsApp Images nach Monaten“
- 0.89 „Ist es schlimm wenn der Speicher schon fast voll ist“ ~ „Ist es schlimm wenn mein Speicher fast voll ist“
- 0.88 „Kann ich die WhatsApp Video-MP4s löschen?“ ~ „kann ich die alten whatsapp videos löschen?“
- 0.87 „Ist es schlimm, wenn der Speicher schon fast voll ist?“ ~ „Ist es schlimm wenn mein Speicher fast voll ist“
- 0.85 „Darf ich die WhatsApp-Videos löschen?“ ~ „kann ich die alten whatsapp videos löschen?“
- 0.85 „wo ist die größte datei mit impfung im download ordner“ ~ „was ist die größte datei im download ordner“
- 0.85 „in welcher Datei steht Versicherungsnummer“ ~ „In welcher Datei steht die Versicherungsnummer fürs Auto?“
- 0.85 „Mein Speicher ist voll, was könnte ich hier löschen?“ ~ „Speicher ist voll, was kann ich löschen?“
- 0.84 „Kann ich die alten WhatsApp-Bilder verschieben?“ ~ „kann ich die alten whatsapp videos löschen?“
- 0.84 „Wie viel Speicher ist noch verfügbar?“ ~ „Wie viel Speicher ist noch frei?“
- 0.84 „wo ist die größte daatei mit impfung im download ordner“ ~ „was ist die größte datei im download ordner“
- 0.81 „Zeig mir die Sprachnachrichten.“ ~ „zeig mir die letzten fünf sprachnachrichten“
- 0.80 „Kann ich die WhatsApp Voice Notes löschen?“ ~ „kann ich die alten whatsapp videos löschen?“
- 0.80 „In welcher Datei aus den Fotos steht die Versicherungsnummer?“ ~ „In welcher Datei steht die Versicherungsnummer fürs Auto?“
- 0.80 „Löschen wir alle PDFs im Download-Ordner?“ ~ „Lösche alle APK-Dateien im Download-Ordner“
- 0.79 „in welcher Datei steht Versicherungsnummer im Ordner Verein“ ~ „In welcher Datei steht die Versicherungsnummer fürs Auto?“
- 0.79 „Darf also ich die WhatsApp-Videos löchen?“ ~ „kann ich die alten whatsapp videos löschen?“
- 0.78 „Wie viel Speicherplatz ist noch verfügbar?“ ~ „Wie viel Speicher ist noch frei?“
- 0.78 „Kann ich die PDFs aus WhatsApp Documents löschen?“ ~ „kann ich die alten whatsapp videos löschen?“
- 0.78 „sortiere Filme nach monaten“ ~ „Sortiere WhatsApp Images nach Monaten“
- 0.78 „Kann ich die WhatsApp-Videos entfernen?“ ~ „kann ich die alten whatsapp videos löschen?“

Nächste behaltene Sätze (unter 0,70):

- 0.70 „tu die Teile in einen anderen Ordner bitte“ ~ „Verschieb die Datei in den anderen Ordner“
- 0.70 „äh also zeig mir mal die größten dateien im ordner downloads“ ~ „Zeig mir mal die größten Dateien auf meinem Handy.“
- 0.70 „Zeige mir die Screenshots-Fotos.“ ~ „zeig mir die drei neuesten screenshots“
- 0.69 „pack Steuer2025 in eine Zip-Datei“ ~ „Pack meine Bewerbungen in eine ZIP-Datei“
- 0.69 „Ich sortiere den Uni-Ordner in Unterordner nach Dateityp.“ ~ „sortier den download ordner nach dateityp“
- 0.69 „pack Hochzeit.mov in eine Zip-Datei“ ~ „Pack meine Bewerbungen in eine ZIP-Datei“
- 0.69 „pack Hochzeitmov in eine Zip-Datei“ ~ „Pack die Lissabon Fotos in eine Zip Datei“
- 0.69 „will mp3s vom letzten monat sehen“ ~ „Bildschirmfotos vom letzten Monat anzeigen“
- 0.69 „verschiebe die Ticket-Dokumente von WhatsApp Documents nach Aufnahmen“ ~ „Verschiebe alle PDFs aus WhatsApp Documents nach Dokumente“
- 0.69 „lösche bitte alle pdfs in whatsapp documents die älter als 2 jahre sind“ ~ „Lösche im Download alle Dateien, die älter als 2 Jahre sind“
