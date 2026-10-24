# Smart Colony — User Guide

Mod version: 1.0.1 · Minecraft 26.3 · Fabric · singleplayer

[Русская версия](USER_GUIDE_RU.md)

> **Experiment.** Smart Colony is an experimental project studying how AI works in Minecraft. Colony behaviour may change between versions, and existing worlds may behave differently after an update. Back up your worlds.

## Contents

1. [What it is](#what-it-is)
2. [Installation](#installation)
3. [Your first colony step by step](#your-first-colony-step-by-step)
4. [The colony window](#the-colony-window)
5. [How a colony lives](#how-a-colony-lives)
6. [Villager roles](#villager-roles)
7. [Buildings](#buildings)
8. [Defense and safety](#defense-and-safety)
9. [Economy, storage and reputation](#economy-storage-and-reputation)
10. [Decrees and orders](#decrees-and-orders)
11. [Neighbours and diplomacy](#neighbours-and-diplomacy)
12. [AI advisor](#ai-advisor)
13. [Commands](#commands)
14. [Settings](#settings)
15. [Files](#files)
16. [FAQ](#faq)
17. [Building and development](#building-and-development)

## What it is

Smart Colony turns an ordinary villager village into a colony that runs itself. Villagers take jobs, cut wood, dig mines, farm, fish, raise animals, put everything into a shared storage, build houses and roads, light lamps, wall the village in and defend themselves.

The main rule: **the colony never takes anything out of thin air**. Every block of a building, every torch and every tool is paid for with items from the storage. The exceptions are the starter storage when a colony is founded and the iron golem, which the colony "forges" from ingots.

The mod is meant for singleplayer: the logic runs on the world's integrated server.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) `0.19.3` or newer for Minecraft 26.3.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) for 26.3 into the `mods` folder.
3. Put `smart-colony-1.0.1.jar` into `mods` (see [Building](#building-and-development)).

Java 25 is required. No other mods are needed. With the AirDefense mod installed, the colony runs its batteries near the village by itself.

## Your first colony step by step

1. **Find a village** with a bell and at least three adult villagers.
2. **Walk up to the bell.** The colony founds itself. You can also do it by hand with `/colony found`.
3. **Storage.** A storage is built 5–28 blocks from the bell: five columns of labelled barrels (Timber, Mine, Granary, Livestock, Arsenal), a crafting table and a furnace. The site is levelled and grass and snow are cleared; houses and fields are left alone.
4. **If there is no room**, a hologram of the storage appears in front of you for 30 seconds: green is the storage, red is what is in the way. Clear the spot, or place the storage yourself with `/colony found here` (right in front of you).
5. **Open the colony window** with `K` or `/colony`. The Overview tab shows villagers by role, storage fill, food stock, the current construction and what it is missing.
6. **From then on the colony works by itself.** You can help it: put resources into the storage, order buildings, set decrees.

A good start: put logs, cobblestone, food and a little iron into the storage. Items you drop within 4 blocks of the storage are taken in right away.

## The colony window

The `K` key (change it in **Options → Controls**) or `/colony`.

| Tab | What it shows |
|---|---|
| Overview | villagers by role, storage, food, construction and what it lacks, the mine, your reputation |
| Storage | what is in the storage |
| Villagers | villagers, their roles and current tasks |
| Journal | colony events: buildings, raids, births, deaths |
| Market | the internal market: what the colony sells and buys |
| Decrees | colony focus, restricted zones, orders |
| Neighbours | relations with other colonies, treaties, parties on the road |

## How a colony lives

### Daily routine

- The work shift runs from tick 1000 to 10500 of the game day. At night and in free time villagers live by vanilla rules: they sleep, trade and chat.
- Lunch at midday: the colony spends food, and work goes 10% faster afterwards.
- An evening gathering at the inn or the bell.
- A day off every 7 days with get-togethers at the chapel, market or tavern.
- A festival at the bell at noon every third day.
- By default villagers shelter from rain.

### Growth

- The colony works out how many homes it needs from beds and villagers.
- With free beds and a food reserve it feeds villagers bread and children are born. Children grow up and get jobs too.
- The colony radius grows from 32 to 112 blocks, but never beyond half the distance to a neighbouring colony.
- Scouts bring settlers from other villages.

### Experience

Villagers gain role experience (levels 1–5): they dig and build faster, guards hit harder.

## Villager roles

The colony assigns roles by its stock and needs, taking a villager's vanilla profession into account (farmer → farmer, fisherman → fisherman, armorer → guard). A role sticks for at least a few minutes so villagers do not flip back and forth. Pin a role with `/colony role <villager> <role>`, return to automatic choice with `/colony role <villager> auto`.

| Role | ID | What it does |
|---|---|---|
| Lumberjack | `lumberjack` | tells natural trees from buildings, fells the whole trunk, plants a sapling on the stump, replants the forest. Works the tree farm. Rests when there is too much wood |
| Miner | `miner` | digs a staircase down and a tunnel with branches, mines whole ore veins, places torches, seals water and lava. Fits out the quarry: hoist, stairs, posts, lamps |
| Farmer | `farmer` | harvests and replants, tills land by water, makes an irrigation pond when water is missing |
| Fisherman | `fisherman` | fishes by the vanilla loot table, from the dock in coastal villages |
| Herder | `herder` | leads wild animals into the pen on a lead, breeds them, shears sheep, collects eggs, keeps the herd limit |
| Builder | `builder` | builds from blueprints, clears sites, repairs buildings, lays roads and the wall |
| Guard | `guard` | patrols by day, holds the dangerous directions at night, protects villagers |
| Fletcher | `fletcher` | makes bows and arrows in the fletcher's hut |
| Smith | `smith` | makes tools and weapons in the smithy |
| Cook | `cook` | cooks food in the bakery |
| Mason | `mason` | makes glass, bricks and stone bricks; digs sand and clay |
| Beekeeper | `beekeeper` | honey and honeycomb, moves bees from wild nests |
| Alchemist | `alchemist` | brews potions in a brewing stand, makes bandages and remedies for the infirmary |
| Scribe | `scribe` | sugar cane, paper, books, the colony chronicle on a lectern, tool enchanting |
| Scout | `scout` | goes on expeditions, marks ore, villages and bee nests, brings settlers |
| Porter | `porter` | carries loot from outposts to the main storage, meets builders at distant sites |
| Medic | `medic` | treats the wounded in the infirmary |
| Teacher | `teacher` | teaches children at school |
| Innkeeper | `innkeeper` | runs the tavern |
| Undertaker | `undertaker` | brings the belongings of the dead to the storage and buries them in the cemetery |
| Villager | `idle` | no role: small chores, picking up litter and clearing snow |

With two or more miners, one becomes a prospector: they visit rich veins found by scouts and dig a passage to them.

## Buildings

Builders pick a level spot on the edge of the colony that can be reached from the entrance. Construction starts once the storage has the materials (the colony crafts what is missing first). If something is lacking, the colony window shows exactly what. Two or three sites run at once, each with a crane made of fences.

Order a building with `/colony build <blueprint>`.

| Blueprint | What it is |
|---|---|
| `house_small`, `house_family`, `house_garden`, `house_row` | small house, family house, house with a yard, big house with 4 beds |
| `house_small_2`, `house_family_2`, `house_garden_2` | stone versions of the houses with extra beds |
| `storage_shed`, `granary`, `barn` | storage shed with 6 barrels, granary, big barn |
| `outpost` | an outpost storage by a distant mine or forest |
| `townhall_1` … `townhall_5` | the five stages of the inn by the bell |
| `guard_post`, `watchtower`, `barracks` | guard post, watchtower, barracks |
| `pen`, `stable` | pen, stable |
| `blacksmith`, `fletcher_hut`, `bakery`, `masonry` | smithy, fletcher's hut, bakery, mason's workshop |
| `apiary`, `apothecary`, `library` | apiary, apothecary, library |
| `infirmary_1` … `infirmary_3` | infirmary: shelter, house, hospital |
| `market`, `trading_post`, `embassy` | market, trading post, embassy |
| `chapel`, `tavern`, `school`, `kindergarten`, `cemetery` | chapel, tavern, school, kindergarten, cemetery |
| `tree_farm`, `windmill`, `well` | tree farm, windmill, well |
| `dock`, `fisher_hut` | dock and fisherman's hut |
| `camp` | a roadside camp on the highway between colonies |

### The inn

The inn is built by the bell in five stages. A stage starts once the previous one is finished and there are enough adults: 4, 10, 18, 28 and 40. By the last stage it has 20 guest beds. At night villagers without a home go to the inn and get up in the morning. Guest beds do not count as homes, so the colony keeps building houses as usual. The inn's chests and barrels are the treasury.

### Wall, roads and lamps

- **Wall.** After two days the colony walls in its buildings, fields, pens and storage: cobblestone, planks, fence railings, towers every 40 blocks, doors where paths cross it, lamps by the gates. The wall grows with the village.
- **Roads.** The colony lays paths from doors, the storage, the mine, fields and gates to the bell. A path is made with a shovel from grass and dirt; cobblestone goes on sand and gravel. The main street is 3 blocks wide and gets stone paving in the iron age.
- **Lamps.** Posts with a torch in the stone age and a lantern in the iron age, next to roads, at least 10 blocks apart.
- **Piers.** Shallow water up to 4 blocks deep is a building site too: roads run on boardwalks on piles, houses stand on pile platforms.

### Bunker

A large colony (8 buildings and 4 houses or more) digs a two-floor underground bunker: a hall with a kitchen, storage and workshop, a bunk room and an infirmary. Villagers shelter there from raids, sieges and storms.

## Defense and safety

- **Alarm.** An enemy in the village or near a building rings the bell and guards turn out. Villagers look for shelter in a closed room of a colony building, steering around enemies. After 4 seconds without enemies everyone goes back to work.
- **Doors** are closed by the door keeper: at night and during an alarm as soon as the doorway is clear, by day after 3 seconds. Doors near a player are left alone.
- **Guards** take weapons and armor from the storage, shoot only with a clear line of fire, back off from charging enemies and leave to heal below 35% health.
- **Golems.** One iron golem per 6 adults, up to 4. A new one costs 20 ingots, a repair one ingot.
- **Pillager raids.** The bell rings, villagers hide, guards hold the gates and towers. After the raid the colony counts losses and schedules repairs.
- **Fires.** The nearest villagers run to put them out; with a water bucket in the storage they put out 5×5 at once. Burnt blocks of colony buildings and vanilla village houses are restored by repairs.
- **Villager safety.** Drowning villagers are pushed to land, and villagers stuck far from the player are moved back into place.

## Economy, storage and reputation

### Storage

Each item is sorted into its storage category, or the next one when that is full. When the storage is 75–90% full, builders put up a storage shed. If a player breaks every storage container, a villager knocks together a chest.

### Internal market

Surplus (carrots above 64, say) is sold through real trades of the colony's villagers for emeralds. Emeralds buy what the colony lacks — tools, arrows, beds — also through real villager offers with their limits.

### Caravans

Colonies with a market or an inn trade surplus once a day; the cargo is carried by a living porter. Caravans do not run during war or feuds.

### Theft and reputation

The colony watches the storage while you keep it open.

| Action | Effect |
|---|---|
| Take without paying | theft: reputation drops in proportion to the value |
| Put in emeralds worth the same | a purchase |
| Put in more than you took | a donation, reputation goes up |
| Break a storage barrel | theft |
| Hit a villager | −8 |
| Kill a villager | −60 |

Statuses: ally, guest, offender, enemy of the colony. Guards and golems attack an enemy. Reputation slowly recovers on its own. Check it with `/colony reputation`.

## Decrees and orders

- **Colony focus**: `/colony decree focus <balanced|food|defense|build|resources>`.
- **Restricted zones**: `/colony decree zone add [radius]` where you stand; the colony will not build, plant or dig there. `remove` deletes a zone, `list` lists them.
- **Orders**: `/colony order <item> <amount>`; the colony gathers or crafts the items and hands them over at the inn or the storage. `/colony order list` and `/colony order cancel` list and cancel.

All of this is also available as buttons on the Decrees tab.

## Neighbours and diplomacy

Colonies within 640 blocks get to know each other and track their relations (−100 to +100, each side with its own opinion).

- Each colony has a character: peaceful, trading or proud. Change it with `/colony diplomacy policy <auto|peaceful|warlike>`.
- Every day opinions shift with border proximity, disputes over ore, treaties and random events.
- An envoy brings gifts and proposals to neighbours: a trade treaty, an alliance, a truce. Send one by hand with `/colony diplomacy envoy <colony>`.
- Guards come to help an ally during a raid.
- **War.** When grievances pile up, a proud colony declares war: a party of 2–4 guards marches on the enemy storage, fights its guards and carries off loot. Losses make both sides war-weary and they make peace; a truce lasts 3 days. By hand: `/colony diplomacy war <colony>`, `raid <colony>`, `peace <colony>`.
- **Highways.** Neighbours with a treaty, an alliance or simply good relations (within 640 blocks) build a road between their colonies: each builds its own half, long highways get roadside camps, and a signpost marks the start. Start one by hand with `/colony diplomacy road <colony>`. Turn off with `highways: false`.

Turn wars off with `colonyWars: false`, and all diplomacy with `diplomacy: false`.

## AI advisor

Optional. A local language model looks at the colony every few minutes and may adjust up to three roles when they are clearly unbalanced, order a building, a craft or the rescue of a stuck villager. It is off by default, and the colony works the same without it.

To turn it on with [Ollama](https://ollama.com/):

1. Install Ollama and pull a model, for example `ollama pull gemma4`.
2. Set `"aiEnabled": true` in `config/smartcolony.json`. The default address is `http://127.0.0.1:11434`, the model `gemma4:latest`.
3. In game run `/colony ai reload`, then `/colony ai on`.
4. `/colony ai` shows the advisor's state and latest thoughts, `/colony ai now` asks at once.

For an OpenAI-compatible API set `"aiApi": "openai"` plus your `aiEndpoint`, `aiModel` and `aiApiKey`.

## Commands

Every command starts with `/colony`. Without the mod on the client the answers arrive in chat.

| Command | What it does |
|---|---|
| `/colony` | the colony window |
| `/colony info` | the window on the Overview tab |
| `/colony stock`, `journal`, `market` | the window on that tab |
| `/colony list` | list of colonies: name, centre, villagers, radius |
| `/colony found` | found a colony at the nearest bell |
| `/colony found here` | place the storage right in front of you |
| `/colony reputation` | your reputation |
| `/colony build <blueprint>` | order a building |
| `/colony bunker` | the bunker's state; plans one if there is none |
| `/colony role <villagers> <role\|auto>` | pin a role or return to automatic choice |
| `/colony decree focus <focus>` | colony focus |
| `/colony decree zone add [radius]`, `remove`, `list` | restricted zones |
| `/colony order <item> <amount>`, `list`, `cancel` | orders |
| `/colony relations` | relations with neighbours |
| `/colony diplomacy policy <auto\|peaceful\|warlike>` | foreign policy |
| `/colony diplomacy` | the same as `/colony relations` |
| `/colony diplomacy envoy\|peace\|war\|raid <colony>` | envoy, peace, war, raid |
| `/colony diplomacy road <colony>` | build a highway to a neighbour |
| `/colony ai`, `ai on\|off`, `ai now`, `ai reload` | the AI advisor |
| `/colony disband` | disband the colony: villagers go back to vanilla behaviour, buildings stay |

## Settings

`config/smartcolony.json` is created on first launch. Restart the world after editing it.

| Key | Default | Meaning |
|---|---|---|
| `autoFound` | true | found a colony automatically when a player is near a bell |
| `minVillagersToFound` | 3 | adult villagers needed to found |
| `colonyRadius` | 32 | starting colony radius |
| `maxColonyRadius` | 112 | maximum radius (at most 192) |
| `workStart`, `workEnd` | 1000, 10500 | work shift in day ticks |
| `showRoleNames` | true | role labels above villagers |
| `notifyPlayers` | true | colony messages in chat |
| `stuckTeleport` | true | move stuck villagers |
| `shelterInRain` | true | shelter from rain |
| `rainWorkRatio` | 0.0 | share of villagers who keep working in the rain |
| `blockPlaceTicks` | 6 | ticks per placed block |
| `mineTargetY` | 12 | target mine depth |
| `treeTarget`, `forestRadius` | 12, 64 | trees to keep nearby and the forest radius |
| `woodSurplus` | 320 | with this much wood stored, lumberjacks rest |
| `golemIronCost`, `maxGolems`, `villagersPerGolem` | 20, 4, 6 | golem cost, maximum golems, villagers per golem |
| `animalsPerSpecies` | 6 | herd limit per species |
| `marketIntervalTicks` | 2400 | market trading period |
| `guardRetreatHealth` | 0.35 | health share at which a guard retreats |
| `guardsAttackEnemyPlayers` | true | guards attack players who are enemies of the colony |
| `townHallAdults` | 4, 10, 18, 28, 40 | adults for each inn stage |
| `infirmaryAdults` | 8, 16, 30 | adults for each infirmary stage |
| `adultsPerMedic` | 15 | adults per medic |
| `lunchBreak`, `eveningGathering` | true | lunch and the evening gathering |
| `restDayEvery` | 7 | a day off every this many days |
| `firefighting`, `snowClearing` | true | firefighting and snow clearing |
| `caravans`, `highways` | true | caravans and highways between colonies |
| `diplomacy`, `colonyWars` | true | diplomacy and wars |
| `bunkers`, `stormShelter` | true | bunkers and storm shelter |
| `recruitFromVillages` | true | settlers from other villages |
| `aiEnabled` | false | the AI advisor |
| `aiApi` | `ollama` | `ollama` or `openai` |
| `aiEndpoint`, `aiModel` | `http://127.0.0.1:11434`, `gemma4:latest` | address and model |
| `aiApiKey` | empty | key, OpenAI-compatible API only |
| `aiLanguage` | `ru` | the advisor's answer language |
| `aiIntervalTicks`, `aiTimeoutSeconds` | 3600, 120 | how often to ask and how long to wait |
| `aiCanAssignRoles`, `aiCanBuild`, `aiCanCraft`, `aiCanRescue` | true | what the advisor may do |

## Files

- `config/smartcolony.json` holds the settings.
- `<world>/smartcolony/colonies.json` holds the colony data inside the world folder, with a `.bak` backup. Writes run on a separate thread.

## FAQ

**The colony does not found itself.** You need a bell and at least 3 adult villagers nearby (`minVillagersToFound`). Check that `autoFound` is on, or run `/colony found`.

**The storage hologram is red.** The storage spot is blocked. Clear it or place the storage yourself: `/colony found here`.

**Construction is stuck.** The Overview tab shows what is missing; put it into the storage. The colony crafts missing items only when it has the raw materials.

**Villagers do not work.** Work runs from tick 1000 to 10500. At night, in the rain and on days off villagers rest.

**The colony says I stole from it.** That is your reputation: the colony counts everything you take from its storage without paying as theft. Put emeralds or items back to restore it.

**How do I disband a colony?** `/colony disband`. Villagers go back to normal behaviour and buildings stay.

**Does it work on a server?** The mod is meant for singleplayer and has not been tested on a server.

## Building and development

JDK 25 is required.

```powershell
git clone https://github.com/ServChan/SmartColony.git
cd SmartColony
.\gradlew.bat build
```

On Linux and macOS use `./gradlew`. The jar is `build/libs/smart-colony-1.0.1.jar`.

For debugging, the separate `src/devbridge` source set holds the `smartcolony_devbridge` mod: an MCP server on `http://127.0.0.1:25599/mcp` with tools for inspecting the colony and running commands. It is not part of the release jar. Run it with `.\gradlew.bat runDevServer` (a headless server in `run-dev/`) or `.\gradlew.bat runDevClient`.
