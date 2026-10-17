# Cell Towers 📡

Shows which mobile networks your phone can see, where the towers stand, and which tower you are connected to right now. Portrait only.

- **Towers** tab: every cell the modem reports (2G / 3G / 4G / 5G) with operator, Cell ID, LAC/TAC, PCI, channel and signal in dBm. The tower you are connected to is marked **★ CONNECTED** and shown first. Tap a tower with a known position to open it in Maps.
- **Radar** tab: you in the centre, every tower with a known position placed by distance and direction. The **🧭 ON/OFF** button (top right) turns the compass on: the radar then turns with the phone so towers appear where they really are around you; off = North at the top. The choice is remembered.
- **Info** tab: your position, the OpenCelliD key box, and a shortcut to Android's "Search networks" screen that lists every operator with coverage in the area.
- Header shows the connected tower and how many cells each network has.

## Where do tower positions come from?
A phone only knows a tower's *ID*, not its coordinates. Positions are looked up in the free **OpenCelliD** database:
make a free account at opencellid.org, copy your API key and paste it in the Info tab. Found positions are cached on the phone. Without a key the app still shows all IDs, networks and signal strength (works offline). 5G towers are not in the free database.

Android normally lists only the serving tower and neighbours of your own SIM's network; use the "Search networks" button for all operators.

Needs the Location permission (Android requires it for cell info). Android 7.0 (API 24) or newer.

Brought to you By: Black Falcon 🦅

## Build
Push to GitHub -> Actions -> "Build APK" -> download `CellTowers-APK`.
Local: `gradle assembleDebug` (JDK 17, Gradle 8.7+).
