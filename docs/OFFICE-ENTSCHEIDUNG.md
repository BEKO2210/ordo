# Office-Formate in Ordo – Entscheidung

Stand: 16.11.2026, Entscheidung von Belkis.

Ordo bringt **keine eigene Office-Engine** mit. DOCX, XLSX, PPTX, ODT und verwandte Formate öffnet Ordo mit einer
installierten Office-App (Weitergabe wie heute).

**Warum:** Bearbeiten in Spitzenqualität ist auf dem Gerät mit Open Source nicht realistisch. Die einzige ernsthafte
Engine (LibreOfficeKit) macht die App 80–95 MB größer, braucht eine eigene native Build-Kette, und selbst der
Hersteller nennt das Bearbeiten „experimentell“. Apache POI und docx4j sind Datenmodelle ohne Layout und auf Android
nicht belegt. Belege in [MEDIEN-RECHERCHE.md](MEDIEN-RECHERCHE.md), Anhang B, Abschnitt 5.

**Später möglich:** eine einfache Inhaltsvorschau ohne Layouttreue (Text eines DOCX, Raster eines XLSX), wenn sie
Nutzern hilft und klein bleibt.
