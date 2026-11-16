| Variante | richtig | falsch verstanden | abgelehnt | gefährlich |
|---|---|---|---|---|
| A | 10/40 | 5 | 20 | 5 |
| S+R | 21/40 | 17 | 0 | 2 |
| K+R | 22/40 | 16 | 0 | 2 |
| S | 17/40 | 21 | 0 | 2 |
| K | 21/40 | 17 | 0 | 2 |

K+R je Fähigkeit:

- cannot: 3/5
- content: 3/4
- copy: 1/2
- delete: 1/2
- dictation: 4/7
- english: 2/5
- kind: 1/3
- move: 1/3
- negation: 1/3
- organize: 0/1
- question: 4/4
- recency: 3/9
- show: 9/20
- size: 1/2
- sort: 3/4
- trap: 5/5

K+R alle Fehler:

- b001 [misread] Die Fotos von vorgestern bitte -> {'action': 'show', 'kinds': ['image'], 'newer_than': 2, '_today': False} []
- b003 [misread] Zeig mir die Handyvideos vom Sommer -> {'action': 'show', 'kinds': ['video'], 'content_contains': 'Sommer'} []
- b004 [misread] Screenshots aus 2024 -> {'action': 'show', 'source': 'Screenshots', 'newer_than': 1, 'age_unit': 'years'} []
- b005 [misread] welche pdfs sind seit weihnachten im down load dazu gekommen -> {'action': 'show', 'source': 'Downloads', 'extensions': ['pdf'], 'newer_than': 2, 'age_unit': 'weeks'} []
- b007 [misread] sprach nachrichten von whats app die über ein mb groß sind -> {'action': 'show', 'source': 'Android/media/com.whatsapp/WhatsApp', 'kinds': ['image'], 'larger_than': 1} []
- b012 [misread] Such mal den Lieferschein von der Küche -> {'action': 'show', 'source': 'Küche', 'content_contains': 'Lieferschein'} []
- b014 [misread] zeig mir die letzten fünf sprachnachrichten -> {'action': 'show', 'kinds': ['audio'], 'extensions': ['3gp', '4ga', '3g2', 'wav', 'mp3', 'm4a'], 'sort': 'newest', 'limit': 5} []
- b016 [misread] Zeig mir alles im Download außer den Zip Dateien -> {'action': 'show', 'source': 'Download', 'extensions': ['zip']} []
- b017 [misread] Was liegt im Kamera Ordner außer Fotos -> {'action': 'show', 'source': 'Kamera', 'kinds': ['image'], 'content_contains': 'außer'} []
- b018 [misread] Show me the PDFs I downloaded this month -> {'action': 'show', 'source': 'Downloads', 'extensions': ['pdf'], 'newer_than': 1, 'age_unit': 'months'} []
- b019 [misread] find my whatsapp voice messages -> {'action': 'show', 'source': 'WhatsApp Images', 'kinds': ['audio']} []
- b020 [misread] copy my wallpapers to the backup folder -> {'action': 'copy', 'source': '.', 'destination': 'backup', 'kinds': ['image']} []
- b023 [dangerous] Schieb die Kontoauszüge aus dem Download in den Ordner Bank -> {'action': 'move', 'source': 'Download', 'destination': 'Bank'} ['more/other files than meant']
- b024 [misread] Pack die drei Bewerbungen aus Dokumente in den Ordner Bewerbungen -> {'action': 'move', 'source': 'Documents/Bewerbungen', 'destination': 'Bewerbungen', 'content_contains': 'Bewerbung', 'limit': 3} []
- b025 [dangerous] lösch die podcast folgen im download die älter als ein jahr sind -> {'action': 'delete', 'source': 'Download', 'older_than': 1, 'age_unit': 'years', '_today': False} ['more/other files than meant']
- b026 [misread] Ordne die WhatsApp Bilder nach Monaten -> {'action': 'organize', 'source': 'Android/media/com.whatsapp/WhatsApp', 'group_by': 'month'} []
- b031 [misread] Pack die Lissabon Fotos in eine Zip Datei -> {'action': 'organize', 'source': 'Kamera', 'extensions': ['zip'], 'name_contains': 'Lissabon'} []
- b032 [misread] Welche Fotos sind verwackelt? Die kannst du gleich löschen. -> {'action': 'show', 'source': 'Kamera', 'kinds': ['image'], 'content_contains': 'verwackelt'} []
