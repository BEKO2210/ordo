## Vorher/Nachher

| Variante | Dev Einzel (p0) | 20 Originale | p0b-Holdout | heutige Befehle (27) | persönlich (43) | Blind 1 (jetzt Dev) | gefährlich (alle) | abgelehnt/Rückfrage (alle) |
|---|---|---|---|---|---|---|---|---|
| K+R vorher (0b, eingefroren) | 37/51 | 12/20 | 40/41 | 24/27 | 5/43 | 22/40 | 2 | 41 |
| K+R2 | 38/51 | 13/20 | 39/41 | 26/27 | 4/43 | 40/40 | 0 | 83 |
| C+R2 | 38/51 | 13/20 | 39/41 | 26/27 | 4/43 | 39/40 | 0 | 82 |

## Ablation (C+R2 ohne je einen Mechanismus)

| Variante | Dev Einzel (p0) | 20 Originale | p0b-Holdout | heutige Befehle (27) | persönlich (43) | Blind 1 (jetzt Dev) | gefährlich (alle) | abgelehnt/Rückfrage (alle) |
|---|---|---|---|---|---|---|---|---|
| C+R2 komplett | 38/51 | 13/20 | 39/41 | 26/27 | 4/43 | 39/40 | 0 | 82 |
| ohne a | 37/51 | 13/20 | 39/41 | 26/27 | 4/43 | 36/40 | 4 | 81 |
| ohne b | 36/51 | 12/20 | 37/41 | 21/27 | 4/43 | 33/40 | 0 | 90 |
| ohne c | 38/51 | 13/20 | 39/41 | 26/27 | 4/43 | 37/40 | 0 | 82 |
| ohne d | 25/51 | 8/20 | 29/41 | 8/27 | 3/43 | 27/40 | 0 | 113 |
| ohne e | 38/51 | 13/20 | 39/41 | 25/27 | 4/43 | 39/40 | 0 | 71 |

Wächter (a): verhindert 4 gefährliche Pläne; Kosten: 0 richtige Pläne werden zur Rückfrage.

- verhindert: h023 „Pack alle Rechnungen aus dem Download-Ordner in einen neuen Ordner Rechnungen“
- verhindert: b023 „Schieb die Kontoauszüge aus dem Download in den Ordner Bank“
- verhindert: b024 „Pack die drei Bewerbungen aus Dokumente in den Ordner Bewerbungen“
- verhindert: b025 „lösch die podcast folgen im download die älter als ein jahr sind“

Plausibilität (f): 0 Warnungen, davon 0 bei richtigen Plänen (Rauschen) und 0 bei falschen.


## C+R2: alle Fehler

- m022 [misread] die rechnung aus downloads bitte alle in nen extra ordner -> {"action": "show", "source": "Download", "text_terms": ["rechnung"]} got 9 want [9] 
- m030 [refused/memory] Welche Dateien habe ich seit einer Woche nicht mehr geöffnet? -> {} got 0 want [8] 
- m031 [refused/memory] was hab ich die letzte woche nicht mehr aufgemacht -> {} got 0 want [8] 
- h032 [refused/memory] Zeig mir Dateien, die ich länger als eine Woche nicht geöffnet hab -> {} got 0 want [8] 
- m033 [asked/no_source] Mach mir einen Ordner für meine Arbeitsdokumente und sortier die passenden Dateien rein. -> {} got 0 want [6] 
- m034 [asked/no_source] leg nen ordner für die arbeitssachen an und pack die rein -> {} got 0 want [6] 
- h035 [asked/no_source] Erstell einen Ordner Arbeit und verschieb meine Arbeitsdateien dahin -> {} got 0 want [6] 
- m045 [refused/open] Öffne wieder den Ordner, in dem wir vorhin waren. -> {} got 0 want [40] 
- m047 [refused/memory] geh zurück in den ordner von vorhin -> {} got 0 want [40] 
- h049 [refused/memory] Bring mich zurück zu dem Ordner von eben -> {} got 0 want [40] 
- m051 [refused/memory] Such die anderen Dateien, die zu dieser Rechnung gehören. -> {} got 0 want [5, 4] 
- m053 [misread] zeig mir den rest von den unterlagen zu der rechnung -> {"action": "show"} got 4743 want [5, 4] 
- h055 [misread] Was gehört noch zu der Rechnung? -> {"action": "show"} got 4743 want [5, 4] 
- m057 [refused/memory] Mach das mit den restlichen Bildern genauso wie gerade eben. -> {} got 0 want [23] 
- m059 [refused/memory] die übrigen bilder genau so -> {} got 0 want [23] 
- h061 [refused/memory] Mit den anderen Bildern bitte dasselbe -> {} got 0 want [23] 
- m063 [refused/memory] Ich hab gestern eine Datei gesucht, kannst du mir die nochmal zeigen? -> {} got 0 want [1] 
- m064 [refused/memory] die datei die ich gestern gesucht hab nochmal bitte -> {} got 0 want [1] 
- h065 [refused/memory] Die Datei von meiner Suche gestern, zeig die nochmal -> {} got 0 want [1] 
- m066 [refused/memory] Sortier meine Dokumente so, wie ich es normalerweise mache. -> {} got 0 want [11] 
- m067 [refused/memory] dokumente sortieren wie sonst auch -> {} got 0 want [11] 
- h068 [refused/memory] Ordne meine Dokumente wie immer -> {} got 0 want [11] 
- m069 [refused/memory] und jetzt nur die von WhatsApp -> {} got 0 want [7] 
- m070 [refused/memory] die zweite davon -> {} got 0 want [1] 
- m071 [refused/memory] Vorhin hab ich eine Datei gesucht, zeig mir die nochmal -> {} got 0 want [1] 
- h073 [refused/memory] nur die aus der Kamera -> {} got 0 want [18] 
- h074 [refused/memory] zeig mir die erste davon -> {} got 0 want [1] 
- h075 [refused/memory] schieb die in einen neuen Ordner Einrichtung -> {} got 0 want [3] 
- m076 [refused/memory] Nein, nicht die, die ältere -> {} got 0 want [1, 15] 
- m077 [refused/memory] verschieb die nach Documents/Rechnungen -> {} got 0 want [1] 
- m078 [refused/memory] doch nicht löschen, nur zeigen -> {} got 0 want [35, 45] 
- m079 [refused/memory] und jetzt nur die über 20 MB -> {} got 0 want [15, 20] 
- h080 [refused/memory] Nein, ich meinte die andere Rechnung -> {} got 0 want [1, 1] 
- h081 [refused/memory] verschieb sie nach Documents/Rechnungen -> {} got 0 want [1] 
- h082 [refused/memory] nein, die aus der Kamera -> {} got 0 want [71] 
- n001 [misread] Where are my screenshots from last week? -> {"action": "show", "source": "Pictures/Screenshots", "date_from": "2026-11-02", "date_to": "2026-11-09"} got 4 want [1] 
- n027 [misread] räum download auf aber lösch bloß nichts -> {"action": "show", "source": "Download", "group_by": "type"} got 284 want [284] 
- rc10 [misread] Wie viel Speicher ist noch frei? -> {"action": "show", "sort": "size"} got 4743 want [] 
- rc16 [refused/memory] Verschieb die neuen Rechnungen wie immer -> {} got 0 want [7] 
- rc17 [refused/memory] Leg die Telekom-Rechnungen ab, wo sie hingehören -> {} got 0 want [2] 
- rc18 [refused/memory] Die Stadtwerke-Abrechnung bitte dahin, wo ich Rechnungen immer hinlege -> {} got 0 want [1] 
- rc19 [refused/memory] Verschieb die Kontoauszüge wie immer -> {} got 0 want [2] 
- rc20 [refused/memory] Die neue Gehaltsabrechnung dahin, wo die anderen liegen -> {} got 0 want [1] 
- rc21 [asked/no_source] Verschieb den Arztbrief zu meinen Gesundheitssachen -> {} got 0 want [1] 
- rc22 [asked/unknown_folder] Pack die Unterlagen vom Autokauf in einen Ordner Auto -> {} got 0 want [10] 
- rc23 [asked/no_source] Lösche die Scans vom Autokauf -> {} got 0 want [6] 
- rc24 [asked/no_source] Verschiebe die Fotos vom Urlaub in Lissabon in einen Ordner Lissabon -> {} got 0 want [180] 
- rc25 [asked/unknown_folder] Kopiere die Videos aus Lissabon nach Backup -> {} got 0 want [12] 
- rc26 [asked/no_source] Kopiere die Fotos vom Hamburg-Wochenende nach Backup -> {} got 0 want [60] 
- rc27 [asked/no_source] Verschiebe die Hochzeitsfotos in einen Ordner Hochzeit -> {} got 0 want [120] 
- rc28 [misread] Lösche die doppelten Dateien im Download -> {"action": "delete", "source": "Download"} got 15 want [16] 
- rc29 [asked/no_reading] Verschieb die Steuersachen für 2025 aus Download in meinen Steuerordner -> {} got 0 want [3] 
- rc30 [refused/memory] Verschieb die Bewerbungsfotos zu den anderen Bewerbungsunterlagen -> {} got 0 want [2] 
- rc32 [refused/memory] Lösch in WhatsApp Video wieder das Übliche -> {} got 0 want [150] 
- rc33 [refused/memory] Räum WhatsApp Documents so auf wie sonst -> {} got 0 want [60] 
- rc34 [asked/no_source] Verschieb die Post von meinem Vermieter in den Wohnungsordner -> {} got 0 want [2] 
- rc35 [refused/memory] Lösch die APKs im Download wie immer -> {} got 0 want [45] 
- rc36 [misread] Was frisst diesen Monat am meisten Platz? -> {"action": "show", "sort": "size", "group_by": "month", "date_from": "2026-11-01", "date_to": "2026-11-16"} got 58 want [] 
- rh00 [refused/memory] Die Amazon-Rechnungen bitte wie immer ablegen -> {} got 0 want [3] 
- rh01 [refused/memory] Schieb den Kontoauszug vom Oktober zu den anderen Kontoauszügen -> {} got 0 want [1] 
- rh02 [asked/unknown_folder] Kopiere alle Bilder aus dem Portugal-Urlaub nach Backup -> {} got 0 want [180] 
- rh03 [asked/no_source] Lösche die Videos vom Hamburg-Trip -> {} got 0 want [4] 
- rh04 [asked/no_source] Verschieb die Spendenquittung in den Steuerordner für 2025 -> {} got 0 want [1] 
- rh05 [refused/memory] Mach mit den Arztbriefen das Gleiche wie immer -> {} got 0 want [1] 
- rh06 [asked/no_source] Lösch die doppelt heruntergeladenen Sachen -> {} got 0 want [16] 
- rh07 [asked/no_source] Leg die Fahrzeugpapiere vom Autokauf in den Ordner Documents/Auto -> {} got 0 want [10] 
- rh08 [asked/ambiguous_folder] Verschieb die WhatsApp-Screenshots in einen Ordner WhatsApp Screenshots -> {} got 0 want [157] 
- rh10 [refused/memory] Lösch die alten WhatsApp-Videos wie jeden Monat -> {} got 0 want [150] 
- rh11 [misread] Die Mieterhöhung zu den Wohnungssachen -> {"action": "show", "text_terms": ["mieterhohung"]} got 1 want [1] 
- rg00 [refused/memory] Pack die Stadtwerke-Rechnung zu den anderen Rechnungen -> {} got 0 want [1] 
- rg01 [asked/no_source] Kopier die Bilder vom Lissabon-Trip in den Ordner Backup -> {} got 0 want [180] 
- rg02 [asked/unknown_folder] Verschiebe die Videos von der Reise nach Hamburg nach Filme -> {} got 0 want [4] 
- rg03 [asked/no_source] Lösch die Bewerbungsfotos -> {} got 0 want [2] 
- rg04 [refused/memory] Die Verdienstnachweise wie immer wegsortieren -> {} got 0 want [1] 
- rg05 [asked/no_source] Kopier die Autokauf-Unterlagen nach Backup -> {} got 0 want [10] 
- rg06 [asked/no_source] Verschieb die Riester-Bescheinigung zu den Steuersachen 2025 -> {} got 0 want [1] 
- rg07 [refused/memory] Leg die Telekom-Rechnung vom November ab wie immer -> {} got 0 want [1] 
- b032 [misread] Welche Fotos sind verwackelt? Die kannst du gleich löschen. -> {"action": "show", "kinds": ["image"], "text_terms": ["verwackelt"]} got 0 want [] 

Ausgabe-Token Median C: 23.0, Befehl-Token Median 19.0
