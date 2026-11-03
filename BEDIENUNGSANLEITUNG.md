# SecurityCams – Bedienungsanleitung für Spieler und Admins

**Version:** 0.2.0 (Spigot 26.3)  
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

Alternativ drückst du **Shift** (Schleichen). Du kehrst zu deinem vorherigen Standort und Spielmodus zurück. Freies Umherfliegen und Teleports zu anderen Spielern sind während der Kameraansicht nicht möglich.

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

### Web-Ansicht im Browser

Alle Kameras lassen sich live im Browser ansehen. Admins erhalten die Adresse mit:

```text
/cam web
```

Die Adresse hat die Form `http://<server>:8090/?token=<schlüssel>` und steht auch beim Serverstart im Log. Gib sie nur an Personen weiter, die die Kameras sehen dürfen – wer den Schlüssel hat, sieht alle Kameras.

- Jede Kamera erscheint als Kachel mit Status **LIVE**; ein Klick vergrößert sie.
- Neue oder gelöschte Kameras erscheinen bzw. verschwinden nach spätestens 10 Sekunden von selbst.
- Nach einem Serverneustart verbindet sich die Seite automatisch neu.
- Das Bild ist eine vereinfachte Darstellung: Blöcke in ihren Kartenfarben, Spieler und Mobs als Quader, Spieler mit Namen. Nachts schaltet die Kamera auf Nachtsicht (IR).
- Port, Auflösung, Bildrate und Sichtweite stellt der Serveradmin in `plugins/SecurityCams/config.yml` ein; `web.enabled: false` schaltet die Web-Ansicht ab.

## Befehlsübersicht

| Befehl | Zweck | Berechtigung |
| --- | --- | --- |
| `/cam list` | Kameraübersicht öffnen | `securitycams.use` |
| `/cam view <name>` | Kamera live ansehen | `securitycams.use` |
| `/cam exit` | Ansicht verlassen (auch per Shift) | – |
| `/cam create <name>` | Kamera speichern | `securitycams.admin` |
| `/cam delete <name>` | Kamera löschen | `securitycams.admin` |
| `/cam web` | Adresse der Web-Ansicht anzeigen | `securitycams.admin` |

**Standardmäßig** darf jeder Spieler die vorhandenen Kameras ansehen (`securitycams.use`). Kamera-Erstellung und Löschen sind nur für OPs erlaubt. Ein Serveradmin kann diese Berechtigungen über ein Permissions-Plugin ändern.

## Häufige Fragen und Probleme

**„Keine Berechtigung.“**  
Frage einen Admin nach der passenden Berechtigung. Für das Ansehen ist `securitycams.use` erforderlich.

**„Kamera nicht gefunden.“**  
Prüfe den Namen oder lass dir mit `/cam list` die gespeicherten Kameras zeigen. Die Kamera-Welt muss geladen sein.

**Die Kamera zeigt keine beweglichen Überwachungsbilder auf einem Block.**  
Das Plugin zeigt eine Kamera-Perspektive im Zuschauer-Modus. Es besitzt noch keinen Überwachungsmonitor als Block und keine frei drehbare Kamera.

**Kann ich alte Aufnahmen ansehen?**  
Nein. Die Web-Ansicht ist eine reine Liveansicht: Bilder werden nur erzeugt, solange jemand zusieht, und nirgends gespeichert. Eine Aufzeichnung ist für eine spätere Version denkbar.

**Die Web-Ansicht zeigt „Token ungültig“ oder „Zugriff verweigert“.**  
Öffne die vollständige Adresse aus `/cam web` inklusive `?token=…`.

**Was passiert nach einem Serverneustart?**  
Gespeicherte Kamerapositionen werden in `plugins/SecurityCams/cameras.yml` gespeichert und beim Start geladen. Stürzt der Server während einer Kameraansicht ab, wirst du beim nächsten Login automatisch an deinen vorherigen Standort zurückgesetzt und erhältst deinen vorherigen Spielmodus zurück.

## Hinweise

- Diese Anleitung bezieht sich auf den aktuellen Entwicklungsstand **0.2.0**.
- Getestet auf einem Spigot-26.3-Testserver, noch nicht auf einem produktiven Server.
- Für Videomitschnitte sollten Serverbetreiber die Mitspieler informieren und geeignete Datenschutz- und Aufbewahrungsregeln festlegen.
