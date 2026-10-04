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
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.Reputation;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.RoleAllocator;
import org.lts.smartcolony.core.Stock;
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
    private final Economy economy;
    private final Defense defense;
    private final Construction construction;
    private final Migration migration;
    private final Improvements improvements;
    private boolean festivalAnnounced;
    private final TheftWatcher theft;
    private final Map<UUID, Colonist> colonists = new LinkedHashMap<>();
    private final Set<String> requests = new LinkedHashSet<>();
    private final Set<UUID> ownedNames = new java.util.HashSet<>();
    private EnumMap<Role, Integer> desired = new EnumMap<>(Role.class);
    private boolean dirty;
    private long ticks;
    private long lastStorageWarning;

    public Colony(ColonyData data) {
        this.data = data;
        this.warehouse = new Warehouse(data);
        this.forest = new ForestIndex(this);
        this.sites = new Sites(this);
        this.economy = new Economy(this);
        this.defense = new Defense(this);
        this.construction = new Construction(this);
        this.migration = new Migration(this);
        this.improvements = new Improvements(this);
        this.theft = new TheftWatcher(this);
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

    public Sites sites() {
        return sites;
    }

    public Economy economy() {
        return economy;
    }

    public Defense defense() {
        return defense;
    }

    public Improvements improvements() {
        return improvements;
    }

    public Migration migration() {
        return migration;
    }

    public Construction construction() {
        return construction;
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

    public boolean craft(ServerLevel level, String id, int count) {
        Stock stock = warehouse.stock(level);
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
        Pos p = WorldUtil.pos(pos);
        if (p.horizontalDistSq(data.depot) < 36 || p.horizontalDistSq(data.center) < 16) {
            return true;
        }
        for (ColonyData.Building building : data.buildings) {
            if (building.contains(p, 2)) {
                return true;
            }
        }
        for (ColonyData.Project project : data.projects) {
            if (project.building.contains(p, 2)) {
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
        if (data.mine != null && p.horizontalDistSq(data.mine.entrance) < 9) {
            return true;
        }
        if (improvements.isWall(p)) {
            return true;
        }
        if (data.mine != null && data.mine.quarry() && p.x() >= data.mine.corner.x() - 1 && p.z() >= data.mine.corner.z() - 1
                && p.x() <= data.mine.corner.x() + data.mine.width && p.z() <= data.mine.corner.z() + data.mine.width) {
            return true;
        }
        return false;
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
        int generation = data.mine == null ? 1 : data.mine.generation + 1;
        ColonyData.Mine next = sites.mineSite(level, generation, data.mine);
        if (next != null) {
            next.generation = generation;
            data.mine = next;
            journal(level, "old mine worked out, new " + (next.quarry() ? "quarry" : "mine") + " at " + next.entrance);
        }
        markDirty();
    }

    public void discoverFarms(ServerLevel level) {
        sites.discoverFarms(level);
    }

    public boolean expandFarms(ServerLevel level) {
        return sites.expandFarms(level);
    }

    public void addFarm(BlockPos pos) {
        data.farms.add(WorldUtil.pos(pos));
        markDirty();
    }

    public BlockPos findGrass(ServerLevel level, BlockPos near) {
        return sites.grass(level, near);
    }

    public BlockPos findPondSite(ServerLevel level) {
        return sites.pondSite(level);
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
        forest.scan(level);
        theft.tick(level);
        defense.tick(level, ticks);
        if (ticks % 200 == 7) {
            planRoles(level);
        }
        if (ticks % 100 == 13) {
            construction.tick(level);
        }
        economy.tick(level, ticks);
        migration.tick(level);
        boolean party = festival(level);
        if (party && !festivalAnnounced) {
            festivalAnnounced = true;
            journal(level, "festival at the bell");
            notify(level, Component.translatable("smartcolony.chat.festival"));
            level.playSound(null, center(), net.minecraft.sounds.SoundEvents.BELL_BLOCK, net.minecraft.sounds.SoundSource.BLOCKS, 2.0F, 1.0F);
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
            if (colonists.containsKey(villager.getUUID()) || ColonyManager.colonyOf(villager) != null) {
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
                if (distance > data.radius + 160 && !Colonist.isControlled(villager)) {
                    leave(level, villager, entry.getValue());
                    it.remove();
                }
                ColonyData.Member member = data.members.get(entry.getKey().toString());
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

    public void memberDied(ServerLevel level, UUID uuid) {
        Colonist colonist = colonists.remove(uuid);
        if (colonist != null) {
            data.members.remove(uuid.toString());
            ColonyManager.unindex(uuid);
            forest.release(uuid);
            data.bump("deaths", 1);
            journal(level, colonist.role().key() + " died");
            notify(level, Component.translatable("smartcolony.chat.death", Component.translatable("smartcolony.role." + colonist.role().key())));
        }
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
        int wood = stock.sum(Ids::isLog) + stock.sum(Ids::isPlanks) / 4;
        int stone = stock.get(Ids.mc("cobblestone")) + stock.get(Ids.mc("cobbled_deepslate"));
        int iron = stock.get(Ids.mc("iron_ingot")) + stock.get(Ids.mc("raw_iron"));
        int animal = stock.sum(id -> Ids.isWool(id) || id.equals(Ids.mc("leather")) || id.equals(Ids.mc("egg")) || id.equals(Ids.mc("feather")));
        for (Map.Entry<String, Integer> entry : construction.missing().entrySet()) {
            String id = entry.getKey();
            if (Ids.isLog(id) || Ids.isPlanks(id) || id.contains("_door") || id.contains("_slab") || id.contains("_fence")) {
                wood = Math.min(wood, 10);
            } else if (id.contains("cobble") || id.contains("glass") || id.contains("torch")) {
                stone = Math.min(stone, 10);
            } else if (id.contains("wool") || id.contains("_bed")) {
                animal = 0;
            }
        }
        RoleAllocator.Needs needs = new RoleAllocator.Needs(adults, wood, stone, iron, economy.foodPoints(stock), animal,
                construction.backlog(level), data.pen != null, equippable, defense.threatLevel());
        desired = RoleAllocator.desired(needs);
        Map<String, Role> current = new LinkedHashMap<>();
        Map<String, Role> affinity = new HashMap<>();
        Set<String> locked = new java.util.HashSet<>();
        Map<String, Villager> loaded = new HashMap<>();
        for (Map.Entry<UUID, Colonist> entry : colonists.entrySet()) {
            if (!(level.getEntity(entry.getKey()) instanceof Villager villager) || villager.isBaby()) {
                continue;
            }
            String key = entry.getKey().toString();
            ColonyData.Member member = data.members.get(key);
            current.put(key, entry.getValue().role());
            affinity.put(key, Role.affinity(villager.getVillagerData().profession().unwrapKey()
                    .map(k -> k.identifier().getPath()).orElse("none")));
            if (member != null && (member.locked || (member.role() != Role.IDLE && level.getGameTime() - member.since < 2400))) {
                locked.add(key);
            }
            loaded.put(key, villager);
        }
        Map<String, Role> changes = RoleAllocator.reassign(current, affinity, locked, desired, 1);
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
        migration.releaseAll(level);
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
