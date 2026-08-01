# PictoPals

A PictoChat-style offline drawing chat for Android, inspired by the Nintendo DS
Lite's PictoChat. No internet, no accounts, no servers — everything happens over
a local WiFi network or one phone's hotspot, so it works on an airplane.

**[⬇️ Download PictoPals-v1.1.apk](PictoPals-v1.1.apk)** (~30 KB)

## Installing (sideloading)

1. On each phone, open this file in the GitHub app or browser and download
   `PictoPals-v1.1.apk` (tap **Raw** / **Download** on GitHub).
2. Open the downloaded file. Android will ask you to allow installs from that
   app (browser/Files) — allow it, then install.
3. Play Protect may warn about an unknown developer; tap **Install anyway**.
   The app asks for **no permissions** beyond network access and never touches
   the internet.

## Using it on a plane (or anywhere without WiFi)

1. **One phone hosts the network**: turn on Airplane mode, then re-enable the
   **WiFi hotspot** (Settings → Network → Hotspot & tethering). No SIM/data
   needed — the hotspot just creates a local network.
2. **Everyone else** joins that hotspot from their WiFi settings (also fine in
   airplane mode with WiFi re-enabled).
3. Everyone opens PictoPals and enters a name + color.
4. The hotspot phone taps **Host** → room A, B, C, or D.
5. Within a couple of seconds the room appears under **Nearby rooms** on the
   other phones — tap it to join.
6. Draw with a finger (✏️ thin pen, 🖊️ thick pen, 🧽 eraser, **Aa** stamps typed
   text, 🗑️ clears) and hit **SEND**.

At home, everyone being on the same house WiFi works too — no hotspot needed.

If room discovery ever fails (some networks block broadcasts), use
**Join by IP address…** with the host's IP (hotspot hosts are usually
`192.168.x.1`, shown in hotspot settings).

## Tech notes

- Plain Java Android app, zero external dependencies, minSdk 26 (Android 8+).
- Host phone runs a TCP relay server (port 41117) and broadcasts a UDP
  discovery beacon (port 41118) once per second; clients auto-discover rooms.
- Messages are the drawing panel encoded as PNG, relayed to everyone in the
  room. Nothing is stored or sent anywhere else.
- Built with `gradle assembleRelease` (Android SDK 35). Signed with the
  committed `keystore/pictopals.jks` (passwords: `pictopals`) — this is a
  throwaway hobby key kept in the repo so future builds can update-install
  over old ones; don't reuse it for anything that matters.

## Why not real PictoChat with a DS Lite?

Real DS PictoChat uses Nintendo's proprietary "NiFi" wireless protocol —
near-802.11b frames that phone WiFi chips cannot send or receive. No phone app
can join an actual DS PictoChat room; this app recreates the experience
phone-to-phone instead.
