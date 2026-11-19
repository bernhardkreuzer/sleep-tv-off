# TV Schlaf-Aus

Schaltet einen Samsung-Fernseher automatisch aus, nachdem die Galaxy Watch das Einschlafen erkannt hat. Die Wartezeit ist frei einstellbar.

## Aufbau

| Modul | Läuft auf | Aufgabe |
|---|---|---|
| `wear` | Galaxy Watch4 oder neuer (Wear OS) | Schlafzustand über die Health Services abfragen, ans Handy melden |
| `mobile` | Samsung-Handy | Timer, Einstellungen, Fernseher ausschalten |

Ablauf: Watch meldet "eingeschlafen um 23:10" → Handy stellt einen Alarm auf 23:10 plus Wartezeit → bei Ablauf prüft das Handy, ob der TV an ist, und schickt den Aus-Befehl. Meldet die Watch vorher "wach", wird der Timer abgebrochen.

Der Fernseher wird lokal im WLAN gesteuert (Port 8002, dieselbe Schnittstelle wie die SmartThings-Fernbedienung). Es braucht weder ein Konto noch Cloud-Zugangsdaten.

## Bauen und installieren

Voraussetzung: Android Studio (aktuelle Version) auf PC oder Mac.

1. Ordner `SleepTvOff` in Android Studio öffnen und den Gradle-Sync abwarten. Bietet Android Studio ein Upgrade des Android Gradle Plugins an, kannst du es annehmen.
2. **Handy:** Entwickleroptionen und USB-Debugging einschalten, per USB verbinden, Run-Konfiguration `mobile` starten.
3. **Watch:** Einstellungen → Info zur Uhr → Softwareinformationen → siebenmal auf "Softwareversion" tippen. Dann in den Entwickleroptionen "ADB-Debugging" und "Drahtloses Debugging" einschalten. In Android Studio: Device Manager → "Pair Devices Using Wi-Fi". Danach Run-Konfiguration `wear` auf der Watch starten.

Wichtig: Beide Apps müssen vom selben Rechner gebaut sein (gleiche Signatur) und haben absichtlich dieselbe `applicationId`. Sonst kommt auf dem Handy nichts von der Watch an.

## Einrichten

1. Dem Fernseher im Router eine feste IP-Adresse geben.
2. Handy-App öffnen, Wartezeit wählen (Schnellwahl 1, 2, 5 oder 10 Minuten, oder Plus/Minus in Minutenschritten bis 240) und die IP-Adresse eintragen.
3. Fernseher einschalten, "Koppeln" tippen und am Fernseher "Zulassen" wählen.
4. "Jetzt aus" tippen. Der Fernseher muss ausgehen.
5. In der Karte "Akku-Optimierung aktiv" auf "Erlauben" tippen, damit der Timer nachts zuverlässig läuft.
6. Watch-App öffnen und die Berechtigung "Körperliche Aktivität" erteilen. Der Status muss "Schlaferkennung aktiv" zeigen.
7. Auf der Watch "Test: eingeschlafen" tippen. Im Protokoll der Handy-App erscheint "Eingeschlafen um … (Test): TV aus um …". Mit "Test: wach" wird der Timer wieder abgebrochen.

## Gut zu wissen

- Die Watch meldet das Einschlafen nicht in Echtzeit, sondern gebündelt, oft mit 10 bis 30 Minuten Verzögerung. Die App rechnet deshalb ab dem tatsächlichen Einschlafzeitpunkt. Ist die Wartezeit beim Eintreffen der Meldung schon vorbei, wird sofort ausgeschaltet.
- Bei sehr kurzen Wartezeiten (1 bis 10 Minuten) ist die Zeit beim Eintreffen der Meldung meist schon vorbei. Der Fernseher geht dann aus, sobald die Watch das Einschlafen meldet.
- Kommt die Meldung mehr als 60 Minuten nach dem geplanten Ausschaltzeitpunkt an (z. B. erst am Morgen), passiert nichts. Der Wert steht in `SleepTimerLogic.MAX_LATE_MIN`.
- Der Aus-Befehl ist bei Samsung ein Umschalter (`KEY_POWER`). Die App fragt deshalb vorher den Status ab und sendet nur, wenn der Fernseher wirklich an ist.
- Das Handy muss nachts im selben WLAN wie der Fernseher sein.
- Nach einem Neustart der Watch meldet sich die App selbst wieder an. Falls nicht: Watch-App einmal öffnen.
- Fragt der Fernseher bei jedem Ausschalten erneut nach Erlaubnis: am TV unter Einstellungen → Verbindung → Externe Geräteverwaltung → Geräteverbindungsmanager die Zugriffsbenachrichtigung auf "Nur beim ersten Mal" stellen (Menüpfad je nach Modell leicht anders).

## Design

Dunkler Grund, Limette als Akzent, Violett für Status. Alle Farben stehen in `res/values/colors.xml` (in beiden Modulen), Formen und Icons in `res/drawable`. Die Statuskarte oben ist limettengrün, solange ein Timer läuft, und violett im Ruhezustand.

## Wichtige Dateien

- `wear/.../SleepPassiveService.kt`: Empfang des Schlafzustands auf der Watch
- `wear/.../PassiveRegistration.kt`: Anmeldung bei den Health Services
- `mobile/.../SleepListenerService.kt`: Empfang auf dem Handy, Start und Abbruch des Timers
- `mobile/.../SleepTimerLogic.kt`: Rechenlogik des Timers (mit Unit-Test)
- `mobile/.../SamsungTv.kt`: Kopplung, Statusabfrage und Aus-Befehl
- `mobile/.../TvOff.kt`: Alarm und Ausschalten im Hintergrund
