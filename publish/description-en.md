# Deep Space Deployer

> An **Aeronautics factory addon** for Minecraft **1.21.1** / **NeoForge** — singleplayer & multiplayer.

Six interstellar companies have built factories across your world. Sell materials, buy modules gated by
factory tier and reputation, print structures from Create blueprints, then weld them into real physics vehicles.

---

## The six companies

| Company | Theme | Currency |
|---|---|---|
| Threshold Laboratories | Research | Threshold scrip |
| Whisperbelt Technologies | Envelopes / stealth | Whisperbelt scrip |
| Brokenedge Ordnance | Weapons | Brokenedge scrip |
| Skimmer Propulsion | Engines | Skimmer scrip |
| Bedrock Heavy Industries | Heavy industry | Bedrock scrip |
| Astralink Dynamics | Energy | Astralink scrip |

**Currencies are not interchangeable.** Exchanging between companies costs a fee (20% by default),
so buying low and selling high across companies is a real playstyle.

---

## Four machine blocks (each with 6 companies × 3 tiers = 18 looks)

**🏪 Factory Terminal (shop)** — insert a bank card to power it on, then deposit scrip, withdraw cash,
exchange between companies, and buy modules page by page. Stock is gated by **factory tier + reputation**.
Every price lives in a datapack, and server admins can edit it **in game** from the pause menu.

**🏭 Factory Assembly Core** — drop Create blueprints (`.nbt`) into `config/deepspacedeployer/blueprints/`,
pick one, feed the required materials (the UI shows have/need), then hit **Assemble**.
- Pick the drop point on a **top-down map** rendered from real terrain
- Cost = 20 scrip per block + materials; low tier caps at 200 blocks, mid 800, high 4000
- With Sable installed the finished structure is **assembled into a physics sub-level** automatically

**🔧 Welding Bench** — welds the factory's latest build into a physics structure. The **high tier** bench can
also toggle collision (static/dynamic) and dissolve the structure. (Low/mid tiers have no collision toggle.)

**💰 Acquisition Station** — drop items in (or pipe them with hoppers / Create arms) and they are converted
into scrip on the card holder's account. **Low tier pays 0.8×, mid 1.0×, high 1.2×.**

---

## Bank cards are account pointers

- Right-click a card to **bind it to a player account** and give the card a **name**
- Money is stored **inside the blocks, per player** — your balance in factory A and factory B are separate
- Spending order: **block balance first, then the card balance**
- Leave the card inserted and **other players can spend the owner's money** — a company credit card

---

## Build your own factory

- **Company licence** (sold by each company): right-click your own machine to convert it to that company
- **Tier upgrade kit**: right-click a machine to upgrade one tier (low → mid → high)
- **Blueprint voucher**: buy the rights to a specific structure, then right-click a Factory Core to select it

---

## World generation

18 factories spawn by tier (BiomesOPlenty biomes included): low tier in plains/forests, mid tier in
deserts/taigas/hills, high tier only in mountains and other extreme biomes. Each building already contains
machines configured for its company and tier, plus a chest of that company's scrip.

---

## Multiplayer

- Everything is **server authoritative** (prices, balances, permissions, assembly, welding)
- Acquisition stations accept **hoppers and Create arms** for fully automated selling
- Admin tools: `/dsd reloadblueprints`, `/dsd selftest`, and the in-game price editor (OP only)

---

## Dependencies

| Mod | Requirement |
|---|---|
| NeoForge 1.21.1 | Required |
| Create | **Recommended** (default shop tables and blueprint ecosystem; the mod still runs without it) |
| Create: Aeronautics / Sable | Optional — physics structure features |
| Sable Photomancy / Synaxis | Optional — collision toggle / welding integration |

---

## Reporting issues

Run **`/dsd selftest`** in game (or search `[自检]` in `logs/latest.log`) — it prints a full diagnostic
report of registries, shop tables, blueprints, config, worldgen and integration bridges. Include it with your report.
