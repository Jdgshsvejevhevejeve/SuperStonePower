# StonePowers

A complete Paper 1.21.11 / Java 21 Minecraft plugin project implementing exactly eight elemental stones, permanent hearts, PvP stone transfer, cooldown-based active abilities, and a matching modern item-model resource pack.

## Requirements
- Paper 1.21.11
- Java 21
- Gradle 8.10+ (GitHub Actions supplies 8.10.2 automatically)

## Project layout
`src/main/java/me/bogeyman/stonepowers/Main.java` contains the plugin implementation.
`src/main/resources/plugin.yml` and `config.yml` contain plugin metadata and settings.
`resourcepack/` is the root of the resource pack; it contains `pack.mcmeta`, `pack.png`, and `assets/` directly.

## Build locally
```bash
gradle build
```
The plugin JAR is written to `build/libs/` and the resource-pack ZIP is written to `build/distributions/StonePowers-ResourcePack.zip`.

## GitHub Actions
Push the project to a repository's `main` branch. The workflow at `.github/workflows/build.yml` also supports manual `workflow_dispatch` runs. It builds with Java 21 and publishes two separate artifacts: `StonePowers-JAR` and `StonePowers-ResourcePack`.

To download them in GitHub, open the workflow run, then scroll to **Artifacts**.

## Server installation
1. Build or download `StonePowers-1.0.0.jar`.
2. Put the JAR into the Paper server's `plugins/` folder.
3. Restart the server.
4. The plugin creates and uses its config from `config.yml`.

## Resource-pack installation
Use `StonePowers-ResourcePack.zip` exactly as produced. The ZIP root contains `pack.mcmeta`, `pack.png`, and `assets/`; there is no extra enclosing folder.
For client-side testing, place the ZIP in the Minecraft resourcepacks folder and enable it.

The plugin uses the modern `minecraft:select` item-model dispatcher on the Echo Shard's CustomModelData component. Model values are:
- Fire 1
- Ice 2
- Ender 3
- Lightning 4
- Water 5
- Earth 6
- Wind 7
- Wither 8

## Stones
Every stone has exactly two passive abilities and two active abilities. Right Click triggers active ability 1; Sneak + Right Click triggers active ability 2.

- Fire: Fire Resistance, Fire Power, Flame Burst, Meteor Strike
- Ice: Freeze Resistance, Frozen Power, Frost Wave, Ice Prison
- Ender: Ender Vision, Ender Mobility, Ender Dash, Void Pull
- Lightning: Lightning Resistance, Storm Speed, Thunder Strike, Storm Field
- Water: Water Breathing, Ocean Speed, Water Blast, Tsunami
- Earth: Earth Resistance, Earth Armor, Earthquake, Earth Shield
- Wind: Wind Speed, Wind Jump, Wind Dash, Tornado
- Wither: Wither Resistance, Dark Regeneration, Wither Blast, Wither Storm

## Hearts
Players start at 10 hearts, with a configurable range from 1 to 20 hearts. In player-vs-player kills the killer gains one heart and the victim loses one heart, bounded by the configured limits. Heart values are stored in each player's PersistentDataContainer and the Minecraft max-health attribute is kept in sync.

## Commands
- `/stone hearts` — view your hearts.
- `/stone hearts <player>` — view another player's hearts.
- `/stone sethearts <player> <amount>` — set hearts within configured limits.
- `/stone resethearts <player>` — reset to the configured starting hearts.
- `/stone give <player> <stone>` — replace a player's stone with a selected stone.
- `/stone giveall <player>` — give all eight stone items.
- `/stone reroll <player>` — replace the player's stone with a new random stone.
- `/stone reload` — reload `config.yml`.

Admin subcommands require `stonepowers.admin`, which defaults to server operators.

## Notes
Abilities never deliberately target their owner, use visual-only lightning where appropriate, and avoid block-breaking/world-griefing effects. Stone identities are stored in item PersistentDataContainer metadata, so ordinary Echo Shards do not activate the abilities.
