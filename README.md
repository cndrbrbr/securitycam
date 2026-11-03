# SecurityCams 0.1.0 (Spigot)

**SecurityCams** ist ein Überwachungskamera-Plugin für Minecraft-Spigot-Server. Erstelle Kamerapositionen, öffne eine übersichtliche Kameraauswahl und beobachte Orte live aus der Ferne. Die aktuelle Version 0.1.0 unterstützt Kameraverwaltung und Liveansichten; echte MP4-Aufzeichnungen und eine Video-Zeitleiste sind für spätere Versionen geplant.

## Voraussetzungen
- Spigot 26.3 (aktuelle Version; gebaut gegen `spigot-api 26.3-R0.1-SNAPSHOT`)
- Java 25
- Maven 3.9+

## Bauen
```sh
mvn clean package
```
Die Plugin-JAR liegt unter `target/securitycams-0.1.0.jar`.
In den `plugins/`-Ordner kopieren und den Server neu starten.

## Befehle
- `/cam create <name>` – Blickposition und Richtung speichern (OP)
- `/cam delete <name>` – Kamera löschen (OP)
- `/cam list` – Übersicht öffnen
- `/cam view <name>` – Kamera im Spectator-Modus ansehen
- `/cam exit` oder Shift – vorige Position und Spielmodus wiederherstellen
- `/cam web` – URL der Web-Ansicht anzeigen (OP)

## Web-Ansicht
Das Plugin rendert die Kamerabilder selbst (Raycasting über die Blöcke, ca. 320×180 bei 4 fps, Nachtsicht-Modus bei Dunkelheit) und stellt sie über einen eingebauten Webserver bereit:

- Übersicht: `http://<server>:8090/?token=<token>` – Admins sehen die URL mit `/cam web`, sie steht auch beim Start im Log
- Live-Stream (MJPEG): `/cam/<name>.mjpg`, Einzelbild: `/cam/<name>.jpg`, Kameraliste: `/api/cameras`
- Der Token wird beim ersten Start zufällig erzeugt und in `plugins/SecurityCams/config.yml` gespeichert; dort lassen sich auch Port, Auflösung, fps und Sichtweite einstellen.
- Gerendert wird nur, solange jemand zusieht. Chunks um beobachtete Kameras bleiben dafür geladen.
- Die Ansicht ist eine vereinfachte Darstellung (Blockfarben, Spieler/Mobs als Quader mit Namen), kein echtes Minecraft-Bild.

## Berechtigungen
- `securitycams.use` (Standard: alle)
- `securitycams.admin` (Standard: OP)

## Einschränkungen
- **Keine Videoaufnahme / MP4 in Version 0.1.0**: Ein separater Minecraft-Client mit OBS/FFmpeg muss das Bild rendern und aufnehmen; Spigot liefert keine Videoframes.
- Temporärer unsichtbarer ArmorStand als Spectator-Ziel.
- Noch keine kamerabezogenen Zugriffsrechte und keine Aufzeichnungs-Timeline.
- Übersicht zeigt maximal 54 Kameras.
- Während der Liveansicht sind freies Umherfliegen und Zuschauer-Teleports gesperrt; Shift beendet die Ansicht.
- Nach einem Serverabsturz wird der vorherige Standort und Spielmodus beim nächsten Login wiederhergestellt (`sessions.yml`).

## Konzept für MP4-Aufnahmen (noch nicht implementiert)
1. Spigot verwaltet Kamera-ID, Position, Blickwinkel und Berechtigungen.
2. Ein separater Minecraft-Client verbindet sich und übernimmt die gewünschte Kameraansicht.
3. OBS/FFmpeg erfasst den gerenderten Client als H.264-MP4.
4. Ein externer Controller koordiniert Aufnahmen und stellt die Dateien über eine authentifizierte API bereit.
5. Ein Web-Dashboard bietet Aufzeichnungsliste und Timeline.

Ein Aufnahmeclient erfordert einen eigenen Minecraft-Account und geeignete Hardware; Einwilligung und Aufbewahrungsfristen sind zu beachten.
