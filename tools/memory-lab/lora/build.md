# Trainingsdaten (lora_build.py)

Sätze von den LLMs: 7046
Behalten: 6558 (Training 6363, Validierung 620, getrennt nach Plan, nicht nach Satz)

Klassen: show 2426, organize 848, delete 687, move 558, refuse 487, chat 314, copy 287, question 270, ask 216, storq 146, refuse2 115, dups 91, storfree 70, dupsimg 43
Generatoren: codex 3330, cursor:composer-2.5 1140, cursor:composer-2.5-fast 823, cursor:cursor-grok-4.6-low-fast 687, cursor:grok-4.7-low-fast 578
Netz (Regeln a-d) bei behaltenen Planfällen: plan 4361, ask 688, refuse 27

Verworfen: near a test sentence 131, time differs 108, duplicate 53, exclusion missing 42, time missing 35, source missing 31, target misses gold 21, refuse with delete verb 19, change said in a show 15, size missing 12, name missing 11, dest missing 6, content missing 4

## Leck-Prüfung gegen 284 Testsätze (p0, p0b, Blind 1, Blind 2, 20 Originale, Labor-Sätze)

Maß: max(Wort-Jaccard, Zeichen-Ähnlichkeit) nach Kleinschreibung/Umlaut-Faltung. Ab 0,70 verworfen.

- 1.00 „wie viel speicher ist noch frei“ ~ „Wie viel Speicher ist noch frei?“
- 1.00 „wie viel speicher hab ich noch frei?“ ~ „Wie viel Speicher hab ich noch frei?“
- 1.00 „Wie spät ist es.“ ~ „wie spät ist es“
- 0.98 „wieviel speicher ist noch frei?“ ~ „Wie viel Speicher ist noch frei?“
- 0.95 „Wie viel Speicher isch noch frei?“ ~ „Wie viel Speicher ist noch frei?“
- 0.95 „Speicher voll, was kann ich löschen??“ ~ „Speicher ist voll, was kann ich löschen?“
- 0.95 „ist es schlimm wenn der speicher fast voll ist“ ~ „Ist es schlimm wenn mein Speicher fast voll ist“
- 0.94 „ist es schlimm wenn speicher fast voll ist“ ~ „Ist es schlimm wenn mein Speicher fast voll ist“
- 0.94 „Ist es schlimm wenn der Speicher fast voll ist?“ ~ „Ist es schlimm wenn mein Speicher fast voll ist“
- 0.93 „hab ich irgendwo dateien doppelt drauf“ ~ „hab ich irgendwelche dateien doppelt drauf“
- 0.92 „Der Speicher isch voll, was kann ich löschen?“ ~ „Speicher ist voll, was kann ich löschen?“
- 0.91 „ist das schlimm wenn der speicher fast voll ist“ ~ „Ist es schlimm wenn mein Speicher fast voll ist“
- 0.90 „zeig mir mal die größten videos“ ~ „zeig mir die 5 größten videos“
- 0.90 „wie viel speicher ist noch frei bei mir“ ~ „Wie viel Speicher ist noch frei?“
- 0.90 „Ist das schlimm wenn Speicher fast voll ist?“ ~ „Ist es schlimm wenn mein Speicher fast voll ist“
- 0.90 „Mein Speicher ist voll, was könnte ich löschen?“ ~ „Speicher ist voll, was kann ich löschen?“
- 0.90 „Ist es schlimm, dass mein Speicher fast voll ist?“ ~ „Ist es schlimm wenn mein Speicher fast voll ist“
- 0.89 „ist das schlimm wenn der speicher fast voll is?“ ~ „Ist es schlimm wenn mein Speicher fast voll ist“
- 0.89 „Ist es eigentlich schlimm, wenn mein Speicher fast voll ist?“ ~ „Ist es schlimm wenn mein Speicher fast voll ist“
- 0.88 „Kann ich die Videos in WhatsApp Video löschen?“ ~ „kann ich die alten whatsapp videos löschen?“
- 0.88 „Speicher frei, wie viel hab ich noch, jetzt jetzt!“ ~ „Wie viel Speicher hab ich noch frei?“
- 0.87 „der speicher ist voll was kann ich denn löschen“ ~ „Speicher ist voll, was kann ich löschen?“
- 0.87 „Mein Speicher isch voll, was könnt ich löschen?“ ~ „Speicher ist voll, was kann ich löschen?“
- 0.87 „hey wie gehts“ ~ „hey wie gehts dir“
- 0.85 „lösch die zip dateien im download ordner“ ~ „Lösche alle APK-Dateien im Download-Ordner“

Nächste behaltene Sätze (unter 0,70):

- 0.70 „ich brauch mehr platz aufm handy, was kann weg?“ ~ „ich hab kein platz mehr was kann weg“
- 0.70 „Zeig mir die Sprachnachrichten von gestern.“ ~ „zeig mir die letzten fünf sprachnachrichten“
- 0.70 „Wo finde ich eigentlich meine Musikdateien?“ ~ „Wo sind eigentlich meine Sprachnachrichten?“
- 0.69 „Wo sind meine files“ ~ „wo sind meine whats app videos“
- 0.69 „Lösch die Dokumente im Download-Ordner.“ ~ „Lösch die APKs im Download wie immer“
- 0.69 „Lösch mal die Sachen.“ ~ „mach mal die großen sachen weg“
- 0.69 „Find the WhatsApp voice messages in the Audio folder.“ ~ „find my whatsapp voice messages“
- 0.69 „Zeig mir mal die Dateien von Lea.“ ~ „Zeig mir mal die größten Dateien auf meinem Handy.“
- 0.69 „Die Mahnung-PDFs aus WhatsApp Documents nach Formulare schieben!“ ~ „Verschiebe alle PDFs aus WhatsApp Documents nach Dokumente“
- 0.69 „I want to find my voice messages.“ ~ „find my whatsapp voice messages“
