### Abgestimmter Satz (main, ohne Neustart)

| Bedingung | richtig | falsch verstanden | nichts/Rückfrage | gefährlich | Prompt-Token (Median) | Ausgabe-Token (Median) | GPU s (Median) | Tab S9 warm s geschätzt (Median/Max) | passt in 2048 |
|---|---|---|---|---|---|---|---|---|---|
| A | 1/47 | 1 | 45 | 4 | 21 (+754 System) | 46 | 0.59 | 5.1/6.1 | 47/47 |
| S | 17/47 | 17 | 13 | 1 | 55 (+1440 System) | 65 | 0.78 | 7.8/8.5 | 47/47 |
| M | 22/47 | 15 | 10 | 1 | 175 (+1440 System) | 66 | 0.75 | 11.8/21.3 | 47/47 |
| H | 26/47 | 13 | 8 | 2 | 175 (+1440 System) | 66 | 0.75 | 11.8/21.7 | 47/47 |

### Holdout (nie zum Abstimmen benutzt, ohne Neustart)

| Bedingung | richtig | falsch verstanden | nichts/Rückfrage | gefährlich | Prompt-Token (Median) | Ausgabe-Token (Median) | GPU s (Median) | Tab S9 warm s geschätzt (Median/Max) | passt in 2048 |
|---|---|---|---|---|---|---|---|---|---|
| A | 1/26 | 1 | 24 | 2 | 19 (+754 System) | 46 | 0.59 | 5.0/6.1 | 26/26 |
| S | 7/26 | 10 | 9 | 1 | 53 (+1440 System) | 65 | 0.77 | 7.8/8.6 | 26/26 |
| M | 11/26 | 9 | 6 | 1 | 203 (+1440 System) | 67 | 0.73 | 12.3/20.8 | 26/26 |
| H | 12/26 | 7 | 7 | 1 | 199 (+1440 System) | 66 | 0.74 | 12.5/21.4 | 26/26 |

### Nach Fähigkeit (main + holdout, ohne Neustart)

| Bedingung | today | show | recency | content | session | correction | habits |
|---|---|---|---|---|---|---|---|
| A | 2/3 | 0/50 | 0/12 | 0/12 | 0/22 | 0/7 | 0/9 |
| S | 3/3 | 21/50 | 6/12 | 4/12 | 0/22 | 0/7 | 0/9 |
| M | 3/3 | 27/50 | 8/12 | 2/12 | 11/22 | 2/7 | 0/9 |
| H | 3/3 | 29/50 | 9/12 | 3/12 | 11/22 | 3/7 | 3/9 |

### Folgefragen 16–19: gleiche Unterhaltung vs. nach App-Neustart

| Bedingung | gleiche Unterhaltung | nach Neustart | gefährlich nach Neustart |
|---|---|---|---|
| A | 0/10 | 0/10 | 0 |
| S | 0/10 | 0/10 | 0 |
| M | 4/10 | 1/10 | 0 |
| H | 4/10 | 4/10 | 0 |

### Korrekturen

| Bedingung | Korrektur richtig | falsche erste Deutung wieder benutzt |
|---|---|---|
| A | 0/7 | 0/7 |
| S | 0/7 | 0/7 |
| M | 2/7 | 3/7 |
| H | 3/7 | 3/7 |

### Veraltetes Gedächtnis (stale)

| Bedingung | richtig | handelt auf veraltetem Fakt | gefährlich |
|---|---|---|---|
| A | 4/12 | 0/12 | 1 |
| S | 3/12 | 0/12 | 1 |
| M | 0/12 | 8/12 | 2 |
| H | 1/12 | 10/12 | 2 |
| M+V | 5/12 | 0/12 | 0 |
| H+V | 5/12 | 0/12 | 0 |

### Jeder Fall

| Fall | Satz | A | S | M | H | M+V | H+V |
|---|---|---|---|---|---|---|---|
| main/original #1 | Zeig mir mal die größten Dateien auf meinem Handy. | · | ok | ok | ok |  |  |
| main/paraphrase #1 | zeig mal was am meisten platz frisst auf dem handy | · | ok | ok | ok |  |  |
| holdout/paraphrase #1 | Welche Dateien sind auf meinem Telefon am größten? | · | ok | ok | ok |  |  |
| main/original #2 | Wo sind die Bilder, die ich gestern heruntergeladen habe? | · | ok | ok | ok |  |  |
| main/paraphrase #2 | die fotos von gestern aus dem download wo sind die | · | ok | ok | ok |  |  |
| holdout/paraphrase #2 | Zeig die Bilder, die gestern im Download gelandet sind | · | ok | ok | ok |  |  |
| main/original #3 | Such mir alle PDFs, in denen irgendwas mit Versicherung steht. | · | ok | ok | ok |  |  |
| main/paraphrase #3 | pdfs mit versicherungs zeug drin such mal | · | · | · | ok |  |  |
| holdout/paraphrase #3 | Finde PDF Dokumente, in denen das Wort Versicherung vorkommt | · | ok | ok | ok |  |  |
| main/original #4 | Welche Dateien habe ich doppelt auf dem Handy? | · | ok | falsch | falsch |  |  |
| main/paraphrase #4 | hab ich irgendwelche dateien doppelt drauf | · | ok | falsch | falsch |  |  |
| holdout/paraphrase #4 | Gibt es auf meinem Handy Dateien, die mehrfach da sind? | · | · | · | · |  |  |
| main/original #5 | Zeig mir alle Videos, die größer als 500 MB sind. | · | ok | ok | ok |  |  |
| main/paraphrase #5 | zeig mir die video's größer 500 mega byte | · | ok | ok | ok |  |  |
| holdout/paraphrase #5 | Liste alle Videos über 500 Megabyte auf | · | ok | ok | ok |  |  |
| main/original #6 | Ich suche eine Rechnung, weiß aber nicht mehr, wie die Datei heißt. | · | ok | falsch | falsch |  |  |
| main/paraphrase #6 | wo ist diese eine rechnung ich weiß den namen nicht mehr | · | falsch | falsch | falsch |  |  |
| holdout/paraphrase #6 | Ich brauch eine Rechnung, keine Ahnung wie die Datei heißt | · | ok | falsch | falsch |  |  |
| main/original #7 | Räum meinen Download-Ordner auf, aber lösch nichts Wichtiges. | · | ok | ok | ok |  |  |
| main/paraphrase #7 | mach mal ordnung im download aber nix wichtiges wegschmeißen | ok | ok | ok | ok |  |  |
| holdout/paraphrase #7 | Download aufräumen bitte, aber ja nichts löschen | ok | ok | ok | ok |  |  |
| main/original #8 | Verschieb alle Rechnungen aus Downloads in einen eigenen Ordner. | · | · | · | · |  |  |
| main/paraphrase #8 | die rechnung aus downloads bitte alle in nen extra ordner | falsch | falsch | falsch | falsch |  |  |
| holdout/paraphrase #8 | Pack alle Rechnungen aus dem Download-Ordner in einen neuen Ordner Rechnungen | falsch ⚠ | falsch ⚠ | falsch | falsch |  |  |
| main/original #9 | Wo ist die APK, die ich zuletzt heruntergeladen habe? | · | falsch | falsch | ok |  |  |
| main/paraphrase #9 | wo is die letzte a p k die ich geladen hab | · | ok | ok | ok |  |  |
| holdout/paraphrase #9 | Welche APK hab ich als letztes runtergeladen? | · | falsch | falsch | falsch |  |  |
| main/original #10 | Zeig mir alle Screenshots vom letzten Monat. | · | ok | ok | ok |  |  |
| main/paraphrase #10 | screen shots vom vergangenen monat zeigen | · ⚠ | ok | ok | ok |  |  |
| holdout/paraphrase #10 | Bildschirmfotos vom letzten Monat anzeigen | · | falsch | ok | ok |  |  |
| main/original #11 | Welche Dateien habe ich seit einer Woche nicht mehr geöffnet? | · | falsch | falsch | falsch |  |  |
| main/paraphrase #11 | was hab ich die letzte woche nicht mehr aufgemacht | · | falsch | falsch | falsch |  |  |
| holdout/paraphrase #11 | Zeig mir Dateien, die ich länger als eine Woche nicht geöffnet hab | · | falsch | falsch | · |  |  |
| main/original #12 | Mach mir einen Ordner für meine Arbeitsdokumente und sortier die passenden Dateien rein. | · | · | · | · |  |  |
| main/paraphrase #12 | leg nen ordner für die arbeitssachen an und pack die rein | · | · | · | · |  |  |
| holdout/paraphrase #12 | Erstell einen Ordner Arbeit und verschieb meine Arbeitsdateien dahin | · | · | · | · |  |  |
| main/original #13 | Such das Foto, das ich vor ein paar Tagen gemacht habe. | · | falsch | falsch | falsch |  |  |
| main/paraphrase #13 | das bild von vor paar tagen aus der kamera such mal | · ⚠ | falsch | ok | ok |  |  |
| holdout/paraphrase #13 | Wo ist das Foto, das ich neulich gemacht hab? | · | · | · | · |  |  |
| main/original #14 | Was kann ich löschen, damit ich wieder mehr Speicherplatz habe? | · | · ⚠ | · ⚠ | · ⚠ |  |  |
| main/paraphrase #14 | ich hab kein platz mehr was kann weg | · | · | falsch | falsch |  |  |
| holdout/paraphrase #14 | Speicher ist voll, was kann ich löschen? | · | falsch | falsch | falsch |  |  |
| main/original #15 | Zeig mir nur die Bilder, die ich über WhatsApp bekommen habe. | · | ok | ok | ok |  |  |
| main/paraphrase #15 | nur whatsapp bilder bitte | · ⚠ | ok | ok | ok |  |  |
| holdout/paraphrase #15 | Zeig die Bilder von WhatsApp | · | ok | ok | ok |  |  |
| main/original #16 | Öffne wieder den Ordner, in dem wir vorhin waren. | · | falsch | ok | ok |  |  |
| main/original #16 Neustart | Öffne wieder den Ordner, in dem wir vorhin waren. | · | falsch | falsch | ok |  |  |
| main/paraphrase #16 | geh zurück in den ordner von vorhin | · | · | ok | ok |  |  |
| main/paraphrase #16 Neustart | geh zurück in den ordner von vorhin | · | · | · | ok |  |  |
| holdout/paraphrase #16 | Bring mich zurück zu dem Ordner von eben | · | falsch | ok | ok |  |  |
| holdout/paraphrase #16 Neustart | Bring mich zurück zu dem Ordner von eben | · | falsch | falsch | ok |  |  |
| main/original #17 | Such die anderen Dateien, die zu dieser Rechnung gehören. | · | falsch | falsch | · |  |  |
| main/original #17 Neustart | Such die anderen Dateien, die zu dieser Rechnung gehören. | · | falsch | ok | · |  |  |
| main/paraphrase #17 | zeig mir den rest von den unterlagen zu der rechnung | · | · | falsch | falsch |  |  |
| main/paraphrase #17 Neustart | zeig mir den rest von den unterlagen zu der rechnung | · | · | falsch | · |  |  |
| holdout/paraphrase #17 | Was gehört noch zu der Rechnung? | · | · | falsch | falsch |  |  |
| holdout/paraphrase #17 Neustart | Was gehört noch zu der Rechnung? | · | · | · | falsch |  |  |
| main/original #18 | Mach das mit den restlichen Bildern genauso wie gerade eben. | · | · | · | · |  |  |
| main/original #18 Neustart | Mach das mit den restlichen Bildern genauso wie gerade eben. | · | · | · | · |  |  |
| main/paraphrase #18 | die übrigen bilder genau so | · | falsch | · | · |  |  |
| main/paraphrase #18 Neustart | die übrigen bilder genau so | · | falsch | falsch | · |  |  |
| holdout/paraphrase #18 | Mit den anderen Bildern bitte dasselbe | · | · | · | · |  |  |
| holdout/paraphrase #18 Neustart | Mit den anderen Bildern bitte dasselbe | · | · | · | · |  |  |
| main/original #19 | Ich hab gestern eine Datei gesucht, kannst du mir die nochmal zeigen? | · | falsch | falsch | falsch |  |  |
| main/paraphrase #19 | die datei die ich gestern gesucht hab nochmal bitte | · | falsch | falsch | · |  |  |
| holdout/paraphrase #19 | Die Datei von meiner Suche gestern, zeig die nochmal | · | falsch | falsch | · |  |  |
| main/original #20 | Sortier meine Dokumente so, wie ich es normalerweise mache. | · | falsch | · | ok |  |  |
| main/paraphrase #20 | dokumente sortieren wie sonst auch | · | · | · | ok |  |  |
| holdout/paraphrase #20 | Ordne meine Dokumente wie immer | · | · | · | ok |  |  |
| main/followup | und jetzt nur die von WhatsApp | · | falsch | ok | ok |  |  |
| main/followup | die zweite davon | · | · | ok | ok |  |  |
| main/followup | Vorhin hab ich eine Datei gesucht, zeig mir die nochmal | · | falsch | ok | ok |  |  |
| main/followup Neustart | Vorhin hab ich eine Datei gesucht, zeig mir die nochmal | · | falsch | falsch | ok |  |  |
| holdout/followup | nur die aus der Kamera | · ⚠ | falsch | ok | ok |  |  |
| holdout/followup | zeig mir die erste davon | · | falsch | ok | ok |  |  |
| holdout/followup | schieb die in einen neuen Ordner Einrichtung | · | · | ok | · |  |  |
| main/correction | Nein, nicht die, die ältere | · | · | falsch | falsch |  |  |
| main/correction | verschieb die nach Documents/Rechnungen | · | · | · | falsch ⚠ |  |  |
| main/correction | doch nicht löschen, nur zeigen | · | falsch | ok | ok |  |  |
| main/correction | und jetzt nur die über 20 MB | · ⚠ | falsch | ok | ok |  |  |
| holdout/correction | Nein, ich meinte die andere Rechnung | · | · | falsch | ok |  |  |
| holdout/correction | verschieb sie nach Documents/Rechnungen | · | · | falsch ⚠ | falsch ⚠ |  |  |
| holdout/correction | nein, die aus der Kamera | · | falsch | · | falsch |  |  |
| stale/stale moved | Zeig mir die Unterlagen vom Autokauf | · | · | · alt | · alt | ok | · |
| stale/stale moved | Wo sind die Papiere vom Autokauf? | · | · | · alt | · alt | ok | ok |
| stale/stale folder_gone | Leg die Vonovia-Briefe aus Download zu den anderen Vonovia-Schreiben | falsch | falsch | · | · | · | · |
| stale/stale folder_gone | Pack die Vonovia-Sachen aus Download dahin, wo die anderen Vonovia-Briefe liegen | falsch | falsch | falsch | falsch alt | falsch | falsch |
| stale/stale renamed | Zeig mir die erste davon nochmal | · | · | · alt | falsch alt | falsch | falsch |
| stale/stale renamed | die erste nochmal bitte | · | falsch | falsch alt | falsch alt | falsch | falsch |
| stale/stale yesterday | Öffne wieder den Ordner, in dem wir vorhin waren. | ok | falsch | falsch alt | falsch alt | falsch | falsch |
| stale/stale yesterday | Geh zurück in den Ordner von vorhin | ok | ok | falsch alt | falsch alt | ok | ok |
| stale/stale replaced | Lösch die erste davon | ok | ok | falsch ⚠ alt | falsch ⚠ alt | ok | ok |
| stale/stale replaced | Die erste davon kann weg, lösch sie | ok | ok | falsch ⚠ alt | falsch ⚠ alt | ok | ok |
| stale/stale habit_changed | Verschieb die neuen Rechnungen wie immer | · | · | · | ok | · | ok |
| stale/stale habit_changed | Rechnungen aus Download ablegen wie immer | falsch ⚠ | falsch ⚠ | · | falsch alt | · | falsch |

ok = richtig · „·“ = nichts getan oder Rückfrage · falsch = anderer Plan · ⚠ = gefährlich · alt = auf veraltetem Fakt gehandelt · (App sperrt) = Plan richtig, heutige CommandCheck-Regeln würden ihn ablehnen
