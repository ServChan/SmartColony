package org.lts.smartcolony.colony;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.Category;
import org.lts.smartcolony.core.ColonyCodec;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.CraftPlanner;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Pier;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.Prospecting;
import org.lts.smartcolony.core.Reputation;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.RoleAllocator;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.core.StringSupply;
import org.lts.smartcolony.core.TerrainMap;
import org.lts.smartcolony.util.ItemIds;
import org.lts.smartcolony.util.WorldUtil;
import org.lts.smartcolony.work.Colonist;
import org.lts.smartcolony.work.ForestIndex;
import org.lts.smartcolony.work.Inv;
import org.lts.smartcolony.work.Jobs;

public final class Colony {
    private final ColonyData data;
    private final Warehouse warehouse;
    private final ForestIndex forest;
    private final Sites sites;
    private final Fields fields;
    private final TreeFarm treeFarm;
    private final Economy economy;
    private final Defense defense;
    private final Construction construction;
    private final Migration migration;
    private final Tavern tavern;
    private final Hall hall;
    private final Improvements improvements;
    private final Survey survey;
    private final StorageRecovery recovery;
    private final Outposts outposts;
    private boolean festivalAnnounced;
    private final TheftWatcher theft;
    private final Trader trader;
    private final Graveyard graveyard;
    private final org.lts.smartcolony.ai.Advisor advisor;
    private final Map<UUID, Colonist> colonists = new LinkedHashMap<>();
    private final Set<String> requests = new LinkedHashSet<>();
    private final Set<UUID> ownedNames = new java.util.HashSet<>();
    private EnumMap<Role, Integer> desired = new EnumMap<>(Role.class);
    private final IronAge ironAge;
    private final Ward ward;
    private final Daily daily;
    private final FireWatch fire;
    private final Chores chores;
    private final Overgrowth overgrowth;
    private final Doors doors;
    private final SafeRooms safeRooms;
    private final Contracts contracts;
    private final Stable stable;
    private final Embassy embassy;
    private boolean dirty;
    private long ticks;
    private long lastStorageWarning;

    public Colony(ColonyData data) {
        this.data = data;
        this.warehouse = new Warehouse(data);
        this.forest = new ForestIndex(this);
        this.sites = new Sites(this);
        this.fields = new Fields(this);
        this.treeFarm = new TreeFarm(this);
        this.economy = new Economy(this);
        this.defense = new Defense(this);
        this.survey = new Survey(this);
        this.recovery = new StorageRecovery(this);
        this.outposts = new Outposts(this);
        this.construction = new Construction(this);
        this.migration = new Migration(this);
        this.tavern = new Tavern(this);
        this.hall = new Hall(this);
        this.improvements = new Improvements(this);
        this.theft = new TheftWatcher(this);
        this.trader = new Trader(this);
        this.graveyard = new Graveyard(this);
        this.advisor = new org.lts.smartcolony.ai.Advisor(this);
        this.ironAge = new IronAge(this);
        this.ward = new Ward(this);
        this.daily = new Daily(this);
        this.fire = new FireWatch(this);
        this.chores = new Chores(this);
        this.overgrowth = new Overgrowth(this);
        this.doors = new Doors(this);
        this.safeRooms = new SafeRooms(this);
        this.contracts = new Contracts(this);
        this.stable = new Stable(this);
        this.embassy = new Embassy(this);
        for (Map.Entry<String, ColonyData.Member> entry : data.members.entrySet()) {
            UUID uuid = UUID.fromString(entry.getKey());
            Colonist colonist = new Colonist(uuid, this);
            colonist.setRole(null, null, entry.getValue().role());
            colonists.put(uuid, colonist);
            if (entry.getValue().named) {
                ownedNames.add(uuid);
            }
        }
    }

    public ColonyData data() {
        return data;
    }

    public String id() {
        return data.id;
    }

    public Warehouse warehouse() {
        return warehouse;
    }

    public ForestIndex forest() {
        return forest;
    }

    public Fields fields() {
        return fields;
    }

    public Sites sites() {
        return sites;
    }

    public TreeFarm treeFarm() {
        return treeFarm;
    }

    public Economy economy() {
        return economy;
    }

    public Doors doors() {
        return doors;
    }

    public SafeRooms safeRooms() {
        return safeRooms;
    }

    public Defense defense() {
        return defense;
    }

    public Improvements improvements() {
        return improvements;
    }

    public Survey survey() {
        return survey;
    }

    public StorageRecovery recovery() {
        return recovery;
    }

    public Outposts outposts() {
        return outposts;
    }

    public Migration migration() {
        return migration;
    }

    public Hall hall() {
        return hall;
    }

    public Tavern tavern() {
        return tavern;
    }

    public Construction construction() {
        return construction;
    }

    public org.lts.smartcolony.ai.Advisor advisor() {
        return advisor;
    }

    public Graveyard graveyard() {
        return graveyard;
    }

    public Ward ward() {
        return ward;
    }

    public Daily daily() {
        return daily;
    }

    public FireWatch fire() {
        return fire;
    }

    public Chores chores() {
        return chores;
    }

    public Contracts contracts() {
        return contracts;
    }

    public Stable stable() {
        return stable;
    }

    public Embassy embassy() {
        return embassy;
    }

    public boolean builtKind(String kind) {
        for (ColonyData.Building building : data.buildings) {
            if (kind.equals(building.kind)) {
                return true;
            }
        }
        return false;
    }

    public ColonyData.Building buildingOfKind(String kind) {
        for (ColonyData.Building building : data.buildings) {
            if (kind.equals(building.kind)) {
                return building;
            }
        }
        return null;
    }

    public Trader trader() {
        return trader;
    }

    public TheftWatcher theft() {
        return theft;
    }

    public Map<UUID, Colonist> colonists() {
        return colonists;
    }

    public EnumMap<Role, Integer> desired() {
        return desired;
    }

    public BlockPos center() {
        return WorldUtil.bp(data.center);
    }

    public BlockPos depot() {
        return WorldUtil.bp(data.depot);
    }

    public BlockPos groundCenter() {
        return Math.abs(data.center.y() - data.depot.y()) > 12 ? depot() : center();
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean consumeDirty() {
        boolean was = dirty;
        dirty = false;
        return was;
    }

    public static int levelFor(int xp) {
        return Math.min(5, (int) Math.floor(Math.sqrt(xp / 12.0)));
    }

    public int skill(UUID uuid, Role role) {
        ColonyData.Member member = data.members.get(uuid.toString());
        return member == null ? 0 : levelFor(member.xp.getOrDefault(role.key(), 0));
    }

    public void gainXp(ServerLevel level, Villager villager, Role role, int amount) {
        ColonyData.Member member = data.members.get(villager.getUUID().toString());
        if (member == null || amount <= 0) {
            return;
        }
        int before = levelFor(member.xp.getOrDefault(role.key(), 0));
        int after = levelFor(member.xp.merge(role.key(), amount, Integer::sum));
        if (after > before) {
            journal(level, role.key() + " reached skill level " + after);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getY() + 2.0,
                    villager.getZ(), 12, 0.4, 0.4, 0.4, 0.0);
        }
        markDirty();
    }

    public boolean festival(ServerLevel level) {
        long time = level.getDefaultClockTime();
        long day = time / 24000L;
        long hour = Math.floorMod(time, 24000L);
        return day % 3 == 2 && hour >= 6000 && hour < 7800 && colonists.size() >= 4;
    }

    public boolean workTime(ServerLevel level) {
        return SmartColonyConfig.get().isWorkTime(level.getDefaultClockTime());
    }

    public boolean contains(BlockPos pos) {
        return pos.distSqr(center()) <= (double) data.radius * data.radius;
    }

    public boolean ownsName(UUID uuid) {
        return ownedNames.contains(uuid);
    }

    public void setOwnsName(UUID uuid, boolean owned) {
        if (owned) {
            ownedNames.add(uuid);
        } else {
            ownedNames.remove(uuid);
        }
        ColonyData.Member member = data.members.get(uuid.toString());
        if (member != null) {
            member.named = owned;
        }
    }

    public void request(String id) {
        requests.add(id);
    }

    public Set<String> requests() {
        return requests;
    }

    public void delivered(Colonist colonist, String id, int amount) {
        if (amount <= 0) {
            return;
        }
        ColonyData.Member member = data.members.get(colonist.uuid().toString());
        if (member != null) {
            member.delivered += amount;
        }
        data.bump("delivered", amount);
        markDirty();
    }

    public void storageFull() {
        data.bump("storage_full_events", 1);
    }

    public void journal(ServerLevel level, String line) {
        data.log(level.getGameTime(), line);
        markDirty();
    }

    public void notify(ServerLevel level, Component message) {
        if (!SmartColonyConfig.get().notifyPlayers) {
            return;
        }
        Component line = Component.translatable("smartcolony.chat.prefix", data.name).append(" ").append(message);
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(center()) < 128 * 128) {
                player.sendSystemMessage(line);
            }
        }
    }

    public int stockOf(ServerLevel level, Predicate<String> ids) {
        return warehouse.stock(level).sum(ids);
    }

    public boolean woodSurplus(ServerLevel level) {
        Stock stock = warehouse.stock(level);
        int wood = stock.sum(Ids::isLog) + stock.sum(Ids::isPlanks) / 4;
        return wood > SmartColonyConfig.get().woodSurplus;
    }

    public int foodPoints(ServerLevel level) {
        return economy.foodPoints(warehouse.stock(level));
    }

    public boolean wantsMoreFood(ServerLevel level) {
        int adults = adultCount(level);
        return foodPoints(level) < 120 + adults * 40;
    }

    public List<String> preferredSeeds(ServerLevel level, List<String> seeds) {
        Stock stock = warehouse.stock(level);
        List<String> ordered = new ArrayList<>(seeds);
        ordered.sort(java.util.Comparator.comparingInt(seed -> stock.get(produceOf(seed))));
        return ordered;
    }

    private static String produceOf(String seed) {
        return switch (Ids.path(seed)) {
            case "wheat_seeds" -> Ids.mc("wheat");
            case "beetroot_seeds" -> Ids.mc("beetroot");
            default -> seed;
        };
    }

    private long waterCheckedAt = -100000;
    private boolean waterVillage;

    public boolean waterVillage(ServerLevel level) {
        long now = level.getGameTime();
        if (now - waterCheckedAt >= 6000) {
            waterCheckedAt = now;
            TerrainMap map = survey.map();
            waterVillage = map != null && Pier.waterVillage(map.counts()[TerrainMap.Surface.WATER.ordinal()], map.columns());
        }
        return waterVillage;
    }

    public boolean craft(ServerLevel level, String id, int count) {
        Stock stock = warehouse.stock(level);
        if (id.equals(Ids.mc("bow")) && !StringSupply.bowAllowed(stock.get(Ids.mc("string")), waterVillage(level))) {
            return false;
        }
        var plan = CraftPlanner.plan(stock, id, count);
        if (plan.isEmpty()) {
            return false;
        }
        if (plan.get().trivial()) {
            return true;
        }
        boolean applied = warehouse.applyPlan(level, plan.get());
        if (applied) {
            for (CraftPlanner.Step step : plan.get().steps()) {
                data.bump(step.smelting() ? "smelted" : "crafted", step.amount());
            }
            markDirty();
        }
        return applied;
    }

    public boolean craftOrTake(ServerLevel level, Villager villager, String id) {
        if (Inv.count(villager, id) > 0) {
            return true;
        }
        if (warehouse.stock(level).get(id) == 0 && !craft(level, id, 1)) {
            request(id);
            return false;
        }
        if (warehouse.take(level, id, 1) == 1) {
            ItemStack rest = Inv.add(villager, ItemIds.stack(id, 1));
            if (!rest.isEmpty()) {
                warehouse.insert(level, rest);
                return false;
            }
            return true;
        }
        return false;
    }

    public void cook(ServerLevel level) {
        economy.cook(level);
    }

    public boolean isProtected(BlockPos pos) {
        return isProtected(pos, true);
    }

    public boolean isProtected(BlockPos pos, boolean includeFarms) {
        return isProtected(pos, includeFarms, null);
    }

    public boolean isProtected(BlockPos pos, boolean includeFarms, ColonyData.Project ignore) {
        Pos p = WorldUtil.pos(pos);
        if (p.horizontalDistSq(data.depot) < 36 || p.horizontalDistSq(data.center) < 16) {
            return true;
        }
        if (!data.zones.isEmpty() && org.lts.smartcolony.core.Decree.inAny(data.zones, p.x(), p.z(), 0)) {
            return true;
        }
        for (ColonyData.Building building : data.buildings) {
            if (building.contains(p, 2)) {
                return true;
            }
        }
        for (ColonyData.Project project : data.projects) {
            if (project != ignore && project.building.contains(p, 2)) {
                return true;
            }
        }
        if (data.pen != null && p.x() >= data.pen.min.x() - 2 && p.x() <= data.pen.max.x() + 2
                && p.z() >= data.pen.min.z() - 2 && p.z() <= data.pen.max.z() + 2) {
            return true;
        }
        for (Pos farm : includeFarms ? data.farms : List.<Pos>of()) {
            if (p.horizontalDistSq(farm) <= 36 && Math.abs(p.y() - farm.y()) < 4) {
                return true;
            }
        }
        if (improvements.isWall(p)) {
            return true;
        }
        for (ColonyData.Mine mine : allMines()) {
            if (p.horizontalDistSq(mine.entrance) < 9) {
                return true;
            }
            if (mine.quarry() && p.x() >= mine.corner.x() - 1 && p.z() >= mine.corner.z() - 1
                    && p.x() <= mine.corner.x() + mine.width + 2 && p.z() <= mine.corner.z() + mine.width) {
                return true;
            }
            Pos shed = mine.shed;
            if (shed != null && p.x() >= shed.x() - 1 && p.x() <= shed.x() + Improvements.SHED_LENGTH
                    && p.z() >= shed.z() - 1 && p.z() <= shed.z() + Improvements.SHED_WIDTH) {
                return true;
            }
        }
        return false;
    }

    public List<ColonyData.Mine> allMines() {
        if (data.mine == null) {
            return data.mines;
        }
        if (data.mines.isEmpty()) {
            return List.of(data.mine);
        }
        List<ColonyData.Mine> all = new ArrayList<>(data.mines.size() + 1);
        all.add(data.mine);
        all.addAll(data.mines);
        return all;
    }

    public boolean isProtectedAnimal(Animal animal) {
        if (animal.isLeashed() || animal.hasCustomName()) {
            return true;
        }
        if (data.pen == null) {
            return false;
        }
        return animal.getX() >= data.pen.min.x() && animal.getX() < data.pen.max.x() + 1
                && animal.getZ() >= data.pen.min.z() && animal.getZ() < data.pen.max.z() + 1;
    }

    public boolean treeAllowed(ServerLevel level, BlockPos pos) {
        if (treeFarm.contains(pos)) {
            return true;
        }
        return !isProtected(pos) && !fields.treeBlocked(level, pos.getX(), pos.getZ());
    }

    public boolean treeInTheWay(ServerLevel level, BlockPos base) {
        if (treeFarm.contains(base)) {
            return false;
        }
        Pos p = WorldUtil.pos(base);
        if (!data.zones.isEmpty() && org.lts.smartcolony.core.Decree.inAny(data.zones, p.x(), p.z(), 0)) {
            return false;
        }
        for (ColonyData.Building building : data.buildings) {
            if (building.contains(p, 0)) {
                return !building.blueprint.contains("garden") && !org.lts.smartcolony.core.TreeFarmPlan.KIND.equals(building.kind);
            }
        }
        for (ColonyData.Project project : data.projects) {
            if (project.building.contains(p, 2)) {
                return true;
            }
        }
        return fields.treeBlocked(level, base.getX(), base.getZ());
    }

    public void rememberPlanted(BlockPos pos) {
        data.plantedTrees.add(WorldUtil.pos(pos));
        while (data.plantedTrees.size() > 200) {
            data.plantedTrees.removeFirst();
        }
        markDirty();
    }

    public BlockPos findPlantingSpot(ServerLevel level, RandomSource random) {
        return sites.plantingSpot(level, random);
    }

    public ColonyData.Mine ensureMine(ServerLevel level) {
        if (data.mine != null && !data.mine.quarry() && data.mine.type == null) {
            ColonyData.Mine quarry = sites.mineSite(level, data.mine.generation + 1, data.mine);
            if (quarry != null && quarry.quarry()) {
                quarry.generation = data.mine.generation + 1;
                data.mine = quarry;
                journal(level, "stair mine replaced by a quarry at " + quarry.entrance);
                markDirty();
                return data.mine;
            }
            data.mine.type = "stair";
            markDirty();
        }
        if (data.mine == null) {
            data.mine = sites.mineSite(level, 0, null);
            if (data.mine != null) {
                journal(level, (data.mine.quarry() ? "quarry marked at " : "mine entrance marked at ") + data.mine.entrance);
                markDirty();
            }
        }
        return data.mine;
    }

    public void mineExhausted(ServerLevel level) {
        mineExhausted(level, data.mine);
    }

    public void mineExhausted(ServerLevel level, ColonyData.Mine old) {
        int index = old == null ? -1 : data.mines.indexOf(old);
        if (index >= 0) {
            ColonyData.Mine next = sites.mineSite(level, old.generation + 1, old, allMines(), null);
            if (next != null) {
                next.generation = old.generation + 1;
                data.mines.set(index, next);
                journal(level, "side mine worked out, new " + (next.quarry() ? "quarry" : "mine") + " at " + next.entrance);
            } else {
                data.mines.remove(index);
                journal(level, "side mine at " + old.entrance + " worked out, no place for another");
            }
            mineClaims.remove(old);
            minersPlannedAt = -100000;
            markDirty();
            return;
        }
        int generation = data.mine == null ? 1 : data.mine.generation + 1;
        ColonyData.Mine next = sites.mineSite(level, generation, data.mine, allMines(), null);
        if (next != null) {
            next.generation = generation;
            data.mine = next;
            journal(level, "old mine worked out, new " + (next.quarry() ? "quarry" : "mine") + " at " + next.entrance);
        }
        if (old != null) {
            mineClaims.remove(old);
        }
        minersPlannedAt = -100000;
        markDirty();
    }

    private final Map<UUID, Integer> minerPlan = new HashMap<>();
    private final Map<UUID, Pos> prospects = new HashMap<>();
    private long minersPlannedAt = -100000;
    private long sideMineRetry;

    public ColonyData.Mine mineFor(ServerLevel level, UUID miner) {
        planMiners(level);
        Integer slot = minerPlan.get(miner);
        List<ColonyData.Mine> mines = allMines();
        if (slot == null || slot < 0 || mines.isEmpty()) {
            return data.mine;
        }
        return mines.get(Math.min(slot, mines.size() - 1));
    }

    public boolean prospects(ServerLevel level, UUID miner) {
        planMiners(level);
        Integer slot = minerPlan.get(miner);
        return slot != null && slot == Prospecting.PROSPECTOR;
    }

    public ColonyData.Discovery prospectFor(ServerLevel level, UUID miner) {
        Pos held = prospects.get(miner);
        if (held != null) {
            ColonyData.Discovery discovery = discoveryAt(held);
            if (discovery != null && Prospecting.rich(discovery.kind, discovery.value, discovery.worked, discovery.dry)) {
                return discovery;
            }
            prospects.remove(miner);
        }
        List<Prospecting.Deposit> reachable = new ArrayList<>();
        for (Prospecting.Deposit deposit : deposits()) {
            if (level.isPositionEntityTicking(WorldUtil.bp(deposit.pos()))) {
                reachable.add(deposit);
            }
        }
        List<Pos> taken = new ArrayList<>();
        for (Map.Entry<UUID, Pos> entry : prospects.entrySet()) {
            if (!entry.getKey().equals(miner)) {
                taken.add(entry.getValue());
            }
        }
        Prospecting.Deposit pick = Prospecting.pick(reachable, taken);
        if (pick == null) {
            return null;
        }
        prospects.put(miner, pick.pos());
        return discoveryAt(pick.pos());
    }

    public void releaseProspect(UUID miner) {
        prospects.remove(miner);
    }

    private ColonyData.Discovery discoveryAt(Pos pos) {
        for (ColonyData.Discovery discovery : data.discoveries) {
            if (discovery.pos.equals(pos)) {
                return discovery;
            }
        }
        return null;
    }

    public List<Prospecting.Deposit> deposits() {
        List<Prospecting.Deposit> deposits = new ArrayList<>();
        for (ColonyData.Discovery discovery : data.discoveries) {
            if (!Prospecting.rich(discovery.kind, discovery.value, discovery.worked, discovery.dry)) {
                continue;
            }
            double distance = Math.sqrt(discovery.pos.horizontalDistSq(data.center));
            if (Prospecting.inRange(distance)) {
                deposits.add(new Prospecting.Deposit(discovery.pos, discovery.value, distance));
            }
        }
        return deposits;
    }

    private void planMiners(ServerLevel level) {
        long now = level.getGameTime();
        if (now - minersPlannedAt < 200) {
            return;
        }
        minersPlannedAt = now;
        List<String> miners = new ArrayList<>();
        for (Map.Entry<UUID, Colonist> entry : colonists.entrySet()) {
            if (entry.getValue().role() == Role.MINER) {
                miners.add(entry.getKey().toString());
            }
        }
        List<Prospecting.Deposit> deposits = deposits();
        int prospectors = Prospecting.prospectors(miners.size(), deposits.size());
        int wanted = Prospecting.mines(miners.size() - prospectors);
        if (data.mine != null) {
            openSideMine(level, wanted, deposits);
        }
        int mines = Math.max(1, Math.min(wanted, allMines().size()));
        minerPlan.clear();
        for (Map.Entry<String, Integer> entry : Prospecting.assign(miners, mines, prospectors).entrySet()) {
            minerPlan.put(UUID.fromString(entry.getKey()), entry.getValue());
        }
        prospects.keySet().removeIf(uuid -> !Integer.valueOf(Prospecting.PROSPECTOR).equals(minerPlan.get(uuid)));
    }

    private void openSideMine(ServerLevel level, int wanted, List<Prospecting.Deposit> deposits) {
        if (allMines().size() >= wanted || level.getGameTime() < sideMineRetry) {
            return;
        }
        List<Pos> existing = new ArrayList<>();
        for (ColonyData.Mine mine : allMines()) {
            existing.add(mine.quarry() ? mine.corner : mine.entrance);
        }
        Prospecting.Deposit anchor = Prospecting.anchor(deposits, data.radius, existing);
        int generation = data.mines.size() * 3 + 2;
        ColonyData.Mine mine = anchor == null ? null : sites.mineSite(level, generation, null, allMines(), anchor.pos());
        if (mine == null) {
            anchor = null;
            mine = sites.mineSite(level, generation, null, allMines(), null);
        }
        if (mine == null) {
            sideMineRetry = level.getGameTime() + 6000;
            journal(level, "no place found for another mine, will look again later");
            return;
        }
        mine.generation = generation;
        mine.anchor = anchor == null ? null : anchor.pos();
        data.mines.add(mine);
        journal(level, "another shaft for the miners: " + (mine.quarry() ? "quarry" : "mine") + " at " + mine.entrance
                + (anchor != null ? " by the ore the scouts found at " + anchor.pos() : ""));
        notify(level, Component.translatable(anchor != null ? "smartcolony.chat.mine_side_ore" : "smartcolony.chat.mine_side",
                mine.entrance.x(), mine.entrance.z()));
        markDirty();
    }

    public enum Prospect {
        TRIP,
        EMPTY,
        UNREACHABLE
    }

    public void prospectDone(ServerLevel level, ColonyData.Discovery discovery, int mined, Prospect outcome) {
        if (mined > 0) {
            discovery.dry = 0;
            data.bump("prospect_ores", mined);
            data.bump("prospect_trips", 1);
        } else if (outcome == Prospect.UNREACHABLE) {
            discovery.dry++;
        }
        if (outcome == Prospect.EMPTY || discovery.dry >= Prospecting.MAX_DRY) {
            discovery.worked = level.getGameTime();
            journal(level, "the ore deposit at " + discovery.pos + (outcome == Prospect.EMPTY ? " is worked out" : " cannot be reached"));
            minersPlannedAt = -100000;
        }
        markDirty();
    }

    public void discoverFarms(ServerLevel level) {
        sites.discoverFarms(level);
    }

    public boolean expandFarms(ServerLevel level) {
        return fields.expand(level);
    }

    public void addFarm(BlockPos pos) {
        data.farms.add(WorldUtil.pos(pos));
        markDirty();
    }

    public BlockPos findGrass(ServerLevel level, BlockPos near) {
        return sites.grass(level, near);
    }

    public BlockPos findPondSite(ServerLevel level) {
        return fields.pondSite(level);
    }

    public BlockPos findWater(ServerLevel level, BlockPos near, int radius) {
        return sites.water(level, near, radius);
    }

    public BlockPos[] fishingSpot(ServerLevel level, BlockPos from) {
        return sites.fishingSpot(level, from);
    }

    public void rejectFishingSpot(BlockPos shore) {
        sites.rejectFishing(shore);
    }

    private final Map<ColonyData.Mine, Map<Integer, Long>> mineClaims = new java.util.IdentityHashMap<>();

    public Map<Integer, Long> mineClaims(ColonyData.Mine mine) {
        return mineClaims.computeIfAbsent(mine, key -> new HashMap<>());
    }

    private org.lts.smartcolony.work.Fitter fitter;

    public org.lts.smartcolony.work.Fitter fitter() {
        if (fitter == null) {
            fitter = new org.lts.smartcolony.work.Fitter(this);
        }
        return fitter;
    }

    public boolean inMine(BlockPos pos) {
        for (ColonyData.Mine mine : allMines()) {
            if (inMine(mine, pos)) {
                return true;
            }
        }
        return false;
    }

    public boolean inMine(ColonyData.Mine mine, BlockPos pos) {
        if (mine == null || pos.getY() > mine.entrance.y() + 1) {
            return false;
        }
        if (mine.quarry()) {
            return pos.getX() >= mine.corner.x() - 1 && pos.getX() <= mine.corner.x() + mine.width
                    && pos.getZ() >= mine.corner.z() - 1 && pos.getZ() <= mine.corner.z() + mine.width;
        }
        if (pos.getY() >= mine.entrance.y() - 1) {
            return false;
        }
        java.util.Set<Long> cells = tunnelCells(mine);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (cells.contains(BlockPos.asLong(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private final Map<ColonyData.Mine, java.util.Set<Long>> tunnels = new java.util.IdentityHashMap<>();
    private final Map<ColonyData.Mine, Integer> tunnelProgress = new java.util.IdentityHashMap<>();

    private java.util.Set<Long> tunnelCells(ColonyData.Mine mine) {
        java.util.Set<Long> cached = tunnels.get(mine);
        if (cached != null && tunnelProgress.getOrDefault(mine, -1) == mine.progress) {
            return cached;
        }
        if (tunnels.size() > 8) {
            tunnels.clear();
            tunnelProgress.clear();
        }
        org.lts.smartcolony.core.DigPlan plan = mine.plan();
        java.util.Set<Long> cells = new java.util.HashSet<>();
        int dug = Math.min(mine.progress + 1, plan.size());
        for (int i = 0; i < dug; i++) {
            org.lts.smartcolony.core.MinePlan.Cell cell = plan.cell(i);
            for (int h = 0; h < cell.height(); h++) {
                cells.add(BlockPos.asLong(cell.feet().x(), cell.feet().y() + h, cell.feet().z()));
            }
        }
        tunnels.put(mine, cells);
        tunnelProgress.put(mine, mine.progress);
        return cells;
    }

    private void clearMineOfStrangers(ServerLevel level) {
        for (ColonyData.Mine mine : allMines()) {
            for (Map.Entry<UUID, Colonist> entry : colonists.entrySet()) {
                if (entry.getValue().role() == Role.MINER || !(level.getEntity(entry.getKey()) instanceof Villager villager)) {
                    continue;
                }
                BlockPos pos = villager.blockPosition();
                if (pos.getY() < mine.entrance.y() - 2 && inMine(mine, pos)
                        && org.lts.smartcolony.work.Mover.rescue(level, villager, depot().above())) {
                    entry.getValue().mover().reset();
                    entry.getValue().restartJob(level, villager);
                    data.bump("mine_strangers_led_out", 1);
                }
            }
        }
    }

    private void closePenGate(ServerLevel level) {
        ColonyData.Pen pen = data.pen;
        if (pen == null || pen.gate == null) {
            return;
        }
        BlockPos gate = WorldUtil.bp(pen.gate);
        if (!WorldUtil.loaded(level, gate)) {
            return;
        }
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(gate);
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock)
                || !state.getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN)) {
            return;
        }
        for (Map.Entry<UUID, Colonist> entry : colonists.entrySet()) {
            if (entry.getValue().role() == Role.HERDER && level.getEntity(entry.getKey()) instanceof Villager villager
                    && org.lts.smartcolony.work.Colonist.isControlled(villager) && villager.blockPosition().distSqr(gate) <= 12 * 12) {
                return;
            }
        }
        level.setBlock(gate, state.setValue(net.minecraft.world.level.block.FenceGateBlock.OPEN, false),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
        data.bump("pen_gate_closed", 1);
    }

    public int adultCount(ServerLevel level) {
        int adults = 0;
        for (UUID uuid : colonists.keySet()) {
            if (level.getEntity(uuid) instanceof Villager villager && !villager.isBaby()) {
                adults++;
            }
        }
        return adults;
    }

    public List<Villager> loadedVillagers(ServerLevel level) {
        List<Villager> result = new ArrayList<>();
        for (UUID uuid : colonists.keySet()) {
            if (level.getEntity(uuid) instanceof Villager villager && villager.isAlive()) {
                result.add(villager);
            }
        }
        return result;
    }

    public boolean isMember(Entity entity) {
        return entity instanceof Villager && colonists.containsKey(entity.getUUID());
    }

    public int reputation(UUID player) {
        return data.reputation.getOrDefault(player.toString(), 0);
    }

    public Reputation.Status status(UUID player) {
        return Reputation.status(reputation(player));
    }

    public void adjustReputation(ServerLevel level, ServerPlayer player, int delta, String reasonKey) {
        if (delta == 0) {
            return;
        }
        UUID uuid = player.getUUID();
        Reputation.Status before = status(uuid);
        int value = Reputation.clamp(reputation(uuid) + delta);
        data.reputation.put(uuid.toString(), value);
        Reputation.Status after = Reputation.status(value);
        player.sendSystemMessage(Component.translatable("smartcolony.chat.prefix", data.name).append(" ")
                .append(Component.translatable(reasonKey, (delta > 0 ? "+" : "") + delta, value,
                        Component.translatable("smartcolony.status." + after.key()))));
        if (before != after) {
            defense.onStatusChange(level, player, before, after);
        }
        markDirty();
    }

    public void tick(ServerLevel level) {
        ticks++;
        if (ticks % 20 == 0) {
            refreshMembers(level);
        }
        for (Map.Entry<UUID, Colonist> entry : colonists.entrySet()) {
            if (level.getEntity(entry.getKey()) instanceof Villager villager && villager.isAlive()) {
                entry.getValue().tick(level, villager);
            }
        }
        Nursery.tick(level, this);
        ward.tick(level);
        survey.tick(level);
        recovery.tick(level);
        outposts.tick(level, ticks);
        forest.scan(level);
        theft.tick(level);
        defense.tick(level, ticks);
        doors.tick(level, ticks);
        safeRooms.tick(level, ticks);
        daily.tick(level, ticks);
        fire.tick(level, ticks);
        chores.tick(level, ticks);
        overgrowth.tick(level, ticks);
        contracts.tick(level, ticks);
        stable.tick(level, ticks);
        embassy.tick(level, ticks);
        if (ticks % 200 == 7) {
            planRoles(level);
        }
        if (ticks % 40 == 23) {
            clearMineOfStrangers(level);
        }
        if (ticks % 20 == 11) {
            closePenGate(level);
        }
        if (ticks % 100 == 13) {
            construction.tick(level);
        }
        if (ticks % 100 == 57) {
            ironAge.tick(level);
        }
        if (ticks % 100 == 31) {
            hall.tick(level);
        }
        economy.tick(level, ticks);
        trader.tick(level, ticks);
        migration.tick(level);
        tavern.tick(level);
        if (ticks % 20 == 11) {
            advisor.tick(level);
        }
        boolean party = festival(level);
        if (party && !festivalAnnounced) {
            festivalAnnounced = true;
            boolean atTavern = tavern.built();
            journal(level, atTavern ? "festival at the tavern" : "festival at the bell");
            notify(level, Component.translatable(atTavern ? "smartcolony.chat.festival_tavern" : "smartcolony.chat.festival"));
            level.playSound(null, atTavern ? tavern.plaza() : center(), net.minecraft.sounds.SoundEvents.BELL_BLOCK, net.minecraft.sounds.SoundSource.BLOCKS, 2.0F, 1.0F);
        } else if (!party) {
            festivalAnnounced = false;
        }
        if (ticks % 1000 == 0) {
            recoverReputation();
        }
    }

    private void recoverReputation() {
        for (Map.Entry<String, Integer> entry : data.reputation.entrySet()) {
            entry.setValue(Reputation.recover(entry.getValue()));
        }
    }

    private void refreshMembers(ServerLevel level) {
        int radius = data.radius + 8;
        AABB box = new AABB(center()).inflate(radius, 24, radius);
        for (Villager villager : level.getEntitiesOfClass(Villager.class, box, Villager::isAlive)) {
            if (colonists.containsKey(villager.getUUID()) || ColonyManager.colonyOf(villager) != null
                    || villager.entityTags().contains(Tavern.TAG)) {
                continue;
            }
            if (villager.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(center())) > radius) {
                continue;
            }
            join(level, villager);
        }
        Iterator<Map.Entry<UUID, Colonist>> it = colonists.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Colonist> entry = it.next();
            Entity entity = level.getEntity(entry.getKey());
            if (entity instanceof Villager villager && villager.isAlive()) {
                double distance = villager.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(center()));
                if (distance > data.radius + 160 && !Colonist.isControlled(villager) && !embassy.onMission(entry.getKey())) {
                    leave(level, villager, entry.getValue());
                    it.remove();
                }
                ColonyData.Member member = data.members.get(entry.getKey().toString());
                if (member != null) {
                    Schoolhouse.graduate(level, this, villager, member);
                }
                if (member != null && villager.isBaby() && member.role() != Role.IDLE) {
                    member.role = Role.IDLE.key();
                    entry.getValue().setRole(level, villager, Role.IDLE);
                }
            }
        }
    }

    public void join(ServerLevel level, Villager villager) {
        ColonyData.Member member = new ColonyData.Member();
        member.since = level.getGameTime();
        data.members.put(villager.getUUID().toString(), member);
        Colonist colonist = new Colonist(villager.getUUID(), this);
        colonist.setRole(level, villager, Role.IDLE);
        colonists.put(villager.getUUID(), colonist);
        ColonyManager.index(villager.getUUID(), this);
        markDirty();
    }

    private void leave(ServerLevel level, Villager villager, Colonist colonist) {
        if (Colonist.isControlled(villager)) {
            colonist.release(level, villager, "left");
        }
        if (ownsName(villager.getUUID())) {
            villager.setCustomName(null);
        }
        data.members.remove(villager.getUUID().toString());
        ColonyManager.unindex(villager.getUUID());
        journal(level, "a villager left the colony");
    }

    public void memberDied(ServerLevel level, Villager villager, net.minecraft.world.damagesource.DamageSource source) {
        Colonist colonist = colonists.get(villager.getUUID());
        if (colonist == null) {
            return;
        }
        data.bump("deaths", 1);
        embassy.memberLost(level, villager, source);
        graveyard.recordDeath(level, villager, source, colonist);
        removeMember(villager.getUUID());
    }

    public void memberDied(ServerLevel level, UUID uuid) {
        Colonist colonist = colonists.get(uuid);
        if (colonist != null) {
            data.bump("deaths", 1);
            embassy.forget(uuid);
            journal(level, colonist.role().key() + " died");
            notify(level, Component.translatable("smartcolony.chat.death", Component.translatable("smartcolony.role." + colonist.role().key())));
            removeMember(uuid);
        }
    }

    private void removeMember(UUID uuid) {
        colonists.remove(uuid);
        data.members.remove(uuid.toString());
        ColonyManager.unindex(uuid);
        forest.release(uuid);
        treeFarm.release(uuid);
    }

    public void planRoles(ServerLevel level) {
        Stock stock = warehouse.stock(level);
        int adults = adultCount(level);
        Set<Role> equippable = java.util.EnumSet.noneOf(Role.class);
        if (canEquip(level, stock, org.lts.smartcolony.work.Tools.PICKAXES)) {
            equippable.add(Role.MINER);
        }
        if (canEquip(level, stock, List.of(Ids.mc("fishing_rod")))) {
            equippable.add(Role.FISHERMAN);
        }
        if (canEquip(level, stock, org.lts.smartcolony.work.Tools.SWORDS) || canEquip(level, stock, org.lts.smartcolony.work.Tools.AXES)) {
            equippable.add(Role.GUARD);
        }
        for (Map.Entry<UUID, Colonist> entry : colonists.entrySet()) {
            if (!(level.getEntity(entry.getKey()) instanceof Villager) && entry.getValue().role() != Role.IDLE) {
                adults++;
            }
        }
        int wood = stock.sum(Ids::isLog) + stock.sum(Ids::isPlanks) / 4;
        int stone = stock.get(Ids.mc("cobblestone")) + stock.get(Ids.mc("cobbled_deepslate"));
        int iron = stock.get(Ids.mc("iron_ingot")) + stock.get(Ids.mc("raw_iron"));
        int animal = stock.sum(id -> Ids.isWool(id) || id.equals(Ids.mc("leather")) || id.equals(Ids.mc("egg")) || id.equals(Ids.mc("feather")));
        Map<String, Integer> shortages = new LinkedHashMap<>(construction.missing());
        contracts.missing(level).forEach((id, count) -> shortages.merge(id, count, Integer::sum));
        for (Map.Entry<String, Integer> entry : shortages.entrySet()) {
            String id = entry.getKey();
            if (Ids.isLog(id) || Ids.isPlanks(id) || id.contains("_door") || id.contains("_slab") || id.contains("_fence")) {
                wood = Math.min(wood, 10);
            } else if (id.contains("cobble") || id.contains("glass") || id.contains("torch")) {
                stone = Math.min(stone, 10);
            } else if (id.contains("wool") || id.contains("_bed")) {
                animal = 0;
            }
        }
        EnumMap<Role, Integer> projected = new EnumMap<>(Role.class);
        projected.putAll(desired);
        if (projected.getOrDefault(Role.GUARD, 0) == 0) {
            projected.put(Role.GUARD, Math.max(1, adults / 5));
        }
        org.lts.smartcolony.core.CrafterNeeds.Context crafting = new org.lts.smartcolony.core.CrafterNeeds.Context(stock, projected, adults,
                unarmedGuards(level));
        RoleAllocator.Needs needs = new RoleAllocator.Needs(adults, wood, stone, iron, economy.foodPoints(stock), animal,
                construction.backlog(level), data.pen != null, equippable, defense.threatLevel(),
                org.lts.smartcolony.core.CrafterNeeds.pending(Role.FLETCHER, crafting),
                org.lts.smartcolony.core.CrafterNeeds.pending(Role.SMITH, crafting),
                org.lts.smartcolony.core.CrafterNeeds.pending(Role.COOK, crafting), Schoolhouse.wantsTeacher(level, this));
        EnumMap<Role, Integer> baseline = RoleAllocator.reserveMedics(
                org.lts.smartcolony.core.TavernRules.withInnkeeper(RoleAllocator.desired(needs), tavern.built(), adults),
                org.lts.smartcolony.core.Hospital.medicsFor(adults, ward.stage() > 0, SmartColonyConfig.get().adultsPerMedic));
        desired = RoleAllocator.withTargets(baseline, advisor.roleTargets(level.getGameTime()), adults);
        desired = RoleAllocator.withUndertaker(desired, graveyard.undertakersWanted(level), adults);
        desired = Prospecting.settle(desired, deposits().size(), adults, equippable.contains(Role.MINER));
        desired = org.lts.smartcolony.core.PorterPlan.settle(desired, outposts.porterDemand(level));
        desired = org.lts.smartcolony.core.Specialists.settle(desired, specialistWants(level, adults), adults);
        desired = org.lts.smartcolony.core.Decree.apply(desired, org.lts.smartcolony.core.Decree.Focus.byKey(data.focus), adults);
        Map<String, Role> current = new LinkedHashMap<>();
        Map<String, Role> affinity = new HashMap<>();
        Set<String> locked = new java.util.HashSet<>();
        Map<String, Villager> loaded = new HashMap<>();
        for (Map.Entry<UUID, Colonist> entry : colonists.entrySet()) {
            if (!(level.getEntity(entry.getKey()) instanceof Villager villager)) {
                if (entry.getValue().role() != Role.IDLE) {
                    current.put(entry.getKey().toString(), entry.getValue().role());
                    locked.add(entry.getKey().toString());
                }
                continue;
            }
            if (villager.isBaby()) {
                continue;
            }
            String key = entry.getKey().toString();
            ColonyData.Member member = data.members.get(key);
            current.put(key, entry.getValue().role());
            affinity.put(key, member != null && member.graduateRole() != null ? member.graduateRole()
                    : member != null && member.affinityRole() != null ? member.affinityRole()
                    : Role.affinity(villager.getVillagerData().profession().unwrapKey()
                    .map(k -> k.identifier().getPath()).orElse("none")));
            if (member != null && (member.locked || (member.role() != Role.IDLE && level.getGameTime() - member.since < 2400))) {
                locked.add(key);
            }
            loaded.put(key, villager);
        }
        Map<String, Role> changes = RoleAllocator.reassign(current, affinity, locked, desired, Math.max(1, adults / 10));
        for (Map.Entry<String, Role> change : changes.entrySet()) {
            Villager villager = loaded.get(change.getKey());
            Colonist colonist = colonists.get(UUID.fromString(change.getKey()));
            ColonyData.Member member = data.members.get(change.getKey());
            if (villager == null || colonist == null || member == null) {
                continue;
            }
            Role old = colonist.role();
            if (Colonist.isControlled(villager) && !Jobs.canInterrupt(colonist, villager)) {
                continue;
            }
            colonist.setRole(level, villager, change.getValue());
            member.role = change.getValue().key();
            member.since = level.getGameTime();
            journal(level, "role change: " + old.key() + " -> " + change.getValue().key());
        }
        for (Map.Entry<UUID, Colonist> entry : colonists.entrySet()) {
            if (level.getEntity(entry.getKey()) instanceof Villager villager) {
                entry.getValue().applyName(villager);
            }
        }
        if (!changes.isEmpty()) {
            markDirty();
        }
    }

    private org.lts.smartcolony.core.Specialists.Wants specialistWants(ServerLevel level, int adults) {
        boolean nests = level.getPoiManager().getCountInRange(type -> type.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.BEE_NEST)
                || type.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.BEEHIVE), center(), data.radius + 32,
                net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY) > 0;
        for (ColonyData.Discovery discovery : data.discoveries) {
            if (org.lts.smartcolony.core.Expedition.NEST.equals(discovery.kind) && !discovery.used) {
                nests = true;
                break;
            }
        }
        return new org.lts.smartcolony.core.Specialists.Wants(
                org.lts.smartcolony.core.Specialists.masons(builtKind(org.lts.smartcolony.core.Specialists.MASONRY), adults),
                org.lts.smartcolony.core.Specialists.beekeepers(builtKind(org.lts.smartcolony.core.Specialists.APIARY), nests, adults),
                org.lts.smartcolony.core.Specialists.alchemists(builtKind(org.lts.smartcolony.core.Specialists.APOTHECARY), adults),
                org.lts.smartcolony.core.Specialists.scribes(builtKind(org.lts.smartcolony.core.Specialists.LIBRARY), adults),
                org.lts.smartcolony.core.Specialists.scouts(adults, true));
    }

    public int unarmedGuards(ServerLevel level) {
        int unarmed = 0;
        for (Map.Entry<UUID, Colonist> entry : colonists.entrySet()) {
            if (entry.getValue().role() != Role.GUARD || !(level.getEntity(entry.getKey()) instanceof Villager villager)) {
                continue;
            }
            boolean sword = org.lts.smartcolony.work.Tools.SWORD.test(villager.getMainHandItem())
                    || Inv.count(villager, org.lts.smartcolony.work.Tools.SWORD) > 0;
            boolean bow = (org.lts.smartcolony.work.Tools.BOW.test(villager.getMainHandItem())
                    || Inv.count(villager, org.lts.smartcolony.work.Tools.BOW) > 0)
                    && Inv.count(villager, org.lts.smartcolony.work.Tools.ARROW) > 0;
            if (!sword && !bow) {
                unarmed++;
            }
        }
        return unarmed;
    }

    public org.lts.smartcolony.core.CrafterNeeds.Context crafterContext(ServerLevel level) {
        EnumMap<Role, Integer> roles = new EnumMap<>(Role.class);
        roles.putAll(desired);
        if (roles.getOrDefault(Role.GUARD, 0) == 0) {
            roles.put(Role.GUARD, Math.max(1, adultCount(level) / 5));
        }
        return new org.lts.smartcolony.core.CrafterNeeds.Context(warehouse.stock(level), roles, adultCount(level), unarmedGuards(level));
    }

    private boolean canEquip(ServerLevel level, Stock stock, List<String> tools) {
        for (String tool : tools) {
            if (stock.get(tool) > 0 || CraftPlanner.plan(stock, tool, 1).isPresent()) {
                return true;
            }
        }
        for (UUID uuid : colonists.keySet()) {
            if (level.getEntity(uuid) instanceof Villager villager) {
                for (String tool : tools) {
                    if (ItemIds.is(villager.getMainHandItem(), tool) || Inv.count(villager, tool) > 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void lockRole(ServerLevel level, Villager villager, Role role) {
        ColonyData.Member member = data.members.get(villager.getUUID().toString());
        Colonist colonist = colonists.get(villager.getUUID());
        if (member == null || colonist == null) {
            return;
        }
        if (role == null) {
            member.locked = false;
        } else {
            member.locked = true;
            member.role = role.key();
            colonist.setRole(level, villager, role);
        }
        markDirty();
    }

    public void disband(ServerLevel level) {
        trader.dismiss(level);
        migration.releaseAll(level);
        tavern.releaseAll(level);
        for (Map.Entry<UUID, Colonist> entry : colonists.entrySet()) {
            if (level.getEntity(entry.getKey()) instanceof Villager villager) {
                if (Colonist.isControlled(villager)) {
                    entry.getValue().release(level, villager, "disband");
                }
                if (ownsName(villager.getUUID())) {
                    villager.setCustomName(null);
                }
            }
            ColonyManager.unindex(entry.getKey());
        }
        colonists.clear();
    }

    public JsonObject debugJson(ServerLevel level) {
        JsonObject root = new JsonObject();
        root.add("data", ColonyCodec.gson().toJsonTree(data));
        JsonObject stock = new JsonObject();
        warehouse.stock(level).sorted().forEach(stock::addProperty);
        root.add("stock", stock);
        JsonObject fill = new JsonObject();
        warehouse.fill(level).forEach((category, value) -> fill.addProperty(category.key(), Math.round(value * 100) / 100.0));
        root.add("fill", fill);
        root.add("outposts", outposts.debug(level));
        JsonObject roles = new JsonObject();
        desired.forEach((role, count) -> roles.addProperty(role.key(), count));
        root.add("desiredRoles", roles);
        JsonArray members = new JsonArray();
        for (Map.Entry<UUID, Colonist> entry : colonists.entrySet()) {
            JsonObject member = new JsonObject();
            member.addProperty("uuid", entry.getKey().toString());
            member.addProperty("role", entry.getValue().role().key());
            Entity entity = level.getEntity(entry.getKey());
            if (entity instanceof Villager villager) {
                member.addProperty("loaded", true);
                member.addProperty("pos", villager.blockPosition().toShortString());
                member.addProperty("controlled", Colonist.isControlled(villager));
                member.addProperty("status", entry.getValue().describe(level));
                member.addProperty("mover", entry.getValue().mover().note());
                member.addProperty("health", Math.round(villager.getHealth() * 10) / 10.0);
                member.addProperty("baby", villager.isBaby());
                member.addProperty("profession", villager.getVillagerData().profession().unwrapKey()
                        .map(k -> k.identifier().getPath()).orElse("?"));
                member.addProperty("hand", ItemIds.id(villager.getMainHandItem()));
                JsonObject inv = new JsonObject();
                for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
                    ItemStack stack = villager.getInventory().getItem(slot);
                    if (!stack.isEmpty()) {
                        inv.addProperty(ItemIds.id(stack), inv.has(ItemIds.id(stack))
                                ? inv.get(ItemIds.id(stack)).getAsInt() + stack.getCount() : stack.getCount());
                    }
                }
                member.add("inventory", inv);
            } else {
                member.addProperty("loaded", false);
            }
            members.add(member);
        }
        root.add("members", members);
        JsonArray req = new JsonArray();
        requests.forEach(req::add);
        root.add("requests", req);
        root.add("construction", construction.debug(level));
        root.addProperty("forestTrees", forest.count());
        root.addProperty("forestKnown", forest.known());
        root.addProperty("foodPoints", foodPoints(level));
        root.addProperty("threat", defense.threatLevel());
        root.add("defense", defense.debug(level));
        return root;
    }

    public Map<Category, Double> fill(ServerLevel level) {
        return warehouse.fill(level);
    }

    public long ticks() {
        return ticks;
    }

    public boolean warnStorage(long now) {
        if (now - lastStorageWarning > 6000) {
            lastStorageWarning = now;
            return true;
        }
        return false;
    }
}
