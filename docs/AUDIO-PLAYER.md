# Ordos Musik-Player – erster Baustein

Stand: 16.11.2026. Dieser Stand beschreibt den Code, nicht einen Gerätetest.

Ordo öffnet `audio/*` aus der normalen Dateiliste im eigenen Player. Die Warteschlange enthält die Audiodateien desselben Ordners in der gerade sichtbaren Reihenfolge und beginnt beim angetippten Titel; das gilt auch in einem geöffneten Archiv, dessen Einträge direkt gelesen werden. Verknüpfungen öffnen eine Warteschlange mit diesem einen Titel. Dateien im Tresor bleiben absichtlich beim bisherigen Öffnen durch eine andere App: eine Hintergrundwiedergabe dürfte die Sperre des Tresors nicht überleben.

`AudioPlaybackService` ist ein Media3-`MediaSessionService` mit einem ExoPlayer. Er verwaltet Audiofokus, reagiert auf abgezogene Kopfhörer, hält bei lokalen Dateien die CPU wach und nutzt Media3s Standard-Medienbenachrichtigung. Die Session nimmt Medienknöpfe entgegen. Pfade, Index und Position liegen klein in SharedPreferences und werden über `onPlaybackResumption` wiederhergestellt.

`PathDataSource` liest Ordos `java8.nio.file.Path` über `SeekableByteChannel`. Damit gilt dieselbe Quelle für lokalen Speicher, SD-Karte, Archive, SMB, SFTP und WebDAV; Bereichslesen und Abbruch schließen den Kanal. `content://` bleibt beim `ContentDataSource` von Media3. In diesem Baustein gibt es weder FFmpeg noch eine andere native Decoder-Erweiterung.

Die Player-Ansicht verbindet sich als `MediaController` mit dem Dienst. Sie besitzt den Player nicht, daher läuft Musik nach dem Schließen weiter. Handy und Fenster ab 600 dp haben getrennte XML-Layouts; Farben und Schriftrollen kommen aus dem App-Theme. Cover stammen aus eingebetteten Bildern über Ordos vorhandenen Coil-Fetcher, andernfalls erscheint das ruhige Audio-Platzhaltersymbol. Die Ansicht bietet Titel, Interpret, Album, verstrichene und verbleibende Zeit, Suchen, Zurück, Play/Pause, Weiter, Zufall und Wiederholen.

Noch nicht geprüft: Kompilierung und Unit-Tests (in dieser Arbeitsumgebung gibt es kein Gradle), Wiedergabe und Wiederaufnahme auf Pixel und Galaxy Tab, Benachrichtigung und Medienknöpfe, alle Speicheranbieter, große Warteschlangen, Audiofokus und das Verhalten bei abgezogenen Kopfhörern. Vor einer Veröffentlichung muss `e2e/audio-player.sh` auf den Testgeräten laufen und das für `mediaPlayback` verlangte Play-Console-Video aufgenommen werden.
