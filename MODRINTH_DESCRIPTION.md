Real physics Jenga for Minecraft. A recreation of the systemzee video where the tower actually leans, wobbles, and comes crashing down — built on the **Sable** physics engine, so every piece is its own physics object that falls, tips, and collides on its own.

<iframe width="560" height="315" src="https://www.youtube-nocookie.com/embed/qIC6ax-VWWc" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>

## How it plays

Run `/stack` to drop a full tower in front of you, then play it for real — grab a piece with an empty hand, ease it out, and try not to be the one who brings the whole thing down. Each piece is a 1×3×9 block of stripped logs, and the layers alternate direction exactly like the real game.

## Commands

| Command | What it does |
|---|---|
| `/stack <N>` | Build an N-layer tower (1–999), one layer per tick |
| `/stack cancel` | Stop a build that's in progress |
| `/stack status` | Check whether a build is running for you |
| `/gamerule jengaMaxLayers <N>` | Cap tower height for the world (default 999) |
| `/jenga start`, `/jenga turn next`, `/jenga turn last` | Turn-based play — **heavily in beta** |

## Controls

| Action | Result |
|---|---|
| **Right-click** a piece (empty hand) | Grab it |
| **Scroll** while holding | Rotate the piece 90° |
| **Ctrl + scroll** (rebindable) while holding | Move the piece closer or further from you |
| **Right-click** again | Let go and let physics take over |

## Config

Open the **Jenga** button in the options menu to choose which blocks the tower is built from. (Turn enforcement is still very early beta, so it may not be finished yet.)

## Before you download — please read

> [!IMPORTANT]
> - **Requires [Fabric Loader](https://fabricmc.net/), [Fabric API](https://modrinth.com/mod/fabric-api), and [Sable](https://modrinth.com/mod/sable)** for **Minecraft 1.21.1**.
> - Sable is the physics engine that makes the pieces move — the mod will not work without it.
> - In multiplayer, install it on both the client and the server, on the same version.

## Known limitations

Honest trade-offs, not bugs:

- The `/jenga` turn-based commands (start, turn enforcement) are **heavily in beta** and may not behave correctly yet.
- Big towers take a moment to build — one layer per tick, so `/stack 999` is intentionally slow.

<details>
<summary>How does it work under the hood? (optional reading)</summary>

Each Jenga piece is a 1×3×9 group of stripped logs assembled into its own **Sable sub-level**, so it falls, tips, and collides independently of every other piece. Grabbing a piece follows your view and your scroll rotation, dispatching Sable teleports to whichever sub-level you're looking at.

</details>

## Compatibility

| | |
|---|---|
| **Minecraft** | 1.21.1 |
| **Loader** | Fabric |
| **Dependencies** | Fabric API, Sable |
| **Java** | 21+ |

Found a bug or have an idea? Open an issue on [GitHub](https://github.com/ToasterStudiosMods/Jenga-Mod/issues) with what you did and what happened — it really helps.
