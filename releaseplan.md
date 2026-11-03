# Release plan: camera views of all servers on meckminecraft.de

Goal: one page on meckminecraft.de that shows the SecurityCams camera views of all our Minecraft servers.

## Compatibility

| Host | Servers | Spigot | SecurityCams |
|---|---|---|---|
| meckminecraft.de (185.170.113.136) | lobby, mc1–mc5 | 26.3 | yes |
| nc3 (2.56.99.112) | mc2–mc5 | 26.3 | yes |
| nc3 (2.56.99.112) | mc1 | Forge 1.20.1 | **no** (Forge, not Spigot) |
| codefield (89.58.11.96) | codefield-javascript | 26.3 | yes |

## Architecture

The plugin renders the camera images itself, on the Minecraft server (a simplified view made of block colours, raycasting). It serves them from a built-in web server on port 8090, protected by a token.

- **Same host (meck lobby, mc1–mc5):** join the containers to the `minecraftdash_default` network in `docker-compose.override.yml`, like the admin panel.
- **Other hosts (nc3, codefield):** a Caddy block on each host (like the `karte.*` BlueMap setup), or open port 8090 only for 185.170.113.136.
- **meckminecraft.de Caddy:** one path per server, e.g. `/cams/meck-mc1/*` → `mc1:8090`. Caddy adds the server's token itself (`header_up Cookie "sctoken=…"`), so the tokens never reach the browser.
- **Overview page:** a new `kameras.html` on the homepage (style like `welt-ansicht.html`). It reads `/api/cameras` of each server and shows the images grouped by server.

## Required changes before going public

1. **Sub-path support.** The plugin's own page fetches `/api/cameras` and `/cam/…` from the root, so it breaks behind `/cams/mc1/`. Either the central page builds its own URLs (simplest), or the plugin switches to relative URLs.
2. **Server load.** Rendering only runs while someone watches, so a public page means rendering all the time. Each watched camera keeps its chunks loaded and uses the two render threads at 4 fps by default. With many cameras this hurts nc3 mc4 (two parallel BlueMap renders already pushed it to ~10/10 cores). For the public setup, lower `render.fps` (1–2) and `render.distance`.
3. **Request limit.** `max-streams: 16` only limits MJPEG streams. Snapshot requests (`/cam/<id>.jpg`) can each wait up to 10 s, and nothing limits how many run at once (cached thread pool), so many visitors can exhaust the web server. Add a limit for snapshots in the plugin and/or a rate limit in Caddy.
4. **Privacy.** The page will be **public** (decided 2026-11-03). The images show player names as labels, and the players are school kids, so: tell players that cameras exist (notice on the page and in-game, e.g. MOTD or a sign at each camera). Because the page is public, the request limit (point 3) and a Caddy rate limit are mandatory.

## Steps

1. Plugin release 0.2.1: fix points 1 and 3, defaults for lower load. GitHub release + entry in the minecraftHostingServer plugin catalog (like BlockWatch).
2. Pilot on one server (meck mc1): create a camera, connect the meck Caddy, build `kameras.html`.
3. Roll out to the other servers one at a time: meck lobby, mc2–mc5, then nc3 mc2–mc5 and codefield (each with a Caddy block on its host).

## Decisions

- 2026-11-03: the page is **public** (no login).
