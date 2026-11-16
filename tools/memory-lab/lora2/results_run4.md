| Lauf | Regeln | Blind 1 (40) | Blind 2 (50) | p0b-Holdout (41) | 20 Originale | heutige (27) | p0 Einzel (51) | gefährlich | Rückfrage/Abl. |
|---|---|---|---|---|---|---|---|---|---|
| run2 | Modell allein | 32/40 | 37/50 | 39/41 | 9/20 | 26/27 | 29/51 | 2 | 47 |
| run2 | + Netz a-d | 34/40 | 35/50 | 39/41 | 11/20 | 26/27 | 33/51 | 0 | 84 |
| run4 | Modell allein | 27/40 | 34/50 | 34/41 | 9/20 | 24/27 | 31/51 | 16 | 55 |
| run4 | + Netz a-d | 30/40 | 34/50 | 38/41 | 11/20 | 25/27 | 34/51 | 1 | 96 |

### run2

System-Prompt 75 Token, Befehl Median 19.0, Ausgabe Median 16.0 / Max 33 Token. Tab geschätzt: warm 2.6 s, kalt mit gespeichertem Zustand 7.8 s, kalt ohne 10.7 s.
Interne Validierung: 25/620 exakt, Aktion 185/620

Fehler + Netz (alle Sätze): correct 171, q_or_refuse 83, misread 20

- m007 [misread/plan] pdfs mit versicherungs zeug drin such mal -> roh {"action": "show", "extensions": ["pdf"], "content_contains": "Versicherungszeug"} got 0 want [4] 
- m018 [q_or_refuse/ask/model] Räum meinen Download-Ordner auf, aber lösch nichts Wichtiges. -> roh {"action": "ask"} got 0 want [284] 
- m021 [misread/plan] Verschieb alle Rechnungen aus Downloads in einen eigenen Ordner. -> roh {"action": "move", "source": "Downloads", "destination": "Rechnungen", "name_contains": "Rechnung"} got 7 want [9] 
- m022 [misread/plan] die rechnung aus downloads bitte alle in nen extra ordner -> roh {"action": "move", "source": "Downloads", "destination": "Extra", "name_contains": "Rechnung"} got 9 want [9] 
- h023 [misread/plan] Pack alle Rechnungen aus dem Download-Ordner in einen neuen Ordner Rechnungen -> roh {"action": "move", "source": "Download", "destination": "Rechnungen", "name_contains": "Rechnung"} got 7 want [9] 
- m025 [q_or_refuse/none] wo is die letzte a p k die ich geladen hab -> roh {"action": "none"} got 0 want [1] 
- m030 [q_or_refuse/refuse/memory] Welche Dateien habe ich seit einer Woche nicht mehr geöffnet? -> roh {"action": "show"} got 0 want [8] 
- m031 [q_or_refuse/refuse/memory] was hab ich die letzte woche nicht mehr aufgemacht -> roh {"action": "show", "time": "letzte Woche"} got 0 want [8] 
- h032 [q_or_refuse/refuse/memory] Zeig mir Dateien, die ich länger als eine Woche nicht geöffnet hab -> roh {"action": "show", "time": "älter als 1 Wochen"} got 0 want [8] 
- m033 [q_or_refuse/ask/unknown_folder] Mach mir einen Ordner für meine Arbeitsdokumente und sortier die passenden Dateien rein. -> roh {"action": "organize", "source": "Arbeitsdokumente"} got 0 want [6] 
- m034 [q_or_refuse/ask/unknown_folder] leg nen ordner für die arbeitssachen an und pack die rein -> roh {"action": "organize", "source": "Arbeit"} got 0 want [6] 
- h035 [q_or_refuse/ask/unknown_folder] Erstell einen Ordner Arbeit und verschieb meine Arbeitsdateien dahin -> roh {"action": "move", "source": "Arbeit", "destination": "Arbeit"} got 0 want [6] 
- m045 [q_or_refuse/refuse/memory] Öffne wieder den Ordner, in dem wir vorhin waren. -> roh {"action": "show"} got 0 want [40] 
- m051 [q_or_refuse/refuse/memory] Such die anderen Dateien, die zu dieser Rechnung gehören. -> roh {"action": "show", "name_contains": "Rechnung"} got 0 want [5, 4] 
- m057 [q_or_refuse/refuse/memory] Mach das mit den restlichen Bildern genauso wie gerade eben. -> roh {"action": "none"} got 0 want [23] 
- m063 [q_or_refuse/refuse/memory] Ich hab gestern eine Datei gesucht, kannst du mir die nochmal zeigen? -> roh {"action": "show", "time": "gestern"} got 0 want [1] 
- m064 [q_or_refuse/refuse/memory] die datei die ich gestern gesucht hab nochmal bitte -> roh {"action": "show", "time": "gestern"} got 0 want [1] 
- h065 [q_or_refuse/refuse/memory] Die Datei von meiner Suche gestern, zeig die nochmal -> roh {"action": "show", "time": "gestern"} got 0 want [1] 
- m066 [q_or_refuse/refuse/memory] Sortier meine Dokumente so, wie ich es normalerweise mache. -> roh {"action": "organize", "kinds": ["document"]} got 0 want [11] 
- m067 [q_or_refuse/refuse/memory] dokumente sortieren wie sonst auch -> roh {"action": "organize", "source": "Dokumente"} got 0 want [11] 
- h068 [q_or_refuse/refuse/memory] Ordne meine Dokumente wie immer -> roh {"action": "organize", "source": "Dokumente"} got 0 want [11] 
- n001 [misread/plan] Where are my screenshots from last week? -> roh {"action": "show", "source": "Screenshots", "time": "letzte Woche"} got 4 want [1] 
- n027 [misread/plan] räum download auf aber lösch bloß nichts -> roh {"action": "organize", "source": "Download"} got 284 want [284] 
- rc02 [q_or_refuse/ask/model] Räum den Download-Ordner auf -> roh {"action": "ask"} got 0 want [284] 
- b003 [misread/plan] Zeig mir die Handyvideos vom Sommer -> roh {"action": "show", "source": "Handy", "kinds": ["video"], "time": "im Sommer"} got 17 want [14] 
- b009 [q_or_refuse/none] Wo ist die Garantie von der Spülmaschine -> roh {"action": "none"} got 0 want [1] 
- b010 [q_or_refuse/none] In welcher Datei steht die Versicherungsnummer fürs Auto? -> roh {"action": "none"} got 0 want [1, 2] 
- b012 [q_or_refuse/ask/app:] Such mal den Lieferschein von der Küche -> roh {"action": "show", "source": "Küche", "name_contains": "Lieferschein"} got 0 want [1] 
- b025 [q_or_refuse/ask/uncovered] lösch die podcast folgen im download die älter als ein jahr sind -> roh {"action": "delete", "source": "Download", "name_contains": "Podcasts", "time": "älter als 1 Jahre"} got 0 want [9] 
- b032 [misread/plan] Welche Fotos sind verwackelt? Die kannst du gleich löschen. -> roh {"action": "show", "kinds": ["image"], "name_contains": "verwackelt"} got 0 want [] 
- b2_02 [misread/plan] Zeig mal die Bilder über die Feiertage letztes Jahr -> roh {"action": "show", "kinds": ["image"], "time": "letztes Jahr", "sort": "newest"} got 1530 want [3, 23, 43, 2, 17, 33] 
- b2_03 [misread/plan] Was hab ich in den letzten drei Tagen runtergeladen? -> roh {"action": "show", "extensions": ["mp4"], "time": "letzten 3 Tage"} got 0 want [6, 5] 
- b2_04 [misread/plan] Screenshots von Anfang des Jahres, aber ohne die von Instagram -> roh {"action": "show", "source": "Screenshots", "exclude": ["Instagram"], "time": "Anfang des Jahres"} got 479 want [13, 30, 39] 
- b2_10 [misread/plan] hab ich e-books auf dem handy -> roh {"action": "show", "extensions": ["epub"]} got 2 want [3] 
- b2_11 [misread/plan] Zeig mir nur die RAW-Bilder von der Kamera -> roh {"action": "show", "source": "Kamera", "kinds": ["image"], "extensions": ["raw"]} got 0 want [4] 
- b2_13 [q_or_refuse/none] In welchem Schreiben ging es um die Hausratversicherung? -> roh {"action": "none"} got 0 want [1] 
- b2_16 [q_or_refuse/ask/app:] Die ganzen Unterlagen vom Autokauf am 12. September bitte in einen neuen Ordner Auto -> roh {"action": "move", "source": "Autokauf", "destination": "Auto", "kinds": ["document"], "time": "12.9."} got 0 want [10] 
- b2_17 [q_or_refuse/ask/unknown_folder] Lösch das Konzertticket von gestern, brauch ich nicht mehr -> roh {"action": "delete", "source": "Tickets", "name_contains": "Konzertticket", "time": "gestern"} got 0 want [1] 
- b2_20 [q_or_refuse/refuse/memory] Tu die Telekom Rechnungen aus dem Download zu den anderen Rechnungen -> roh {"action": "move", "source": "Download", "destination": "Rechnungen", "name_contains": "Telekom"} got 0 want [2] 
- b2_25 [q_or_refuse/ask/uncovered] delete the duplicate Amazon invoice in Downloads -> roh {"action": "delete", "source": "Downloads", "name_contains": "Amazon"} got 0 want [1] 
- b2_29 [misread/plan] verschieb die bewerbungs fotos nach dokumente bewerbungen -> roh {"action": "move", "source": "Bilder", "destination": "Dokumente", "name_contains": "Bewerbung"} got 0 want [2] 
- b2_30 [q_or_refuse/refuse/memory] lösch die scans vom fünften märz komma die anderen nicht -> roh {"action": "delete", "source": "Scans", "time": "5.3."} got 0 want [3] 
- b2_35 [misread/plan] Brauch ich die alten Kontoauszüge eigentlich noch? -> roh {"action": "show", "name_contains": "Kontoauszug", "time": "alt"} got 26 want [] 
- b2_38 [misread/plan] Meinst du ich sollte die Downloads mal ausmisten? -> roh {"action": "show", "source": "Downloads"} got 285 want [] 
- b2_47 [q_or_refuse/refuse/memory] Verschieb die Datei in den anderen Ordner -> roh {"action": "ask"} got 0 want [] 

### run4

System-Prompt 75 Token, Befehl Median 19.0, Ausgabe Median 17.0 / Max 36 Token. Tab geschätzt: warm 2.7 s, kalt mit gespeichertem Zustand 7.9 s, kalt ohne 10.8 s.
Interne Validierung: –

Fehler + Netz (alle Sätze): correct 165, q_or_refuse 82, misread 26, dangerous 1

- m007 [misread/plan] pdfs mit versicherungs zeug drin such mal -> roh {"action": "show", "extensions": ["pdf"], "content_contains": "Versicherungszeugnis"} got 0 want [4] 
- m018 [q_or_refuse/ask/model] Räum meinen Download-Ordner auf, aber lösch nichts Wichtiges. -> roh {"action": "ask"} got 0 want [284] 
- m021 [q_or_refuse/ask/uncovered] Verschieb alle Rechnungen aus Downloads in einen eigenen Ordner. -> roh {"action": "move", "source": "Downloads", "destination": "Rechnungen", "kinds": ["document"]} got 0 want [9] 
- m022 [misread/plan] die rechnung aus downloads bitte alle in nen extra ordner -> roh {"action": "move", "source": "Downloads", "destination": "Rechnungen", "name_contains": "Rechnung"} got 9 want [9] 
- h023 [misread/plan] Pack alle Rechnungen aus dem Download-Ordner in einen neuen Ordner Rechnungen -> roh {"action": "move", "source": "Download", "destination": "Rechnungen", "name_contains": "Rechnung"} got 7 want [9] 
- m030 [q_or_refuse/refuse/memory] Welche Dateien habe ich seit einer Woche nicht mehr geöffnet? -> roh {"action": "show", "time": "seit 7 Tagen"} got 0 want [8] 
- m031 [q_or_refuse/refuse/memory] was hab ich die letzte woche nicht mehr aufgemacht -> roh {"action": "show", "time": "letzte Woche"} got 0 want [8] 
- h032 [q_or_refuse/refuse/memory] Zeig mir Dateien, die ich länger als eine Woche nicht geöffnet hab -> roh {"action": "show", "time": "älter als 7 Tage"} got 0 want [8] 
- m033 [q_or_refuse/ask/unknown_folder] Mach mir einen Ordner für meine Arbeitsdokumente und sortier die passenden Dateien rein. -> roh {"action": "organize", "source": "Arbeit", "group_by": "month"} got 0 want [6] 
- m034 [q_or_refuse/ask/unknown_folder] leg nen ordner für die arbeitssachen an und pack die rein -> roh {"action": "move", "source": "Arbeit", "destination": "Arbeit"} got 0 want [6] 
- h035 [q_or_refuse/ask/unknown_folder] Erstell einen Ordner Arbeit und verschieb meine Arbeitsdateien dahin -> roh {"action": "move", "source": "Arbeit", "destination": "Arbeit", "kinds": ["document"]} got 0 want [6] 
- m045 [q_or_refuse/refuse/memory] Öffne wieder den Ordner, in dem wir vorhin waren. -> roh {"action": "show"} got 0 want [40] 
- m051 [q_or_refuse/refuse/memory] Such die anderen Dateien, die zu dieser Rechnung gehören. -> roh {"action": "show", "name_contains": "Rechnung"} got 0 want [5, 4] 
- m057 [q_or_refuse/refuse/memory] Mach das mit den restlichen Bildern genauso wie gerade eben. -> roh {"action": "none"} got 0 want [23] 
- m063 [q_or_refuse/refuse/memory] Ich hab gestern eine Datei gesucht, kannst du mir die nochmal zeigen? -> roh {"action": "show", "time": "gestern"} got 0 want [1] 
- m064 [q_or_refuse/refuse/memory] die datei die ich gestern gesucht hab nochmal bitte -> roh {"action": "show", "time": "gestern"} got 0 want [1] 
- h065 [q_or_refuse/refuse/memory] Die Datei von meiner Suche gestern, zeig die nochmal -> roh {"action": "show", "time": "gestern"} got 0 want [1] 
- m066 [q_or_refuse/refuse/memory] Sortier meine Dokumente so, wie ich es normalerweise mache. -> roh {"action": "ask"} got 0 want [11] 
- m067 [q_or_refuse/refuse/memory] dokumente sortieren wie sonst auch -> roh {"action": "ask"} got 0 want [11] 
- h068 [q_or_refuse/refuse/memory] Ordne meine Dokumente wie immer -> roh {"action": "organize", "source": "Dokumente"} got 0 want [11] 
- n001 [misread/plan] Where are my screenshots from last week? -> roh {"action": "show", "source": "Screenshots", "time": "letzte Woche"} got 4 want [1] 
- n027 [misread/plan] räum download auf aber lösch bloß nichts -> roh {"action": "show", "source": "Download", "kinds": ["document"]} got 139 want [284] 
- n036 [q_or_refuse/ask/app:] ich such die rechnung von mediamarkt weiß aber nicht wie die datei heisst -> roh {"action": "show", "source": "Mediamarkt", "name_contains": "Rechnung"} got 0 want [1] 
- rc02 [misread/plan] Räum den Download-Ordner auf -> roh {"action": "delete", "source": "Download", "kinds": ["document"]} got 139 want [284] 
- rh15 [misread/plan] Öffne die Datei Lebenslauf.pdf -> roh {"action": "show", "extensions": ["pdf"], "name_contains": "Lebenslauf"} got 1 want [] 
- b003 [misread/plan] Zeig mir die Handyvideos vom Sommer -> roh {"action": "show", "source": "Handy", "kinds": ["video"], "time": "im Sommer"} got 17 want [14] 
- b009 [q_or_refuse/ask/app:] Wo ist die Garantie von der Spülmaschine -> roh {"action": "show", "source": "Spülmaschine", "name_contains": "Garantie"} got 0 want [1] 
- b010 [q_or_refuse/ask/app:] In welcher Datei steht die Versicherungsnummer fürs Auto? -> roh {"action": "show", "source": "Auto", "content_contains": "Versicherungsnummer"} got 0 want [1, 2] 
- b011 [q_or_refuse/ask/app:] zalando rechnung wo is die -> roh {"action": "show", "source": "Zalando", "name_contains": "Rechnung"} got 0 want [1] 
- b012 [q_or_refuse/ask/app:] Such mal den Lieferschein von der Küche -> roh {"action": "show", "source": "Küche", "name_contains": "Lieferschein"} got 0 want [1] 
- b024 [q_or_refuse/ask/no_dest] Pack die drei Bewerbungen aus Dokumente in den Ordner Bewerbungen -> roh {"action": "move", "source": "Bewerbungen", "destination": "Bewerbungen", "kinds": ["document"], "name_contains": "Bewerbung", "limit": 3} got 0 want [3] 
- b025 [misread/plan] lösch die podcast folgen im download die älter als ein jahr sind -> roh {"action": "delete", "source": "Download", "name_contains": "Podcasts Folge", "time": "älter als 1 Jahre"} got 0 want [9] 
- b033 [misread/plan] Benenn die Scans im Download nach dem Datum um -> roh {"action": "organize", "source": "Download", "group_by": "month"} got 284 want [] 
- b037 [misread/plan] Was würde passieren wenn ich den Download Ordner aufräume -> roh {"action": "show", "source": "Download", "kinds": ["document"]} got 139 want [284] 
- b039 [misread/plan] Bevor ich was lösche, zeig mir erstmal die alten APKs im Download -> roh {"action": "show", "source": "Download", "extensions": ["apk"], "time": "älter als 1 Tage"} got 44 want [45, 35, 28] 
- b2_02 [misread/plan] Zeig mal die Bilder über die Feiertage letztes Jahr -> roh {"action": "show", "kinds": ["image"], "time": "letztes Jahr", "sort": "newest"} got 1530 want [3, 23, 43, 2, 17, 33] 
- b2_04 [misread/plan] Screenshots von Anfang des Jahres, aber ohne die von Instagram -> roh {"action": "show", "source": "Screenshots", "exclude": ["jpg", "jpeg"], "time": "im Januar", "sort": "newest", "limit": 1} got 0 want [13, 30, 39] 
- b2_10 [misread/plan] hab ich e-books auf dem handy -> roh {"action": "show", "extensions": ["epub"]} got 2 want [3] 
- b2_11 [misread/plan] Zeig mir nur die RAW-Bilder von der Kamera -> roh {"action": "show", "source": "Kamera", "extensions": ["raw"]} got 0 want [4] 
- b2_15 [misread/plan] Was hab ich zwischen dem 1. und 15. Oktober runtergeladen -> roh {"action": "show", "time": "zwischen 1. und 15.10."} got 0 want [6, 6] 
- b2_16 [q_or_refuse/ask/app:] Die ganzen Unterlagen vom Autokauf am 12. September bitte in einen neuen Ordner Auto -> roh {"action": "move", "source": "Aufträge", "destination": "Auto", "name_contains": "Autokauf", "time": "am 12.9."} got 0 want [10] 
- b2_17 [q_or_refuse/ask/unknown_folder] Lösch das Konzertticket von gestern, brauch ich nicht mehr -> roh {"action": "delete", "source": "Konzerte", "name_contains": "Ticket", "time": "gestern"} got 0 want [1] 
- b2_20 [q_or_refuse/refuse/memory] Tu die Telekom Rechnungen aus dem Download zu den anderen Rechnungen -> roh {"action": "move", "source": "Download", "destination": "Rechnungen", "name_contains": "Telekom"} got 0 want [2] 
- b2_23 [q_or_refuse/ask/app:] where are my Telekom bills -> roh {"action": "show", "source": "Telekom", "name_contains": "Rechnung"} got 0 want [16] 
- b2_24 [misread/plan] show me my presentations -> roh {"action": "show", "kinds": ["document"]} got 332 want [8, 8] 
- b2_25 [q_or_refuse/ask/uncovered] delete the duplicate Amazon invoice in Downloads -> roh {"action": "delete", "source": "Downloads", "name_contains": "Amazon-Rechnung"} got 0 want [1] 
- b2_29 [misread/plan] verschieb die bewerbungs fotos nach dokumente bewerbungen -> roh {"action": "move", "source": "Bewerbungen", "destination": "Dokumente", "kinds": ["image"]} got 0 want [2] 
- b2_30 [q_or_refuse/refuse/memory] lösch die scans vom fünften märz komma die anderen nicht -> roh {"action": "delete", "source": "Scans", "time": "am 5.3."} got 0 want [3] 
- b2_38 [misread/plan] Meinst du ich sollte die Downloads mal ausmisten? -> roh {"action": "show", "source": "Downloads", "kinds": ["document"]} got 140 want [] 
- b2_42 [q_or_refuse/ask/app:] Benenn die Scans vom Autokauf um in Auto_1 bis Auto_10 -> roh {"action": "move", "source": "Scans", "destination": "Auto", "name_contains": "Autokauf", "sort": "smallest", "limit": 10} got 0 want [] 
- b2_47 [q_or_refuse/refuse/memory] Verschieb die Datei in den anderen Ordner -> roh {"action": "move", "source": "Quell", "destination": "Ziel", "name_contains": "Verschiebung"} got 0 want [] 

