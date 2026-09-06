# MCphone (GTNH)

English | [简体中文](README.md)

> [!NOTE]
> This is the English readme. The project's default page is [简体中文 (README.md)](README.md).

A working smartphone inside GTNH — take photos, browse a gallery, change wallpapers, jot notes, teleport between multiple waypoints, and connect straight to an AE2 Quantum/Universal Wireless Terminal. You can even name your phone.

> **What is this**: skyc10, with the help of an AI coding assistant, **ported and rewrote** [november521/mcphone](https://github.com/november521/mcphone) (the original, mainline: Minecraft 1.21.1 + NeoForge) for **GTNH 2.9** — the original mod is by november521; this repository (skyc10) maintains the GTNH port.
> Target environment: **GTNH 2.9.0-beta-3** (Minecraft 1.7.10 + Forge 1614, Java 17+ runtime, lwjgl3ify).
> The UI layer is fully rebuilt on the [Qz-UILib](https://github.com/QuanhuZeYu/Qz-UILib) scene UI (native-resolution rendering with a modern font renderer).

**Minecraft 1.7.10** · **GTNH 2.9.0-beta-3** · Install on both client and server

**Dependency**:

| Mod | Notes |
| --- | --- |
| [Qz-UILib](https://github.com/QuanhuZeYu/Qz-UILib) (4.8+) | **Required**. A modern scene UI library built for GTNH — the entire phone UI runs on it. If the library does not publish build artifacts, grab it from this project's Releases |

**Integration**: the ME Terminal app depends on **AE2** and **ae2fc** (Ultra Wireless Terminal) — both ship with the GTNH modpack, no extra install needed. In a GTNH instance with them installed, the phone can bind by sneak + right-clicking an ME Security Station, and the ME Terminal icon opens the Ultra Wireless Terminal's full interface directly.

---

## Getting a phone

Craft it at a workbench, one per craft:

```
Iron Ingot   Glass Pane   Iron Ingot
Iron Ingot   Redstone     Iron Ingot
             Iron Ingot
```

Hold it and right-click to power on; with the phone in your inventory, **P** also opens it (rebindable under Options → Controls → MCphone).

`Esc` is hierarchical: on an app page it goes back to the home screen, on the home screen it powers off; the ⌂ button at the bottom always returns home.

## Features

| App | Notes |
| --- | --- |
| 🕐 Clock | Large world-time display, kept in sync with the status bar |
| ☀️ Weather | Current biome, rain/thunder, day-night state |
| 📝 Notes | Quick notes stored in `.minecraft/mcphone/notes/` (shared across saves), with create/edit/delete |
| 📦 Ender Chest | One-click access: closes the phone and opens your ender chest, fully vanilla-compatible |
| 🌀 Teleport | Built-in teleportation — **no Charm of Dislocation needed**. The icon opens a waypoint list: bind current position (or Shift+click the icon to bind quickly), one-click teleport (cross-dimension supported), rename, delete. Waypoints are stored in the phone's NBT |
| 📡 ME Terminal | Direct AE2 access. With an **Ultra Wireless Terminal** in your inventory it is auto-swapped into your hand and its full interface opens, then swapped back on close; without one, the phone's built-in item terminal opens (power is free). Bind by sneak + right-clicking an ME Security Station with the phone |
| 📷 Camera | Viewfinder + key capture. Defaults: **C** shoot, **P** back to the phone (rebindable). Photos contain only the world — HUD, minimap, hotbar and crosshair never appear |
| 🖼 Gallery | Thumbnail grid, full-size viewer, delete, one-click set-as-wallpaper. Photos live in `.minecraft/mcphone/photos/` (**shared across saves**) — you can also drop any PNG into that folder and the gallery reads it |
| ⚙️ Settings | Device naming, wallpaper reset, plus **UI size** (50–150%) and **font size** (50–500%) sliders — applied on release, great for high-resolution displays |
| 🗂 App Manager | One row per app: click to enable/disable (applied on next launch), ↑/↓ to reorder home-screen icons, order persisted |

## For developers / AI handover

Architecture, pitfall notes, build/release workflow and conventions: **[docs/AI-DEV-NOTES.md](docs/AI-DEV-NOTES.md)** (Chinese). Addon development: [docs/addon-api.md](docs/addon-api.md). The full development history lives on the `dev-history` branch.

## Storage locations

| Content | Path |
| --- | --- |
| Photos / wallpaper | `.minecraft/mcphone/photos/` (drop PNGs in and the gallery reads them) |
| Notes | `.minecraft/mcphone/notes/` |
| Phone settings (scaling / icon order / app toggles) | `.minecraft/mcphone/settings.properties` |
| Addon app config | `.minecraft/mcphone/appdata/<appId>.properties` |

All of the above are **shared across saves** (client-local).

## For addon developers

MCphone ships a scene-UI-based app extension API: extend `PhoneAppBase`, build pages with `PhoneWidgets`, persist settings via `PhoneAppConfig` — a working app in a few dozen lines. See **[docs/addon-api.md](docs/addon-api.md)** (Chinese).

Register via code (`PhoneApi.register(...)`) or auto-discovery through `META-INF/services` inside your jar.

## Addon mods

Addons built on the API above:

| Addon | Notes |
| --- | --- |
| [mcphone-addon-browser](https://github.com/skyc10/mcphone-addon-browser) (in development) | 🌐 **Browser**: click the icon to open real web pages on a 16:9 fullscreen virtual display, with bookmarks and history |

## Credits

- **[november521](https://github.com/november521)** — original author of MCphone, where it all began: [november521/mcphone](https://github.com/november521/mcphone) (this repository is its GTNH port + rewrite, done by skyc10 with an AI coding assistant)
- **[QuanhuZeYu](https://github.com/QuanhuZeYu)** — author of [Qz-UILib](https://github.com/QuanhuZeYu/Qz-UILib), a rare modern scene UI library on GTNH (LGPL-3.0, used as a dependency by this mod)
- **[Zhipu AI (Z.ai)](https://github.com/zai-org/GLM-5)** — free GLM-5.3 tokens. This mod's entire GTNH port and rewrite was done by an AI coding assistant powered by GLM-5.3.

Without the original author's design and code, this GTNH version would not exist. Thank you.

## License

Follows the original project's license; Qz-UILib is LGPL-3.0 and is used as an independent mod dependency — its source is not bundled modified.
