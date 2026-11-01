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
18. [Feature reference](#feature-reference)

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

## Feature reference

A detailed description of the features, moved here from the README. Step-by-step instructions are in the sections above.

### Founding

When a player is near a village bell with at least 3 adult villagers around, the colony founds itself (or use `/colony found`). A storage is built next to the bell: 5 columns of barrels with signs — Timber, Mine, Granary, Livestock, Arsenal — plus a crafting table and a furnace.

The spot is picked 5–28 blocks from the bell: the ground is levelled (up to 4 blocks of fill or cut), grass, paths, snow and leaves are cleared, and houses, fences, farms and other buildings are never touched. If there is no room, a hologram of the storage appears in front of the player for 30 seconds: green is the storage, red is what is in the way.

### Categorized storage

Each item goes to its category, overflowing into the others. At 75–90% fill builders add a storage shed with 6 more barrels.

### Roles

The colony assigns jobs from its stock and needs, preferring each villager's vanilla profession (farmer → farmer, fisherman → fisherman, weaponsmith → guard, …). A role is kept for at least a minute. The work shift is day ticks 1000–10500; at night and in free time villagers follow vanilla behaviour (sleep, trade, socialise).

### Lumberjack

**Lumberjack** tells natural trees from buildings (non-persistent leaves, no planks/glass/doors nearby), fells the whole trunk bottom-up, clears only the orphaned leaves, collects logs, saplings and apples, replants the stump and plants more saplings when the forest thins. When the storage holds enough wood, lumberjacks rest.

### Tree nursery (`tree_farm`)

**Tree nursery** is planned at 6 or more adults (a second one at 25) once a house and a storage stand, or earlier when the forest thins: the colony levels a 15x15 plot (fill up to 4, cut up to 5 blocks), dresses it with a low cobblestone wall with an open gap, a dirt-path ring and torches on posts, and keeps nine sapling plots four blocks apart.

Lumberjacks plant storage saplings into free plots first, fell the grown trees there before wild ones and replant at once; the wall and plots are never repaired or decorated over.

### Miner

**Miner** takes a pickaxe and torches, picks a mine site, digs a 3-high staircase down, then a main drift with side branches; mines whole ore veins, lights dark spots, seals water and lava with cobblestone, patches the floor over gaps, and walks back along stored waypoints. Flooded sections are skipped; a worked-out mine is abandoned for a new one in another direction.

Miners fit the quarry themselves while they work, outside the builder queue: a gate, a headframe with a winch rope, a deck and a barrel shed on the surface, and inside timber corner posts, lantern beams every four layers, a cobblestone spiral stair with landings and a fence rail on the pit side; stair-mine tunnels get a log frame every three blocks. Layers of older quarries are retrofitted top-down as miners pass.

When there are more than two miners, the colony opens extra mines (two miners per mine, up to three), next to a rich vein found by the scouts when one is close enough; every mine gets its own road, outpost and fittings.

Miners sharing a mine each keep to their own branch and cells dug out of order are remembered; wall holes left by mined ore are patched with cobblestone, and water running into a tunnel is stopped by plugging its sources.

### Porters and outposts

When the walk from the mine or quarry to the storage is long (over 24 blocks, counting height difference, or miners keep hitting "no path") and there are at least six adults, the colony builds a drop-off outpost (`outpost`, a 5x5 shed with four barrels, a roof and torches) from storage materials next to the mine, outside the pit and its protected zone.

The same happens at the forest edge when lumberjacks walk more than 40 blocks to their trees on average. Miners and lumberjacks deposit into the nearest outpost when it is clearly closer than the main storage and has room; the miner takes cobblestone back there.

A porter (role `porter`) takes the largest stacks from outposts with a full inventory to the storage, and also meets builders at far building sites to collect what they dug out while clearing. One porter per outpost holding goods plus one per two far sites (at most a fifth of the adults), none when there is nothing to carry. If the barrels are gone or nobody can reach the outpost, its entry is dropped.

Construction materials never need carrying: they are taken from storage when a site is reserved.

### Farmer

**Farmer** harvests ripe crops and replants at once, sows empty farmland, tills new land by water when food is short, finds new water for fields and, without water nearby, carries a bucket to dig an irrigation pond. Without seeds, breaks grass for seeds.

### Fisherman

**Fisherman** finds open water and fishes using the vanilla fishing loot table; the colony cooks part of the raw fish and meat (fuel: coal, charcoal, planks or logs from storage).

### Herder

**Herder** gets a pen (built by a builder), leashes wild cows, sheep, pigs and chickens and leads them in, breeds pairs, shears sheep, gathers eggs and wool, keeps a population cap (6 per species by default) and culls extras when meat runs low. Animals in other pens or in water are left alone.

Pen fence posts are two blocks high so animals cannot hop onto them and walk out; existing pens get the missing logs during the building inspection.

### Builder

**Builder** builds from blueprints (small and family house, house with a yard, storage shed, animal pen, guard post, blacksmith, market, barn, watchtower, chapel) on flat land at the colony edge that can be reached from the entrance. Construction starts only with 100% of the materials in storage (the colony crafts what it can first: planks, doors, slabs, beds, torches, panes).

Missing items are shown, and meanwhile the colony builds whatever it can afford. The site is cleared, a cobblestone foundation fills gaps (at most 3 blocks so nothing hangs over a cliff), blocks go up layer by layer, doors and beds as pairs. Windows without glass become fences; a bed of the wanted colour is replaced by one made of available wool.

When only late items are missing (beds, carpets, glass panes, lanterns, banners, flower pots), the build starts without them and places them last once they arrive.

### Clearing and terraces

Before building, the colony prepares the site from the live world: trees (including huge dark oaks) and giant mushrooms touching the site plus a 2-block margin are cut down whole, top to bottom; the slope is cut down to the floor level (up to 8 blocks) with 1-block steps left on the sides up to the terrain, an inverted truncated pyramid you can walk up; holes around are filled in steps (dirt first, then stone from the storage) and the approach to the door is levelled for 3 blocks.

Other buildings, roads, fields, mines and the wall are left alone. A builder removes up to 4 blocks of a column per visit, and surplus dirt and stone beyond the stock cap is thrown away. Hillsides in the middle of the village are no longer left empty: site scoring makes cutting cheap and filling expensive.

Giant mushrooms inside the colony and trees in the way of buildings, roads and doors are removed even without a building project. No more than 6 blocks are cut from one column; anything steeper stays as a natural ledge, and a builder never digs the column it stands on.

When the bell sits far above or below the storage, the storage anchors roads, lamps and site search, and mines keep 8 blocks from buildings and 18 from the storage.

### Terrain survey

After founding, the colony scans the land around the centre in small slices (96 columns per tick, loaded chunks only) out to the colony radius plus 32 blocks: ground height and a surface class (ground, path, road, water, tree, field, foreign structure, colony building, quarry). The map is not saved and is rebuilt after loading; builders use it to pick sites cheaply.

Right before a project leaves the waiting phase, and again before the foundation, the footprint is measured again from the live world: if the land was dug, filled or built over, the plan is recomputed (fill, height) or the building moves to a new site together with the materials already set aside.

Decor without support is propped with cobblestone from the storage or skipped, and finished houses get their floors checked. If a player breaks every storage container, a villager holding two logs or eight planks crafts a chest and places it at the storage spot.

### Building sites and repairs

Two or three sites run at once, each under a fence crane that is taken down afterwards. Finished buildings get a levelled 3x3 entrance apron and decor by building type; old village houses are renovated over time. Buildings and the wall are inspected and missing blocks repaired.

### Inn

Built instead of the town hall, five stages on one spot by the bell: a timber hall with 12 beds, a porch and two more beds by the door, a loft with the colony head's room, lanterns and a ridge cupola (iron age), a stone chimney, terrace and lamp posts, ending with 20 guest beds.

A stage starts when the previous one is finished and there are enough adults (default 4, 10, 18, 28, 40; `townHallAdults` in `config/smartcolony.json`). An upgrade reuses the same footprint: correct blocks stay, the rest is taken down and returned to the storage. Stages 3 and 5 allow one more parallel building site each.

The first stage is planned as soon as a storage exists and there are 4 adults, ahead of other buildings and regardless of the bed shortage. At night villagers without a home of their own (guards excepted) go to the inn, lie down in a free bed and get up in the morning; guest beds never become anyone's permanent home and do not count as housing, so houses are still built.

When the beds run out, the rest rest on the floor indoors and slowly heal. A town hall standing in an older world is rebuilt into the inn of the same stage. The colony picks a head (highest total skill, then the oldest member); from stage 3 the head moves into the private room.

Chests and barrels inside the inn become the treasury: emeralds, diamonds, gold, amethyst, enchanted books and other valuables go there, and the stock, market and trader still see them.

### Wall

After two days the colony walls in all its buildings, farms, pens and the storage: cobblestone, planks and a fence rail, log posts, 3x3 towers every 40 columns, doors where paths cross the wall, lanterns at the gates. Where the ground outside is higher than the wall base, the wall rises with it. When the wall is redrawn, the old line is taken down for its materials.

### Alarm, skills, festivals

An enemy inside the village or next to any building rings the bell and guards come out. Villagers within the colony are not handed to vanilla AI: each looks for a closed room in a colony building (an enclosed space reachable only through a door; their own home first, crowded rooms and rooms with an enemy at the entrance are skipped).

The route avoids enemies: if the path passes next to a monster the villager picks another room, and with an enemy at arm's length or no room left it runs away from the enemies towards the village or stays by the nearest guard. A villager already standing in a closed room stays put.

Danger is felt without an alarm too: a hit, a monster within 5 blocks, a pursuer within 10, an archer aiming at the villager within 16, a creeper within 7. Sleepers are not woken, miners deep underground ignore the alarm. Four seconds after no enemy is near, everyone goes back to work.

A door keeper closes colony doors (buildings and wall gates): at night and during an alarm half a second after the doorway is clear, by day after 3 seconds, never with a player nearby (stats `doors_closed`, `doors_closed_night`, `refuge_*`). Guards first strike monsters that are hunting villagers. Villagers gain role experience (levels 1-5) that speeds digging and building and adds guard damage.

Every third day at noon the colony holds a festival at the bell. Benches stand around the bell and trees along the roads.

### Tavern, innkeeper and travellers

At about 10 adults, once food per head is stable, the colony plans a two-floor tavern (`tavern`): a common room with tables and stools, a counter with barrels, a smoker and a crafting table, a lit fireplace with a chimney, and guest cots (hay) upstairs. The cots are not beds, so they never count as colony housing.

With a tavern one worker becomes the **innkeeper** (taken from the largest of lumberjacks, miners, fishermen, herders or farmers): he works the counter during the shift and through the evening, and cooks bread and meals from storage when no cook works.

After the shift colonists eat once a day at the tavern (one food item from storage, only while the storage is not nearly empty) and get a short regeneration and a small work-speed bonus (+8%) until about the middle of the next shift. The festival is held on the square in front of the tavern instead of at the bell.

Travellers: about once a day the tavern may attract one or two wandering villagers (the only thing the colony creates for free, like the trader). They arrive at the colony edge, walk to the tavern, get a meal and stay a few minutes.

They then settle as colonists only if there is still a free bed, food per head is enough (60 plus 30 per villager) and at most one villager died over the last three to six days; otherwise they move on and vanish. Without a tavern no traveller comes.

The old migration from real neighbouring villages stays as a relief wave: only when the colony has dropped below 4 members or 60% of its peak, has free beds and full stores. Overview, journal and the colony screen show meals served, travellers and settlers.

### Demography

Housing demand follows beds versus population. With free beds and food in stock the colony feeds villagers bread and children are born; children grow up and get jobs.

### Roads and lights

The colony lays out a path network by itself, including for the houses a village already had: doors (found by scanning the surroundings a few chunks at a time), the storage, the mine or quarry entrance, fields, the pen and wall gates are all joined to the village centre (the bell) through existing paths or directly.

Routes follow the terrain (a node-bounded Dijkstra search): they avoid water, buildings, farmland, the quarry pit and steps higher than one block, and a one-block rise is walked over a half slab (cobblestone slab). A 3-wide main street leads from the centre to the main gates (or to the storage and mine while there is no wall).

Paths are made with a shovel from grass and dirt at no material cost, sand and gravel get cobblestone, and snow is cleared off the paths. Street edges get a little gravel when there is surplus; in the iron age the main street is paved with cobblestone and stone bricks.

Lamps: in the stone age a cobblestone wall, two fences and a torch in a lantern box of four open trapdoors under a wooden slab (older posts get the box added; extra posts away from roads standing closer than 9 blocks to a neighbour are taken down), in the iron age a stone brick wall post with a fence and a lantern hanging from an arm (a lantern is crafted from a torch and iron nuggets).

Posts stand beside the paths, not on them, not on a door apron or a field, at least 7 blocks apart, are built bottom-up and paid from storage. Old torches stuck in the ground are replaced gradually: the new post goes up beside the old light first and only then is the old one removed, so there is no gap in the lighting; stone posts are upgraded to lantern posts after the iron age begins.

### Fire, lava and rain

Colony villagers path around fire, lit campfires, magma and lava (cells beside them are very costly) and refuse targets on or next to them; miners cobble over lava next to their cell and skip cells they cannot seal.

A villager that burns or stands in or beside a hazard drops its task, runs to the nearest safe spot (or to water within 6 blocks when ablaze) and rests for a few seconds (stats `hazard_escapes`, `lava_avoided`).

When it rains, outdoor workers and idle villagers walk to the nearest covered spot within 24 blocks (inside colony buildings first, spread over several cells) and wait until the rain ends; miners, fishermen, guards and medics keep working, but a thunderstorm sends everybody indoors except alarmed guards.

Config: `shelterInRain` (default true) and `rainWorkRatio` (0 to 1, share of villagers who keep working in light rain).

### Guards

**Guards** take a sword, bow, arrows and armour from storage (the colony crafts what is missing), patrol the edge and the mine entrance by day and hold the most dangerous directions at night (the colony remembers where mobs come from).

In combat they only shoot when no villager, golem or peaceful player is in the line of fire, back away from charging enemies, retreat to the storage to heal with food below 35% health, drop unreachable targets, do not chase far beyond the colony and pick up loot afterwards.

### School

After the kindergarten (or at about 18 adults) the colony builds a school. Small children stay in the kindergarten; the older half of childhood spends the working day in the classroom, earning study points in the profession the colony needs most (spread across pupils). One teacher (a vanilla librarian is preferred) speeds the lessons up.

A grown pupil starts with a skill level (at most 3) in the profession studied and the role allocator prefers that role for them.

### Barracks

With about four guards, or two guards and a high recent threat, the colony builds barracks: four cots (they are not beds and are not homes), tables, a barrel armoury, two practice dummies and an archery lane with hay targets. With three or more guards the shifts rotate: night-shift guards rest at a cot by day, day-shift guards rest at night.

Idle day guards alternate between patrol and practice (sword swings at the dummy, arrows at the lane with the arrows picked up again afterwards); practice gives guard experience only up to level 3. Practice stops at once during an alarm or high threat. Higher guard levels shorten shot and strike cooldowns and tighten the aim.

### Golems

One iron golem per 6 adults (up to 4): a new one costs 20 ingots, wounded ones are repaired with an ingot each.

### Internal market

Surpluses (e.g. carrots above 64) are sold for emeralds through the real trades of the colony's villagers; emeralds buy what the colony lacks (tools, arrows, beds) through real offers too, respecting their stock limits.

### Theft and reputation

While a player has a storage barrel open the colony tracks its contents. Taking without paying is theft: reputation drops by the value taken (prices in emeralds, adjusted for scarcity). Leaving emeralds worth the goods is a purchase; leaving more is a donation and raises reputation. Breaking a storage barrel counts as theft. Hitting a villager costs 8, killing one 60.

Statuses: ally, guest, suspect, enemy of the colony. Enemies are attacked by guards and golems (villagers also get vanilla gossip, so vanilla golems react). Reputation slowly recovers on its own.

### AI advisor (optional)

A local model through Ollama or an OpenAI-compatible API looks at the colony every few minutes and may fix at most three roles when there is a clear imbalance, order a building, crafting or a rescue of a stuck villager. Off by default (`aiEnabled: false`); the colony works the same without it.

### Piers and houses on water

Shallow water (up to 4 blocks deep) counts as building ground: roads cross it as a plank walkway on log piles (a post every 3 blocks down to the seabed, fence railings on the sides, open ends, a lantern or torch roughly every 8 blocks), and houses, the dock and the fisher hut stand on a piled platform. Land is preferred while a suitable site on it is not too far.

Everything is paid for with planks, logs and fences from storage. The dock and the fisher hut are planned in seaside villages; fishermen fish from the dock. String for the rod: guards pick it up from spiders, the colony does not let bow crafting take the last two strings, and the trader gets a string order while there is no rod.

### Villager safety

Workers who see a monster are briefly handed back to vanilla behaviour (they flee); drowning workers are pushed to land; stuck workers far from the player are moved into place.

### Raids

During a pillager raid the bell rings, bedless villagers shelter in the inn, the rest hide at home, guards hold the gates, towers and inn door, and wounded guards drink healing potions. Afterwards the colony counts its losses and queues repairs for every building.

### Fires

The colony checks its buildings and storage; the nearest villagers run to put out fire (with a water bucket in storage they clear 5×5 at once).

### Chores and snow

Free villagers and workers whose own job is idle pick up dropped items, clear paths and doorways of grass, saplings and cobwebs, and in snowy biomes sweep snow from roads, doorways and storage; builders without a site also clear snow.

### Daily routine

Midday lunch (the colony spends food, work is 10% faster afterwards), an evening gathering at the inn or bell, and a day off every 7 days with a get-together at the chapel, market or tavern.

### Decrees

Colony focus (food, defense, building, resources), no-build zones where the colony does not build, plant or dig, and player orders: the colony gathers or crafts items and hands them over at the inn or storage.

### Clearance

A new building never sits in front of another door, and its own door never faces a wall, fence, pen or village wall; fields, lamps and planting keep doorways clear.

### House upgrades

Small, family and yard houses are rebuilt in stone with extra beds (2, 4 and 3).

### New workshops and roles

Masonry workshop (glass, bricks, stone bricks, stonecutter; the mason digs sand and clay), apiary and beekeeper (honey, honeycomb, moving bees from wild nests), apothecary and alchemist (real potions in a brewing stand, bandages and remedies for the infirmary), stable (the herder brings horses, donkeys and llamas, porters take pack animals), library and scribe (sugar cane, paper, books, a colony chronicle on the lectern, tool enchanting).

### Scouting

Scouts travel in all directions, mark ore veins, villages and bee nests, mine exposed veins and bring settlers from other villages.

Rich finds (iron, gold, lapis, diamonds and emeralds in sizeable clusters) are put to use: with two or more miners one of them (two from five miners) becomes a prospector who walks to the vein by day, mines the ore around it and brings it to the storage; when the ore cannot be reached on foot he digs a staircase passage to it (up to 24 steps, 3 blocks high, spiralling down when the ore lies right below), plugs water and lava with cobblestone and places torches.

Worked-out or unreachable veins are marked and skipped; while such finds exist the colony keeps at least two miners.

### Caravans

Colonies with a market or inn trade surpluses once a day; a real carrier walks the goods over (when nobody can go, the caravan travels off-screen and arrives after a while). No caravans run during war or hostility.

### Roads between colonies

Neighbours with a trade pact, an alliance or plain friendship (up to 640 blocks apart) build a road: surveyors trace it over the terrain around water, cliffs, trees and other buildings, crossing shallow water on boardwalks. Each colony builds its half from the gate facing the neighbour (or the village edge); builders take it on when the village has nothing urgent, and only in loaded chunks.

Long roads (200 blocks or more) get a roadside camp with a campfire and a shelter about every 160 blocks, and a signpost with the neighbour's name and the distance stands at the start. Travellers, envoys and caravans walk the finished road and spend the night at the camps. Progress shows in the Neighbours tab. Turn it off with `highways: false`.

### More buildings, bigger villages

A well, a windmill (with four fields), a granary (large storage), a row house with 4 beds for big villages, a trading post (a colony with a pact sends caravans even without a market) and an embassy (where neighbours' envoys come). The colony radius grows up to 112 blocks (`maxColonyRadius`, at most 192) but never past half the distance to a neighbour: sites are only searched on the colony's own side of the border.

### Neighbours and diplomacy

Colonies within 640 blocks meet and keep track of how they feel about each other (-100 to +100, each side has its own opinion). Every colony has a character, peaceful, merchant or proud, which a decree can override with Peaceful or Warlike. Each day the opinion moves with how close the border is, disputed ore, treaties and random incidents (a wedding, songs by the fire, trampled crops, a border quarrel).

The colony decides on its own: an envoy carries gifts from storage and proposals of a trade pact, alliance or truce, guests go to the neighbours' festival, and guards march to help an ally under attack.

When grudges pile up a proud colony declares war: a party of 2-4 armed guards (one always stays home) walks to the enemy storehouse, fights its guards and carries off loot; the victim rings the bell, villagers take cover, golems and guards strike the raiders, wounded raiders fall back. Losses and raids wear the sides down until they make peace; a truce lasts 3 days.

Villagers on the road carry a name ("Envoy of ...", "Raider of ..."). The Neighbours tab of the colony window shows both opinions, the reasons, history, parties on the road and buttons. `colonyWars: false` turns wars off, `diplomacy: false` turns all of it off.

### Settings summary

`config/smartcolony.json` (created on first launch): auto-founding, minimum villagers, colony radius and its maximum, shift start and end, role names above villagers, chat messages, moving stuck villagers, build speed, mine target depth, forest density and radius, golem price and count, animal cap, market period, guard retreat threshold, "enough wood" threshold, guards attacking enemy players.

AI advisor: `aiEnabled`, `aiApi` (`ollama` or `openai`), `aiEndpoint` (default `http://127.0.0.1:11434`), `aiModel` (default `gemma4:latest`), `aiApiKey` (OpenAI-compatible API only), `aiLanguage`, `aiIntervalTicks`, `aiTimeoutSeconds`, permissions `aiCanAssignRoles`, `aiCanBuild`, `aiCanCraft`, `aiCanRescue`. Colony data lives in the world: `<world>/smartcolony/colonies.json` (with a `.bak` copy).

### MCP bridge for development

A separate source set `src/devbridge` holds the debug mod `smartcolony_devbridge`: an MCP server at `http://127.0.0.1:25599/mcp` with the tools `status`, `brief`, `colony`, `command`, `wait`, `blocks`, `topmap`, `log`, `found`, `role`, `give`, `build`, `entities`, `site`, `rooms`, `wall`, `paste`, `ai`. It is not part of the release jar.

Run it with `.\gradlew.bat runDevServer` (headless server in `run-dev/`) or `.\gradlew.bat runDevClient`. To remove the bridge entirely: delete `src/devbridge`, `.mcp.json`, the `sourceSets { devbridge … }` block, the `"smartcolony_devbridge" { … }` entry in `loom.mods` and both `devServer`/`devClient` entries in `loom.runs` of `build.gradle`.
