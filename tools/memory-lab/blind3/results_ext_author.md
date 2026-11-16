| Lauf | Regeln | Fremdsatz | gefährlich | Rückfrage/Abl. |
|---|---|---|---|---|
| blind3 | Modell allein | 44/60 | 1 | 12 |
| blind3 | + Netz a-d | 43/60 | 0 | 13 |

### blind3

System-Prompt 75 Token, Befehl Median 19.0, Ausgabe Median 16.0 / Max 44 Token. Tab geschätzt: warm 2.6 s, kalt mit gespeichertem Zustand 7.8 s, kalt ohne 10.7 s.
Interne Validierung: –

Fehler + Netz (alle Sätze): correct 43, q_or_refuse 11, misread 6

- b3-05 [q_or_refuse/none] Die Garantie vom Geschirrspüler bitte -> roh {"action": "none"} got 0 want [1] 
- b3-10 [misread/plan] screenshots aus whatsapp vom letzten monat -> roh {"action": "show", "source": "WhatsApp Screenshots", "time": "letzten Monat"} got 0 want [8] 
- b3-11 [misread/plan] Alle Rechnungen außer Amazon -> roh {"action": "show", "name_contains": "Rechnung", "exclude": ["Amazon"]} got 22 want [26, 28] 
- b3-19 [q_or_refuse/ask/app:] zeig mir alles von der Küche -> roh {"action": "show", "source": "Küche"} got 0 want [5, 3] 
- b3-27 [q_or_refuse/ask/app:] Instagram Screenshots von Anfang des Jahres -> roh {"action": "show", "source": "Instagram Screenshots", "time": "Anfang des Jahres"} got 0 want [4, 7, 9] 
- b3-29 [q_or_refuse/ask/uncovered] Verschieb die Telekom Rechnungen aus Downloads in den Ordner Rechnungen -> roh {"action": "move", "source": "Downloads", "destination": "Rechnungen", "name_contains": "Telekom"} got 0 want [2] 
- b3-30 [misread/plan] lösch die doppelte amazon rechnung im download -> roh {"action": "delete", "source": "Download", "name_contains": "Amazon Rechnung"} got 0 want [1] 
- b3-31 [q_or_refuse/ask/no_dest] Kopier die Bewerbungsfotos zu den Bewerbungen -> roh {"action": "copy", "source": "Bewerbungen", "destination": "Bewerbungen", "kinds": ["image"]} got 0 want [2] 
- b3-32 [q_or_refuse/ask/app:] die ganzen scans vom autokauf im september bitte in einen ordner auto -> roh {"action": "move", "source": "Scans", "destination": "Auto", "name_contains": "Autokauf", "time": "im September"} got 0 want [6] 
- b3-33 [q_or_refuse/ask/unknown_folder] Lösche die APKs, die älter als einen Monat sind -> roh {"action": "delete", "source": "APK", "time": "älter als 1 Monate"} got 0 want [42] 
- b3-35 [q_or_refuse/ask/unknown_folder] Kopier alles was mit Kessler Logistik zu tun hat nach Documents/Arbeit -> roh {"action": "copy", "source": "Kessler Logistik", "destination": "Documents/Arbeit", "name_contains": "Kessler Logistik"} got 0 want [6] 
- b3-37 [q_or_refuse/ask/unknown_folder] verschieb die lohnsteuer und die spendenquittung und riester von 2025 nach steuer 2025 -> roh {"action": "move", "source": "Steuer", "destination": "Steuer 2025", "name_contains": "Lohnsteuer", "time": "2025", "sort": "newest", "limit": 1} got 0 want [3] 
- b3-39 [q_or_refuse/refuse/memory] Mach die Kontoauszüge aus dem Download zu den anderen in Bank -> roh {"action": "move", "source": "Download", "destination": "Bank", "name_contains": "Kontoauszug"} got 0 want [2] 
- b3-44 [misread/plan] Soll ich die alten WhatsApp Videos löschen oder lieber behalten? -> roh {"action": "show", "source": "WhatsApp Video", "time": "alt"} got 150 want [] 
- b3-53 [q_or_refuse/refuse/memory] verschieb das in den anderen ordner -> roh {"action": "ask"} got 0 want [] 
- b3-57 [misread/plan] find my boarding pass for lisbon -> roh {"action": "show", "name_contains": "Boarding Pass", "content_contains": "Lisbon"} got 0 want [1, 1, 2] 
- b3-59 [misread/plan] delete the png files in my downloads except the logo -> roh {"action": "delete", "source": "Downloads", "extensions": ["png"], "name_contains": "Logo"} got 0 want [2] 

