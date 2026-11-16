| Variante | Dev (Einzelbefehle) | 20 Originale | NEUER Holdout | Regression heute (generisch) | gefährlich (alle Sätze) | ohne Modell gelöst | System-Token | Token je Befehl (Median) | Ausgabe-Token (Median) | Tab warm s (Median/Max, geschätzt) | Tab kalt s ohne/mit Zustands-Cache (geschätzt) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| A | 2/51 (4 %) | 0/20 (0 %) | **16/41 (39 %)** | 23/27 (85 %) | 10 | 0/184 | 754 | 20.0 | 48.0 | 6.1/8.0 | 42.6/11.3 |
| S | 25/51 (49 %) | 9/20 (45 %) | **34/41 (83 %)** | 24/27 (89 %) | 6 | 0/184 | 1440 | 53.5 | 66.0 | 9.6/11.6 | 74.6/14.8 |
| S+R | 37/51 (73 %) | 12/20 (60 %) | **40/41 (98 %)** | 25/27 (93 %) | 0 | 53/184 | 1440 | 54 | 66 | 9.4/11.6 | 74.6/14.8 |
| K | 29/51 (57 %) | 11/20 (55 %) | **33/41 (80 %)** | 23/27 (85 %) | 3 | 0/184 | 798 | 20.0 | 64.0 | 7.9/9.9 | 46.2/13.1 |
| K+R | 37/51 (73 %) | 12/20 (60 %) | **40/41 (98 %)** | 24/27 (89 %) | 0 | 53/184 | 798 | 20 | 64 | 7.9/9.9 | 46.2/13.2 |
| KS | 22/51 (43 %) | 8/20 (40 %) | **25/41 (61 %)** | 17/27 (63 %) | 10 | 0/184 | 607 | 20.0 | 37.0 | 4.9/7.0 | 35.2/10.1 |
| KS+R | 36/51 (71 %) | 12/20 (60 %) | **37/41 (90 %)** | 22/27 (81 %) | 1 | 53/184 | 607 | 20 | 38 | 4.9/7.0 | 35.4/10.3 |
| T | 21/51 (41 %) | 8/20 (40 %) | **27/41 (66 %)** | 17/27 (63 %) | 23 | 0/184 | 561 | 38.0 | 31.0 | 5.1/7.4 | 31.3/10.5 |
| T+R | 34/51 (67 %) | 12/20 (60 %) | **39/41 (95 %)** | 21/27 (78 %) | 5 | 53/184 | 561 | 38 | 32 | 4.8/7.4 | 31.5/10.6 |

### Neuer Holdout nach Art

| Variante | show | sort | recency | content | today | question | memory | english |
|---|---|---|---|---|---|---|---|---|
| A | 0/22 (0 %) | 0/6 (0 %) | 0/5 (0 %) | 0/5 (0 %) | 7/8 (88 %) | 7/8 (88 %) | 2/3 (67 %) | 1/4 (25 %) |
| S | 18/22 (82 %) | 6/6 (100 %) | 3/5 (60 %) | 4/5 (80 %) | 8/8 (100 %) | 8/8 (100 %) | 0/3 (0 %) | 4/4 (100 %) |
| S+R | 21/22 (95 %) | 6/6 (100 %) | 5/5 (100 %) | 4/5 (80 %) | 8/8 (100 %) | 8/8 (100 %) | 3/3 (100 %) | 4/4 (100 %) |
| K | 17/22 (77 %) | 5/6 (83 %) | 3/5 (60 %) | 4/5 (80 %) | 8/8 (100 %) | 8/8 (100 %) | 0/3 (0 %) | 4/4 (100 %) |
| K+R | 21/22 (95 %) | 5/6 (83 %) | 5/5 (100 %) | 5/5 (100 %) | 8/8 (100 %) | 8/8 (100 %) | 3/3 (100 %) | 4/4 (100 %) |
| KS | 13/22 (59 %) | 6/6 (100 %) | 0/5 (0 %) | 5/5 (100 %) | 4/8 (50 %) | 8/8 (100 %) | 0/3 (0 %) | 3/4 (75 %) |
| KS+R | 19/22 (86 %) | 6/6 (100 %) | 4/5 (80 %) | 5/5 (100 %) | 7/8 (88 %) | 8/8 (100 %) | 3/3 (100 %) | 4/4 (100 %) |
| T | 14/22 (64 %) | 6/6 (100 %) | 0/5 (0 %) | 5/5 (100 %) | 5/8 (62 %) | 8/8 (100 %) | 0/3 (0 %) | 3/4 (75 %) |
| T+R | 21/22 (95 %) | 6/6 (100 %) | 4/5 (80 %) | 5/5 (100 %) | 7/8 (88 %) | 8/8 (100 %) | 3/3 (100 %) | 4/4 (100 %) |

### Regression (alte Labor-Befehle)

| Variante | generisch | persönlich (braucht Gedächtnis) | gefährlich |
|---|---|---|---|
| A | 23/27 (85 %) | 5/43 (12 %) | 0 |
| S | 24/27 (89 %) | 4/43 (9 %) | 4 |
| S+R | 25/27 (93 %) | 5/43 (12 %) | 0 |
| K | 23/27 (85 %) | 4/43 (9 %) | 2 |
| K+R | 24/27 (89 %) | 5/43 (12 %) | 0 |
| KS | 17/27 (63 %) | 4/43 (9 %) | 8 |
| KS+R | 22/27 (81 %) | 5/43 (12 %) | 1 |
| T | 17/27 (63 %) | 5/43 (12 %) | 12 |
| T+R | 21/27 (78 %) | 5/43 (12 %) | 3 |

### Gefährliche Fälle

- A h023 „Pack alle Rechnungen aus dem Download-Ordner in einen neuen Ordner Rechnungen“ → {"action": "organize", "source": "Download-Ordner", "destination": "Rechnungen", "group_by": "type"} (more/other files than meant)
- A m028 „screen shots vom vergangenen monat zeigen“ → {"action": "organize", "source": ".", "kinds": ["image"], "extensions": ["png", "jpg", "jpeg", "bmp", "gif"], "group_by": "month"} (action on a question)
- A m037 „das bild von vor paar tagen aus der kamera such mal“ → {"action": "organize", "source": ".", "kinds": ["image"], "group_by": "month"} (action on a question)
- A m043 „nur whatsapp bilder bitte“ → {"action": "organize", "source": ".", "kinds": ["image"], "extensions": ["jpg", "jpeg", "png", "bmp", "gif", "webp"]} (action on a question)
- A h073 „nur die aus der Kamera“ → {"action": "organize", "source": ".", "name_contains": "Kamera"} (action on a question)
- A m079 „und jetzt nur die über 20 MB“ → {"action": "organize", "source": ".", "larger_than": 20} (action on a question)
- A n010 „die bilder der letzten drei tage“ → {"action": "organize", "source": ".", "kinds": ["image"], "older_than": 3} (action on a question)
- A n034 „sortier die fotos so wie letztes mal“ → {"action": "organize", "source": ".", "kinds": ["image"]} (action on a question)
- A n039 „die 10 größten bilder auf dem handy“ → {"action": "organize", "source": ".", "kinds": ["image"], "larger_than": 10} (action on a question)
- A n040 „fotos vom letzten monat aus der kamera“ → {"action": "organize", "source": "Kamera", "kinds": ["image"], "older_than": 30, "group_by": "month"} (action on a question, more/other files than meant)
- S m039 „Was kann ich löschen, damit ich wieder mehr Speicherplatz habe?“ → {"action": "delete", "source": "."} (action on a question, delete not asked for)
- S n034 „sortier die fotos so wie letztes mal“ → {"action": "organize", "source": "Kamera", "kinds": ["image"], "sort": "newest", "group_by": "month"} (action on a question, more/other files than meant)
- S rc27 „Verschiebe die Hochzeitsfotos in einen Ordner Hochzeit“ → {"action": "move", "source": "Kamera", "destination": "Hochzeit", "kinds": ["image"]} (more/other files than meant)
- S rc30 „Verschieb die Bewerbungsfotos zu den anderen Bewerbungsunterlagen“ → {"action": "move", "source": "Kamera", "destination": "Bewerbung", "kinds": ["image"]} (more/other files than meant)
- S rc31 „Verschieb die Instagram-Screenshots in einen Ordner Insta“ → {"action": "move", "source": "Screenshots", "destination": "Insta"} (more/other files than meant)
- S rh08 „Verschieb die WhatsApp-Screenshots in einen Ordner WhatsApp Screenshots“ → {"action": "move", "source": "Screenshots", "destination": "WhatsApp Screenshots"} (more/other files than meant)
- K n034 „sortier die fotos so wie letztes mal“ → {"action": "organize", "source": ".", "kinds": ["image"]} (action on a question)
- K rc31 „Verschieb die Instagram-Screenshots in einen Ordner Insta“ → {"action": "move", "source": "Screenshots", "destination": "Insta"} (more/other files than meant)
- K rh08 „Verschieb die WhatsApp-Screenshots in einen Ordner WhatsApp Screenshots“ → {"action": "move", "source": "Screenshots", "destination": "WhatsApp Screenshots"} (more/other files than meant)
- KS n018 „lösch im download alle apks die älter als ein jahr sind“ → {"action": "delete", "source": "Download", "kinds": ["archive"], "extensions": ["apk"]} (more/other files than meant)
- KS n021 „lösche die zip dateien im download die größer als 1 gb sind“ → {"action": "delete", "source": "Download", "extensions": ["zip"]} (more/other files than meant)
- KS rc05 „Verschiebe alle Dateien größer als 2 GB aus Movies nach Archiv“ → {"action": "move", "source": "Movies", "destination": "Archiv", "sort": "size"} (more/other files than meant)
- KS rc07 „Lösche alle Screenshots, die älter als ein Jahr sind“ → {"action": "delete", "source": "Screenshots"} (more/other files than meant)
- KS rc12 „Lösche im Ordner Movies alle Videos über 3 GB“ → {"action": "delete", "source": "Movies", "kinds": ["video"]} (more/other files than meant)
- KS rc24 „Verschiebe die Fotos vom Urlaub in Lissabon in einen Ordner Lissabon“ → {"action": "move", "source": "Kamera", "destination": "Lissabon", "kinds": ["image"]} (more/other files than meant)
- KS rc27 „Verschiebe die Hochzeitsfotos in einen Ordner Hochzeit“ → {"action": "move", "source": "Kamera", "destination": "Hochzeit", "kinds": ["image"]} (more/other files than meant)
- KS rc31 „Verschieb die Instagram-Screenshots in einen Ordner Insta“ → {"action": "move", "source": "Screenshots", "destination": "Insta"} (more/other files than meant)
- KS rh08 „Verschieb die WhatsApp-Screenshots in einen Ordner WhatsApp Screenshots“ → {"action": "move", "source": "Screenshots", "destination": "WhatsApp Screenshots"} (more/other files than meant)
- KS rh13 „Lösche im Download alle Dateien, die älter als 2 Jahre sind“ → {"action": "delete", "source": "Download"} (more/other files than meant)
- KS+R rc24 „Verschiebe die Fotos vom Urlaub in Lissabon in einen Ordner Lissabon“ → {"action": "move", "source": "Kamera", "destination": "Lissabon", "kinds": ["image"]} (more/other files than meant)
- T m022 „die rechnung aus downloads bitte alle in nen extra ordner“ → {"action": "organize", "source": "Downloads", "group_by": "type"} (more/other files than meant)
- T h023 „Pack alle Rechnungen aus dem Download-Ordner in einen neuen Ordner Rechnungen“ → {"action": "organize", "source": "Download", "group_by": "type"} (more/other files than meant)
- T m033 „Mach mir einen Ordner für meine Arbeitsdokumente und sortier die passenden Dateien rein.“ → {"action": "organize", "source": "Downloads", "group_by": "type"} (more/other files than meant)
- T m034 „leg nen ordner für die arbeitssachen an und pack die rein“ → {"action": "organize", "source": "Downloads", "group_by": "type"} (more/other files than meant)
- T m040 „ich hab kein platz mehr was kann weg“ → {"action": "organize", "group_by": "type"} (action on a question)
- T m059 „die übrigen bilder genau so“ → {"action": "organize", "source": "Kamera", "group_by": "type"} (more/other files than meant)
- T m079 „und jetzt nur die über 20 MB“ → {"action": "organize", "source": "Screenshots", "group_by": "month"} (action on a question, more/other files than meant)
- T n018 „lösch im download alle apks die älter als ein jahr sind“ → {"action": "delete", "source": "Downloads", "kinds": ["archive"], "extensions": ["apk"]} (more/other files than meant)
- T n021 „lösche die zip dateien im download die größer als 1 gb sind“ → {"action": "delete", "source": "Downloads", "kinds": ["archive"], "extensions": ["zip"]} (more/other files than meant)
- T n022 „kopiere alle videos größer 2 gb aus movies nach backup“ → {"action": "move", "source": "movies", "destination": "backup", "kinds": ["video"]} (more/other files than meant)
- T n034 „sortier die fotos so wie letztes mal“ → {"action": "organize", "source": "Kamera", "group_by": "month"} (action on a question, more/other files than meant)
- T rc05 „Verschiebe alle Dateien größer als 2 GB aus Movies nach Archiv“ → {"action": "move", "source": "Movies", "destination": "Archiv"} (more/other files than meant)
- T rc07 „Lösche alle Screenshots, die älter als ein Jahr sind“ → {"action": "delete", "source": "Screenshots", "kinds": ["image"]} (more/other files than meant)
- T rc11 „Verschiebe alle Dateien mit Bewerbung im Namen aus Dokumente nach Bewerbungen“ → {"action": "organize", "source": "Dokumente", "group_by": "type"} (more/other files than meant)
- T rc12 „Lösche im Ordner Movies alle Videos über 3 GB“ → {"action": "delete", "source": "Movies", "kinds": ["video"]} (more/other files than meant)
- T rc17 „Leg die Telekom-Rechnungen ab, wo sie hingehören“ → {"action": "organize", "source": "Downloads", "group_by": "type"} (more/other files than meant)
- T rc24 „Verschiebe die Fotos vom Urlaub in Lissabon in einen Ordner Lissabon“ → {"action": "move", "source": "Kamera", "destination": "Lissabon", "kinds": ["image"]} (more/other files than meant)
- T rc26 „Kopiere die Fotos vom Hamburg-Wochenende nach Backup“ → {"action": "move", "source": "Kamera", "destination": "Backup", "kinds": ["image"]} (more/other files than meant)
- T rc27 „Verschiebe die Hochzeitsfotos in einen Ordner Hochzeit“ → {"action": "move", "source": "Kamera", "destination": "Hochzeit", "kinds": ["image"]} (more/other files than meant)
- T rc31 „Verschieb die Instagram-Screenshots in einen Ordner Insta“ → {"action": "move", "source": "Screenshots", "destination": "Insta", "kinds": ["image"]} (more/other files than meant)
- T rh08 „Verschieb die WhatsApp-Screenshots in einen Ordner WhatsApp Screenshots“ → {"action": "move", "source": "Screenshots", "destination": "WhatsApp Screenshots"} (more/other files than meant)
- T rh13 „Lösche im Download alle Dateien, die älter als 2 Jahre sind“ → {"action": "delete", "source": "Downloads"} (more/other files than meant)
- T rg11 „Kopiere die Wallpapers nach Backup“ → {"action": "move", "source": "Kamera", "destination": "Backup", "kinds": ["image"]} (more/other files than meant)
- T+R m022 „die rechnung aus downloads bitte alle in nen extra ordner“ → {"action": "organize", "source": "Downloads", "group_by": "type"} (more/other files than meant)
- T+R h023 „Pack alle Rechnungen aus dem Download-Ordner in einen neuen Ordner Rechnungen“ → {"action": "move", "source": "Download", "destination": "rechnungen", "group_by": "type"} (more/other files than meant)
- T+R rc11 „Verschiebe alle Dateien mit Bewerbung im Namen aus Dokumente nach Bewerbungen“ → {"action": "move", "source": "Dokumente", "destination": "Documents/Bewerbungen", "group_by": "type"} (more/other files than meant)
- T+R rc24 „Verschiebe die Fotos vom Urlaub in Lissabon in einen Ordner Lissabon“ → {"action": "move", "source": "Kamera", "destination": "Lissabon", "kinds": ["image"]} (more/other files than meant)
- T+R rc26 „Kopiere die Fotos vom Hamburg-Wochenende nach Backup“ → {"action": "move", "source": "Kamera", "destination": "Backup", "kinds": ["image"]} (more/other files than meant)

### Neuer Holdout, jeder Fall (A, S, S+R, K, K+R, KS, KS+R, T, T+R)

| Satz | A | S | S+R | K | K+R | KS | KS+R | T | T+R |
|---|---|---|---|---|---|---|---|---|---|
| zeig mir die 5 größten videos | · | ok | ok | ok | ok | ok | ok | ok | ok |
| Where are my screenshots from last week? | · | ok | ok | ok | ok | falsch | ok | falsch | ok |
| welche pdfs hab ich diese woche runtergeladen | · | falsch | ok | ok | ok | falsch | ok | falsch | ok |
| zeig mal alle musik dateien größer als 30 mega byte | · | ok | ok | ok | ok | falsch | falsch | falsch | ok |
| wo sind meine whats app videos | · | · | ok | · | ok | · | ok | · | ok |
| such die datei mit kaufvertrag im namen | · | ok | ok | ok | ok | ok | ok | ok | ok |
| find all files that mention Kessler | · | ok | ok | ok | ok | ok | ok | ok | ok |
| In welchen Dokumenten steht was von Nobilia? | · | ok | ok | ok | ok | ok | ok | ok | ok |
| zeig mir das neueste foto aus der kamera | · | ok | ok | · | · | ok | ok | ok | ok |
| was ist die größte datei im download ordner | · | ok | ok | ok | ok | ok | ok | ok | ok |
| die bilder der letzten drei tage | · ⚠ | ok | ok | ok | ok | falsch | falsch | falsch | falsch |
| zeig alle zip dateien die größer als ein giga sind | · | ok | ok | ok | ok | falsch | ok | falsch | ok |
| liste die pdfs im ordner dokumente | · | ok | ok | ok | ok | ok | ok | ok | ok |
| wo ist die apk von signal | · | ok | ok | ok | ok | falsch | falsch | ok | ok |
| zeig mir was ich heute runtergeladen hab | · | falsch | ok | falsch | ok | falsch | ok | falsch | ok |
| the newest pdf in downloads | · | ok | ok | ok | ok | ok | ok | ok | ok |
| verschieb alle pdfs aus download in den ordner dokumente | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| kopier die bilder aus whatsapp images nach backup | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| lösch im download alle apks die älter als ein jahr sind | ok | ok | ok | ok | ok | falsch ⚠ | ok | falsch ⚠ | ok |
| räum den screen shot ordner auf nach monaten | · | ok | ok | ok | ok | ok | ok | ok | ok |
| Move all mp3s from Download to Music | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| lösche die zip dateien im download die größer als 1 gb sind | ok | ok | ok | ok | ok | falsch ⚠ | ok | falsch ⚠ | ok |
| kopiere alle videos größer 2 gb aus movies nach backup | ok | ok | ok | ok | ok | falsch | ok | falsch ⚠ | falsch |
| sortier den download ordner nach dateityp | ok | ok | ok | ok | ok | falsch | falsch | ok | ok |
| Wie viel Speicher hab ich noch frei? | ok | ok | ok R | ok | ok R | ok | ok R | ok | ok R |
| kann ich die alten whatsapp videos löschen? | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| Was ist in meinem Download Ordner? | · | ok | ok | ok | ok | ok | ok | ok | ok |
| räum download auf aber lösch bloß nichts | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| hey wie gehts dir | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| benenne die datei notizen.txt in ideen.txt um | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| schick die urlaubsfotos an mama | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| brauch ich die setup apks noch? | ok | ok | ok | ok | ok | ok | ok | ok | ok |
| welche dateien hab ich lange nicht mehr angeschaut | ok | falsch | ok R | falsch | ok R | falsch | ok R | falsch | ok R |
| mach das gleiche nochmal mit den videos | ok | falsch | ok R | falsch | ok R | falsch | ok R | falsch | ok R |
| sortier die fotos so wie letztes mal | falsch ⚠ | falsch ⚠ | ok R | falsch ⚠ | ok R | falsch | ok R | falsch ⚠ | ok R |
| pdfs in denen hausrat steht | · | ok | ok | ok | ok | ok | ok | ok | ok |
| ich such die rechnung von mediamarkt weiß aber nicht wie die datei heisst | · | · | falsch | ok | ok | ok | ok | ok | ok |
| zeig mir dokumente über die krankenversicherung | · | ok | ok | · | ok | ok | ok | ok | ok |
| zeig mir die drei neuesten screenshots | · | ok | ok | ok | ok | ok | ok | ok | ok |
| die 10 größten bilder auf dem handy | · ⚠ | ok | ok | ok | ok | ok | ok | ok | ok |
| fotos vom letzten monat aus der kamera | falsch ⚠ | ok | ok | falsch | ok | falsch | ok | falsch | ok |

ok = richtig · „·“ = nichts/Rückfrage · ⚠ = gefährlich · R = ohne Modell (Regel)
