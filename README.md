# SecurityCams 0.1.0 (Spigot)

**SecurityCams** ist ein Überwachungskamera-Plugin für Minecraft-Spigot-Server. Erstelle Kamerapositionen, öffne eine übersichtliche Kameraauswahl und beobachte Orte live aus der Ferne. Die aktuelle Version 0.1.0 unterstützt Kameraverwaltung und Liveansichten; echte MP4-Aufzeichnungen und eine Video-Zeitleiste sind für spätere Versionen geplant.

## Voraussetzungen
- Spigot 1.21.8 (Beispiel-Zielversion; andere Versionen müssen gesondert getestet werden)
- Java 21
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
- `/cam exit` – vorige Position und Spielmodus wiederherstellen

## Berechtigungen
- `securitycams.use` (Standard: alle)
- `securitycams.admin` (Standard: OP)

## Einschränkungen
- **Keine Videoaufnahme / MP4 in Version 0.1.0**: Ein separater Minecraft-Client mit OBS/FFmpeg muss das Bild rendern und aufnehmen; Spigot liefert keine Videoframes.
- Temporärer unsichtbarer ArmorStand als Spectator-Ziel.
- Noch keine kamerabezogenen Zugriffsrechte und keine Aufzeichnungs-Timeline.
- Übersicht zeigt maximal 54 Kameras.
- Bei einem Serverabsturz während der Liveansicht wird der vorherige Spielmodus nicht wiederhergestellt.

## Konzept für MP4-Aufnahmen (noch nicht implementiert)
1. Spigot verwaltet Kamera-ID, Position, Blickwinkel und Berechtigungen.
2. Ein separater Minecraft-Client verbindet sich und übernimmt die gewünschte Kameraansicht.
3. OBS/FFmpeg erfasst den gerenderten Client als H.264-MP4.
4. Ein externer Controller koordiniert Aufnahmen und stellt die Dateien über eine authentifizierte API bereit.
5. Ein Web-Dashboard bietet Aufzeichnungsliste und Timeline.

Ein Aufnahmeclient erfordert einen eigenen Minecraft-Account und geeignete Hardware; Einwilligung und Aufbewahrungsfristen sind zu beachten.
