# SecurityCams – Bedienungsanleitung für Spieler und Admins

**Version:** 0.1.0 (Spigot-Prototyp)  
**Sprache:** Deutsch

## Was ist SecurityCams?

SecurityCams ist ein Minecraft-Spigot-Plugin, mit dem du gespeicherte Kamerapositionen aus der Ferne ansehen kannst. Die Kameraübersicht ist ein Inventarmenü. Es handelt sich derzeit um **Liveansichten**, nicht um echte Videoaufzeichnungen.

## Für Spieler: Kameras ansehen

### 1. Kameraübersicht öffnen

Gib im Minecraft-Chat ein:

```text
/cam list
```

Es öffnet sich die Kameraübersicht. Die Einträge sind als Enderaugen dargestellt. Klicke eine Kamera an, um ihre Perspektive zu öffnen. Das Menü zeigt derzeit maximal 54 Kameras; andere Kameras lassen sich über ihren Namen aufrufen.

### 2. Eine Kamera direkt öffnen

Wenn du den Kameranamen kennst, gib ein:

```text
/cam view eingang
```

Ersetze `eingang` durch den gewünschten Kameranamen. Du gelangst in die Kameraansicht. Währenddessen verwendet das Plugin vorübergehend den Zuschauer-Modus (Spectator).

### 3. Kameraansicht verlassen

Gib ein:

```text
/cam exit
```

Du kehrst zu deinem vorherigen Standort und Spielmodus zurück. Benutze zum Verlassen möglichst immer diesen Befehl.

## Für Admins: Kameras einrichten

Du benötigst Operator-Rechte (OP) oder die Berechtigung `securitycams.admin`.

### Neue Kamera erstellen

1. Gehe an die Position, an der die Kamera stehen soll.
2. Schaue in die gewünschte Aufnahmerichtung.
3. Gib ein:

```text
/cam create eingang
```

Die aktuelle Blickposition und Blickrichtung werden unter dem Namen `eingang` gespeichert. Du kannst die Kamera anschließend über `/cam list` oder `/cam view eingang` testen.

**Namensregeln:** 1–32 Zeichen, nur Kleinbuchstaben a–z, Ziffern 0–9, Bindestrich und Unterstrich. Namen müssen eindeutig sein.

### Kamera löschen

```text
/cam delete eingang
```

Dadurch wird der gespeicherte Kamerastandort gelöscht. Es erfolgt derzeit keine zusätzliche Bestätigung.

## Befehlsübersicht

| Befehl | Zweck | Berechtigung |
| --- | --- | --- |
| `/cam list` | Kameraübersicht öffnen | `securitycams.use` |
| `/cam view <name>` | Kamera live ansehen | `securitycams.use` |
| `/cam exit` | Ansicht verlassen | `securitycams.use` |
| `/cam create <name>` | Kamera speichern | `securitycams.admin` |
| `/cam delete <name>` | Kamera löschen | `securitycams.admin` |

**Standardmäßig** darf jeder Spieler die vorhandenen Kameras ansehen (`securitycams.use`). Kamera-Erstellung und Löschen sind nur für OPs erlaubt. Ein Serveradmin kann diese Berechtigungen über ein Permissions-Plugin ändern.

## Häufige Fragen und Probleme

**„Keine Berechtigung.“**  
Frage einen Admin nach der passenden Berechtigung. Für das Ansehen ist `securitycams.use` erforderlich.

**„Kamera nicht gefunden.“**  
Prüfe den Namen oder lass dir mit `/cam list` die gespeicherten Kameras zeigen. Die Kamera-Welt muss geladen sein.

**Die Kamera zeigt keine beweglichen Überwachungsbilder auf einem Block.**  
Das Plugin zeigt eine Kamera-Perspektive im Zuschauer-Modus. Es besitzt noch keinen Überwachungsmonitor als Block und keine frei drehbare Kamera.

**Kann ich alte Aufnahmen ansehen?**  
Noch nicht. MP4-Videoaufzeichnungen, Wiedergabe und Aufzeichnungsarchiv sind für eine spätere Version geplant. Ein reines Spigot-Plugin kann nicht selbst das Bild der Minecraft-Spielwelt als MP4 rendern; dafür ist ein zusätzlicher Aufnahme-Client erforderlich.

**Was passiert nach einem Serverneustart?**  
Gespeicherte Kamerapositionen werden in `plugins/SecurityCams/cameras.yml` gespeichert und beim Start geladen. Nach einem Serverabsturz während der Kameraansicht ist die automatische Wiederherstellung des vorherigen Spielmodus derzeit nicht garantiert.

## Hinweise

- Diese Anleitung bezieht sich auf den aktuellen Entwicklungsstand **0.1.0**.
- Das Plugin ist noch nicht auf einem produktiven Spigot-Server verifiziert.
- Für Videomitschnitte sollten Serverbetreiber die Mitspieler informieren und geeignete Datenschutz- und Aufbewahrungsregeln festlegen.
