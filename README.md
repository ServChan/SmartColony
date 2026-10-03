# Smart Colony

[![Minecraft Version](https://img.shields.io/badge/Minecraft-26.3-brightgreen?style=flat-square&logo=minecraft)](README.md)
[![Platform](https://img.shields.io/badge/Platform-Fabric-blue?style=flat-square&logo=fabric)](README.md)
[![Java Target](https://img.shields.io/badge/Java-25-orange?style=flat-square&logo=openjdk)](README.md)
[![Mod Version](https://img.shields.io/badge/Version-1.0.0-purple?style=flat-square)](README.md)
[![License](https://img.shields.io/badge/License-MIT-yellow?style=flat-square)](LICENSE)

Turns a vanilla village into a self-managing colony: categorized storage, lumberjacks, miners, farmers, fishermen, herders, builders, roads, torches, guards, golems, an internal market and theft detection. Singleplayer.

## Русский

### Что это

`Smart Colony` (mod id `smartcolony`) — Fabric-мод для Minecraft 26.3, рассчитанный на одиночную игру. Обычная деревня превращается в колонию, которая живёт сама: жители получают профессии, добывают ресурсы, складывают их на общий склад, строят дома, прокладывают дороги, ставят фонари и защищаются. Любое действие колонии оплачивается реальными предметами со склада — ничего не появляется из воздуха (кроме стартового склада при основании).

### Возможности

- **Основание.** Когда игрок рядом с колоколом деревни и вокруг есть хотя бы 3 взрослых жителя, колония основывается сама (или командой `/colony found`). У колокола строится склад: 5 столбиков бочек с табличками — Древесина, Недра, Амбар, Животноводство, Арсенал, плюс верстак и печь.
- **Склад по категориям.** Каждый предмет сортируется в свою категорию; если категория переполнена — в соседнюю. Когда склад заполняется на 75–90%, строители возводят складской навес на 6 бочек.
- **Роли.** Колония сама распределяет жителей по ролям исходя из запасов и нужд, учитывая их ванильную профессию (фермер → фермер, рыбак → рыбак, оружейник → стражник и т. д.). Роль держится минимум минуту, чтобы жители не метались. Рабочая смена — с 1000 до 10500 тиков дня; ночью и в свободное время жители живут по ванильным правилам (спят, торгуют, общаются).
- **Лесоруб** отличает природные деревья от построек (по непостоянной листве и отсутствию досок, стекла, дверей рядом), спиливает ствол целиком снизу вверх, срезает только осиротевшую листву, собирает брёвна, саженцы и яблоки, сажает саженец на пень и подсаживает лес, если он редеет. Когда дерева на складе слишком много, лесорубы отдыхают.
- **Шахтёр** берёт кирку и факелы, выбирает место для шахты, роет лестницу вниз (3 блока высоты, чтобы можно было подняться), затем главный штрек с боковыми ответвлениями; выкапывает рудные жилы целиком, ставит факелы в темноте, закладывает булыжником воду и лаву, подкладывает пол над пустотами, возвращается по сохранённым точкам маршрута. Затопленные участки пропускает, выработанную шахту бросает и закладывает новую в другом направлении.
- **Фермер** собирает спелый урожай и тут же пересаживает, засевает пустую пашню, вспахивает новую землю у воды, когда еды мало, находит новые водоёмы под поля, а если воды рядом нет — приносит её ведром и делает пруд-оросительный. Без семян рвёт траву ради семян.
- **Рыбак** ищет открытую воду, удит по ванильной таблице улова; часть сырой рыбы и мяса колония запекает (печь «виртуальная», топливо — уголь/древесный уголь/доски/брёвна со склада).
- **Пастух** заводит загон (его строит строитель), ловит диких коров, овец, свиней и кур на поводок и заводит внутрь, кормит пары для размножения, стрижёт овец, собирает яйца и шерсть, держит лимит поголовья (по умолчанию 6 на вид) и при нехватке мяса забивает лишних. Животных из чужих загонов и в воде не трогает.
- **Строитель** строит по чертежам (малый и семейный дом, складской навес, загон, пост стражи), выбирая ровное место на краю колонии, к которому можно подойти от входа. Стройка начинается только когда на складе есть 100% материалов (недостающее колония сначала крафтит: доски, двери, полублоки, кровати, факелы, стёкла). Если чего-то нет — показывает, чего не хватает, и пока строит то, на что материалов хватает. Площадку расчищает, подсыпает фундамент из булыжника (не больше 3 блоков, чтобы дом не висел над обрывом), ставит блоки слоями, двери и кровати целиком. Окна без стекла заменяются заборами, кровать нужного цвета — кроватью из имеющейся шерсти.
- **Демография.** Нужда в домах считается по кроватям и числу жителей. При свободных кроватях и запасе еды колония подкармливает жителей хлебом и у них рождаются дети; дети вырастают и тоже получают работу.
- **Дороги и фонари.** От двери каждого нового здания прокладывается тропа (A*-поиск по поверхности) к ближайшей дороге или складу; тёмные места у дорог и дверей получают столбик-забор с факелом.
- **Стражники** берут меч, лук, стрелы и броню со склада (колония крафтит недостающее), днём патрулируют периметр и вход в шахту, ночью стоят на самых опасных направлениях (колония запоминает, откуда приходят мобы). В бою: стреляют, только если на линии огня нет жителя, голема или мирного игрока, отходят от подбегающих врагов, при здоровье ниже 35% уходят к складу лечиться едой. Недостижимые цели бросают, за границу колонии далеко не гонятся, после боя собирают лут.
- **Големы.** На каждые 6 взрослых колония держит железного голема (до 4): выковывает нового за 20 слитков и чинит раненых по слитку.
- **Внутренний рынок.** Излишки (например, морковь сверх 64) продаются по настоящим сделкам жителей колонии за изумруды; изумруды тратятся на то, чего колонии не хватает (инструменты, стрелы, кровати), — тоже через реальные предложения жителей, с учётом их лимитов.
- **Кражи и репутация.** Колония сверяет содержимое склада, пока игрок держит его открытым. Взял без оплаты — это кража: репутация падает пропорционально стоимости (цены — в изумрудах, с поправкой на дефицит). Положил изумруды на ту же сумму — это покупка; положил лишнее — пожертвование и плюс к репутации. Сломанная бочка склада — тоже кража. Нападение на жителя — минус 8, убийство — минус 60. Статусы: союзник, гость, нарушитель, враг колонии. Враг — стражи и големы атакуют его (жители получают ванильные «сплетни», поэтому и ванильные големы реагируют). Репутация понемногу восстанавливается сама.
- **Безопасность жителей.** Рабочие при виде монстра на время отпускаются к ванильному поведению (убегают); тонущих выталкивает на сушу; застрявших далеко от игрока колония переставляет на место.

### Управление

Клавиш нет — всё через команду `/colony`:

- `/colony` или `/colony info` — жители по ролям, заполнение складов, запас еды, стройка и чего не хватает, шахта, ваша репутация;
- `/colony found` — основать колонию у ближайшего колокола;
- `/colony stock` — содержимое складов по категориям;
- `/colony journal` — последние события колонии;
- `/colony market` — последние сделки рынка и список нужд;
- `/colony reputation` — ваша репутация у колонии;
- `/colony build <чертёж>` — заказать постройку (`house_small`, `house_family`, `storage_shed`, `guard_post`, `pen`);
- `/colony role <жители> <роль|auto>` — закрепить роль (`lumberjack`, `miner`, `farmer`, `fisherman`, `herder`, `builder`, `guard`, `idle`) или вернуть автоматический выбор;
- `/colony disband` — распустить колонию (жители возвращаются к ванильному поведению, постройки остаются).

### Настройки

Файл `config/smartcolony.json` (создаётся при первом запуске): авто-основание, минимум жителей, радиус колонии и его максимум, начало и конец смены, подписи ролей над жителями, сообщения в чат, разрешение переставлять застрявших жителей, скорость стройки, целевая глубина шахты, плотность и радиус леса, цена и число големов, лимит животных, период рынка, порог отступления стражи, порог «дерева достаточно», атака стражей на врагов-игроков. Данные колоний хранятся в мире: `<мир>/smartcolony/colonies.json` (с резервной копией `.bak`).

### Установка

Положите `smart-colony-1.0.0.jar` в папку `mods` профиля Minecraft 26.3 с Fabric Loader и Fabric API.

**Требования:** Minecraft 26.3, Fabric Loader 0.19.3+, Fabric API, Java 25.

### Сборка

```
.\gradlew.bat clean build
```

Готовый файл: `build/libs/smart-colony-1.0.0.jar`.

### Для разработки: MCP-мост

В отдельном source set `src/devbridge` лежит отладочный мод `smartcolony_devbridge`: MCP-сервер на `http://127.0.0.1:25599/mcp` с инструментами `status`, `brief`, `colony`, `command`, `wait`, `blocks`, `topmap`, `log`, `found`, `role`, `give`, `build`, `entities`. Он не попадает в релизный jar. Запуск: `.\gradlew.bat runDevServer` (безоконный сервер в `run-dev/`) или `.\gradlew.bat runDevClient`. Чтобы удалить мост целиком: удалите папку `src/devbridge`, файл `.mcp.json`, блок `sourceSets { devbridge … }`, строку `"smartcolony_devbridge" { … }` в `loom.mods` и обе записи `devServer`/`devClient` в `loom.runs` из `build.gradle`.

---

## English

### What It Is

`Smart Colony` (mod id `smartcolony`) is a Fabric mod for Minecraft 26.3 meant for singleplayer. It turns an ordinary village into a colony that runs itself: villagers take jobs, gather resources into a shared storage, build houses, lay roads, put up street lights and defend themselves. Every colony action is paid for with real items from the storage — nothing is spawned except the starter storage when the colony is founded.

### Features

- **Founding.** When a player is near a village bell with at least 3 adult villagers around, the colony founds itself (or use `/colony found`). A storage is built next to the bell: 5 columns of barrels with signs — Timber, Mine, Granary, Livestock, Arsenal — plus a crafting table and a furnace.
- **Categorized storage.** Each item goes to its category, overflowing into the others. At 75–90% fill builders add a storage shed with 6 more barrels.
- **Roles.** The colony assigns jobs from its stock and needs, preferring each villager's vanilla profession (farmer → farmer, fisherman → fisherman, weaponsmith → guard, …). A role is kept for at least a minute. The work shift is day ticks 1000–10500; at night and in free time villagers follow vanilla behaviour (sleep, trade, socialise).
- **Lumberjack** tells natural trees from buildings (non-persistent leaves, no planks/glass/doors nearby), fells the whole trunk bottom-up, clears only the orphaned leaves, collects logs, saplings and apples, replants the stump and plants more saplings when the forest thins. When the storage holds enough wood, lumberjacks rest.
- **Miner** takes a pickaxe and torches, picks a mine site, digs a 3-high staircase down, then a main drift with side branches; mines whole ore veins, lights dark spots, seals water and lava with cobblestone, patches the floor over gaps, and walks back along stored waypoints. Flooded sections are skipped; a worked-out mine is abandoned for a new one in another direction.
- **Farmer** harvests ripe crops and replants at once, sows empty farmland, tills new land by water when food is short, finds new water for fields and, without water nearby, carries a bucket to dig an irrigation pond. Without seeds, breaks grass for seeds.
- **Fisherman** finds open water and fishes using the vanilla fishing loot table; the colony cooks part of the raw fish and meat (fuel: coal, charcoal, planks or logs from storage).
- **Herder** gets a pen (built by a builder), leashes wild cows, sheep, pigs and chickens and leads them in, breeds pairs, shears sheep, gathers eggs and wool, keeps a population cap (6 per species by default) and culls extras when meat runs low. Animals in other pens or in water are left alone.
- **Builder** builds from blueprints (small and family house, storage shed, animal pen, guard post) on flat land at the colony edge that can be reached from the entrance. Construction starts only with 100% of the materials in storage (the colony crafts what it can first: planks, doors, slabs, beds, torches, panes). Missing items are shown, and meanwhile the colony builds whatever it can afford. The site is cleared, a cobblestone foundation fills gaps (at most 3 blocks so nothing hangs over a cliff), blocks go up layer by layer, doors and beds as pairs. Windows without glass become fences; a bed of the wanted colour is replaced by one made of available wool.
- **Demography.** Housing demand follows beds versus population. With free beds and food in stock the colony feeds villagers bread and children are born; children grow up and get jobs.
- **Roads and lights.** Every new building gets a path (A* over the surface) from its door to the nearest road or the storage; dark spots by roads and doors get a fence post with a torch.
- **Guards** take a sword, bow, arrows and armour from storage (the colony crafts what is missing), patrol the edge and the mine entrance by day and hold the most dangerous directions at night (the colony remembers where mobs come from). In combat they only shoot when no villager, golem or peaceful player is in the line of fire, back away from charging enemies, retreat to the storage to heal with food below 35% health, drop unreachable targets, do not chase far beyond the colony and pick up loot afterwards.
- **Golems.** One iron golem per 6 adults (up to 4): a new one costs 20 ingots, wounded ones are repaired with an ingot each.
- **Internal market.** Surpluses (e.g. carrots above 64) are sold for emeralds through the real trades of the colony's villagers; emeralds buy what the colony lacks (tools, arrows, beds) through real offers too, respecting their stock limits.
- **Theft and reputation.** While a player has a storage barrel open the colony tracks its contents. Taking without paying is theft: reputation drops by the value taken (prices in emeralds, adjusted for scarcity). Leaving emeralds worth the goods is a purchase; leaving more is a donation and raises reputation. Breaking a storage barrel counts as theft. Hitting a villager costs 8, killing one 60. Statuses: ally, guest, suspect, enemy of the colony. Enemies are attacked by guards and golems (villagers also get vanilla gossip, so vanilla golems react). Reputation slowly recovers on its own.
- **Villager safety.** Workers who see a monster are briefly handed back to vanilla behaviour (they flee); drowning workers are pushed to land; stuck workers far from the player are moved into place.

### Controls

No key bindings — everything goes through `/colony`:

- `/colony` or `/colony info` — members by role, storage fill, food, construction and what it lacks, mine, your reputation;
- `/colony found` — found a colony at the nearest bell;
- `/colony stock` — storage contents by category;
- `/colony journal` — recent colony events;
- `/colony market` — recent market deals and current needs;
- `/colony reputation` — your reputation with the colony;
- `/colony build <blueprint>` — order a building (`house_small`, `house_family`, `storage_shed`, `guard_post`, `pen`);
- `/colony role <villagers> <role|auto>` — lock a role (`lumberjack`, `miner`, `farmer`, `fisherman`, `herder`, `builder`, `guard`, `idle`) or return to automatic choice;
- `/colony disband` — disband the colony (villagers return to vanilla behaviour, buildings stay).

### Configuration

`config/smartcolony.json` (created on first launch): auto-founding, minimum villagers, colony radius and its maximum, shift start and end, role names above villagers, chat messages, moving stuck villagers, build speed, mine target depth, forest density and radius, golem price and count, animal cap, market period, guard retreat threshold, "enough wood" threshold, guards attacking enemy players. Colony data lives in the world: `<world>/smartcolony/colonies.json` (with a `.bak` copy).

### Installation

Put `smart-colony-1.0.0.jar` into the `mods` folder of a Minecraft 26.3 profile with Fabric Loader and Fabric API.

**Requirements:** Minecraft 26.3, Fabric Loader 0.19.3+, Fabric API, Java 25.

### Building

```
.\gradlew.bat clean build
```

Output: `build/libs/smart-colony-1.0.0.jar`.

### Development: MCP bridge

A separate source set `src/devbridge` holds the debug mod `smartcolony_devbridge`: an MCP server at `http://127.0.0.1:25599/mcp` with the tools `status`, `brief`, `colony`, `command`, `wait`, `blocks`, `topmap`, `log`, `found`, `role`, `give`, `build`, `entities`. It is not part of the release jar. Run it with `.\gradlew.bat runDevServer` (headless server in `run-dev/`) or `.\gradlew.bat runDevClient`. To remove the bridge entirely: delete `src/devbridge`, `.mcp.json`, the `sourceSets { devbridge … }` block, the `"smartcolony_devbridge" { … }` entry in `loom.mods` and both `devServer`/`devClient` entries in `loom.runs` of `build.gradle`.

## Лицензия / License

MIT — см. [LICENSE](LICENSE).
