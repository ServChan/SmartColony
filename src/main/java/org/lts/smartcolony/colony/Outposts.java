package org.lts.smartcolony.colony;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.PorterPlan;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.util.WorldUtil;
import org.lts.smartcolony.work.Colonist;
import org.lts.smartcolony.work.Job;

public final class Outposts {
    public enum Type {
        HAUL,
        PICKUP
    }

    public record Task(Type type, ColonyData.Outpost outpost, UUID builder) {
    }

    private record Anchor(BlockPos pos, int minRadius, int maxRadius, List<int[]> forbidden) {
    }

    private static final int CHECK_PERIOD = 100;
    private static final int PLAN_PERIOD = 600;
    private static final long RETRY_TICKS = 6000;
    private static final long STALE_TICKS = 120000;
    private static final int SAMPLE_WINDOW = 40;
    private static final double MINE_NEAR = 40.0;
    private static final double FOREST_NEAR = 50.0;
    private static final int MAX_STRIKES = 3;
    private static final long BLOCK_TICKS = 1200;

    private final Colony colony;
    private final Map<String, Long> retryAt = new HashMap<>();
    private final Map<String, Integer> trouble = new HashMap<>();
    private final Map<UUID, Task> claims = new HashMap<>();
    private int treeSamples;
    private double treeDx;
    private double treeDz;
    private double treeDistance;

    Outposts(Colony colony) {
        this.colony = colony;
    }

    private ColonyData data() {
        return colony.data();
    }

    public List<ColonyData.Outpost> all() {
        return data().outposts;
    }

    public void tick(ServerLevel level, long ticks) {
        if (ticks % CHECK_PERIOD == 31) {
            validate(level);
        }
        if (ticks % PLAN_PERIOD == 307) {
            plan(level);
        }
    }

    public void sampleTree(BlockPos tree) {
        BlockPos depot = colony.depot();
        double dx = tree.getX() - depot.getX();
        double dz = tree.getZ() - depot.getZ();
        treeSamples++;
        treeDx += dx;
        treeDz += dz;
        treeDistance += Math.hypot(dx, dz);
        if (treeSamples >= SAMPLE_WINDOW) {
            treeSamples /= 2;
            treeDx /= 2;
            treeDz /= 2;
            treeDistance /= 2;
        }
    }

    private void resetTrees() {
        treeSamples = 0;
        treeDx = 0;
        treeDz = 0;
        treeDistance = 0;
    }

    public void noteTrouble(String kind) {
        trouble.merge(kind, 1, Integer::sum);
    }

    private double treeAverage() {
        return treeSamples == 0 ? 0.0 : treeDistance / treeSamples;
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private static double horizontal(Pos a, BlockPos b) {
        return Math.hypot(a.x() - b.getX(), a.z() - b.getZ());
    }

    private Anchor mineAnchor() {
        ColonyData.Mine mine = data().mine;
        if (mine == null) {
            return null;
        }
        if (mine.quarry()) {
            int half = mine.width / 2;
            BlockPos pos = new BlockPos(mine.corner.x() + half, mine.corner.y(), mine.corner.z() + half);
            return new Anchor(pos, half + 5, half + 18, PorterPlan.quarryForbidden(mine.corner.x(), mine.corner.z(), mine.width, mine.shed,
                    Improvements.SHED_LENGTH, Improvements.SHED_WIDTH));
        }
        return new Anchor(WorldUtil.bp(mine.entrance), 6, 16, PorterPlan.tunnelForbidden(mine.entrance, mine.dirX, mine.dirZ, mine.depth));
    }

    private int pendingOutposts() {
        int pending = 0;
        for (ColonyData.Project project : colony.construction().projects()) {
            if (PorterPlan.OUTPOST_ID.equals(project.building.blueprint)) {
                pending++;
            }
        }
        return pending;
    }

    private boolean outpostNear(BlockPos pos, double radius) {
        for (ColonyData.Outpost outpost : data().outposts) {
            if (horizontal(outpost.center, pos) <= radius) {
                return true;
            }
        }
        for (ColonyData.Project project : colony.construction().projects()) {
            if (PorterPlan.OUTPOST_ID.equals(project.building.blueprint) && horizontal(project.building.origin, pos) <= radius) {
                return true;
            }
        }
        return false;
    }

    private boolean ready(String kind, long now) {
        return retryAt.getOrDefault(kind, Long.MIN_VALUE) <= now;
    }

    private void plan(ServerLevel level) {
        long now = level.getGameTime();
        if (colony.adultCount(level) < PorterPlan.MIN_ADULTS || data().outposts.size() + pendingOutposts() >= PorterPlan.MAX_OUTPOSTS
                || pendingOutposts() > 0 || colony.construction().projects().size() > colony.construction().maxProjects()) {
            return;
        }
        BlockPos depot = colony.depot();
        Anchor mine = mineAnchor();
        if (mine != null && ready(PorterPlan.MINE, now) && !outpostNear(mine.pos(), MINE_NEAR)
                && PorterPlan.longWalk(horizontal(mine.pos(), depot), mine.pos().getY() - depot.getY(), trouble.getOrDefault(PorterPlan.MINE, 0))
                && WorldUtil.loaded(level, mine.pos())) {
            if (colony.construction().planOutpost(level, mine.pos(), mine.minRadius(), mine.maxRadius(), mine.forbidden())) {
                retryAt.put(PorterPlan.MINE, now + RETRY_TICKS / 2);
                return;
            }
            retryAt.put(PorterPlan.MINE, now + RETRY_TICKS);
            colony.journal(level, "no site for an outpost near the mine, will retry later");
        }
        if (ready(PorterPlan.FOREST, now) && PorterPlan.forestFar(treeSamples, treeAverage())) {
            Pos edge = PorterPlan.forestAnchor(data().depot, treeDx, treeDz, treeSamples);
            BlockPos probe = new BlockPos(edge.x(), edge.y(), edge.z());
            if (!WorldUtil.loaded(level, probe) || outpostNear(probe, FOREST_NEAR)) {
                return;
            }
            BlockPos ground = WorldUtil.surface(level, edge.x(), edge.z());
            if (colony.construction().planOutpost(level, ground, 3, 14, List.of())) {
                retryAt.put(PorterPlan.FOREST, now + RETRY_TICKS / 2);
                return;
            }
            retryAt.put(PorterPlan.FOREST, now + RETRY_TICKS);
            colony.journal(level, "no site for an outpost at the forest edge, will retry later");
        }
    }

    void built(ServerLevel level, ColonyData.Building building) {
        ColonyData.Outpost outpost = new ColonyData.Outpost();
        outpost.door = building.door;
        outpost.center = new Pos((building.min.x() + building.max.x()) / 2, building.origin.y(), (building.min.z() + building.max.z()) / 2);
        outpost.containers = new ArrayList<>(building.containers);
        outpost.since = level.getGameTime();
        outpost.lastUse = outpost.since;
        BlockPos center = WorldUtil.bp(outpost.center);
        Anchor mine = mineAnchor();
        boolean forMine = mine != null && horizontal(mine.pos(), center) <= MINE_NEAR;
        outpost.kind = forMine ? PorterPlan.MINE : PorterPlan.FOREST;
        data().outposts.add(outpost);
        colony.warehouse().invalidate();
        data().bump("outposts_built", 1);
        trouble.remove(outpost.kind);
        if (!forMine) {
            resetTrees();
        }
        colony.journal(level, "outpost (" + outpost.kind + ") ready at " + outpost.door + " with " + outpost.containers.size() + " barrels");
        colony.markDirty();
    }

    public ColonyData.Outpost dropFor(ServerLevel level, BlockPos from) {
        if (data().outposts.isEmpty()) {
            return null;
        }
        long now = level.getGameTime();
        BlockPos depot = colony.depot();
        List<PorterPlan.Candidate> candidates = new ArrayList<>();
        for (ColonyData.Outpost outpost : data().outposts) {
            BlockPos door = WorldUtil.bp(outpost.door);
            boolean usable = outpost.blockedUntil <= now && !outpost.containers.isEmpty() && WorldUtil.loaded(level, door);
            candidates.add(new PorterPlan.Candidate(door.getX(), door.getY(), door.getZ(),
                    usable ? colony.warehouse().outpostFree(level, outpost) : 0, usable));
        }
        int index = PorterPlan.pickDrop(from.getX(), from.getY(), from.getZ(), depot.getX(), depot.getY(), depot.getZ(), candidates);
        return index < 0 ? null : data().outposts.get(index);
    }

    public ColonyData.Outpost supplyFor(ServerLevel level, BlockPos from, java.util.function.Predicate<net.minecraft.world.item.ItemStack> filter,
                                        int minimum) {
        ColonyData.Outpost best = null;
        double bestCost = Double.MAX_VALUE;
        BlockPos depot = colony.depot();
        double depotCost = PorterPlan.walkCost(horizontal(from, depot), from.getY() - depot.getY());
        long now = level.getGameTime();
        for (ColonyData.Outpost outpost : data().outposts) {
            BlockPos door = WorldUtil.bp(outpost.door);
            if (outpost.blockedUntil > now || !WorldUtil.loaded(level, door)) {
                continue;
            }
            double cost = PorterPlan.walkCost(horizontal(from, door), from.getY() - door.getY());
            if (cost + PorterPlan.CLOSER_BY < depotCost && cost < bestCost && colony.warehouse().outpostCount(level, outpost, filter) >= minimum) {
                bestCost = cost;
                best = outpost;
            }
        }
        return best;
    }

    public void deposited(ServerLevel level, ColonyData.Outpost outpost, int items, BlockPos from) {
        if (items <= 0) {
            return;
        }
        BlockPos depot = colony.depot();
        BlockPos door = WorldUtil.bp(outpost.door);
        outpost.received += items;
        outpost.lastUse = level.getGameTime();
        outpost.strikes = 0;
        data().bump("outpost_items", items);
        data().bump("outpost_drops", 1);
        data().bump("walk_saved", PorterPlan.savedBlocks(PorterPlan.walkCost(horizontal(from, depot), from.getY() - depot.getY()),
                PorterPlan.walkCost(horizontal(from, door), from.getY() - door.getY())));
        colony.markDirty();
    }

    public void hauled(ServerLevel level, ColonyData.Outpost outpost, int items) {
        outpost.hauled += items;
        outpost.lastUse = Math.max(outpost.lastUse, level.getGameTime());
        data().bump("porter_items", items);
        data().bump("porter_trips", 1);
        colony.markDirty();
    }

    public void pickedUp(int items) {
        data().bump("porter_items", items);
        data().bump("porter_trips", 1);
        data().bump("porter_pickups", 1);
        colony.markDirty();
    }

    public void reached(ColonyData.Outpost outpost) {
        outpost.strikes = 0;
    }

    public void unreachable(ServerLevel level, ColonyData.Outpost outpost) {
        outpost.strikes++;
        outpost.blockedUntil = level.getGameTime() + BLOCK_TICKS * outpost.strikes;
        if (outpost.strikes >= MAX_STRIKES) {
            drop(level, outpost, "nobody could reach it", false);
        }
        colony.markDirty();
    }

    private void drop(ServerLevel level, ColonyData.Outpost outpost, String reason, boolean broken) {
        data().outposts.remove(outpost);
        claims.values().removeIf(task -> task.outpost() == outpost);
        if (broken) {
            data().buildings.removeIf(building -> building.door.equals(outpost.door) && PorterPlan.OUTPOST_KIND.equals(building.kind));
        }
        retryAt.remove(outpost.kind);
        colony.warehouse().invalidate();
        data().bump("outposts_dropped", 1);
        colony.journal(level, "outpost at " + outpost.door + " dropped: " + reason);
        colony.markDirty();
    }

    private void validate(ServerLevel level) {
        long now = level.getGameTime();
        Anchor mine = mineAnchor();
        for (ColonyData.Outpost outpost : new ArrayList<>(data().outposts)) {
            BlockPos door = WorldUtil.bp(outpost.door);
            if (!WorldUtil.loaded(level, door)) {
                continue;
            }
            int before = outpost.containers.size();
            outpost.containers.removeIf(pos -> WorldUtil.loaded(level, WorldUtil.bp(pos)) && Warehouse.container(level, WorldUtil.bp(pos)) == null);
            if (outpost.containers.size() != before) {
                colony.warehouse().invalidate();
                colony.journal(level, "outpost at " + outpost.door + " lost " + (before - outpost.containers.size()) + " barrels");
                colony.markDirty();
            }
            if (outpost.containers.isEmpty()) {
                drop(level, outpost, "its containers no longer exist", true);
                continue;
            }
            boolean idle = now - outpost.lastUse > STALE_TICKS && colony.warehouse().outpostItems(level, outpost) == 0;
            if (idle && !stillWanted(outpost, mine)) {
                drop(level, outpost, "not used for a long time", false);
            }
        }
    }

    private boolean stillWanted(ColonyData.Outpost outpost, Anchor mine) {
        BlockPos center = WorldUtil.bp(outpost.center);
        if (mine != null && horizontal(mine.pos(), center) <= MINE_NEAR) {
            return true;
        }
        if (treeSamples == 0) {
            return false;
        }
        Pos edge = PorterPlan.forestAnchor(data().depot, treeDx, treeDz, treeSamples);
        return horizontal(edge, center) <= FOREST_NEAR;
    }

    public Task claim(ServerLevel level, UUID porter, BlockPos from) {
        Task current = claims.get(porter);
        if (current != null && valid(level, current)) {
            return current;
        }
        claims.remove(porter);
        claims.keySet().removeIf(id -> {
            Colonist other = colony.colonists().get(id);
            return other == null || other.role() != Role.PORTER;
        });
        long now = level.getGameTime();
        Task best = null;
        double bestScore = -Double.MAX_VALUE;
        for (ColonyData.Outpost outpost : data().outposts) {
            BlockPos door = WorldUtil.bp(outpost.door);
            if (outpost.blockedUntil > now || !WorldUtil.loaded(level, door) || claimedElsewhere(porter, outpost)) {
                continue;
            }
            int items = colony.warehouse().outpostItems(level, outpost);
            if (!PorterPlan.worthHauling(items, now - outpost.lastUse)) {
                continue;
            }
            double score = items - horizontal(from, door) * 0.5;
            if (score > bestScore) {
                bestScore = score;
                best = new Task(Type.HAUL, outpost, null);
            }
        }
        if (best == null) {
            best = pickup(level, porter, from);
        }
        if (best != null) {
            claims.put(porter, best);
        }
        return best;
    }

    private boolean claimedElsewhere(UUID porter, ColonyData.Outpost outpost) {
        for (Map.Entry<UUID, Task> entry : claims.entrySet()) {
            if (!entry.getKey().equals(porter) && entry.getValue().outpost() == outpost) {
                return true;
            }
        }
        return false;
    }

    private boolean builderClaimed(UUID porter, UUID builder) {
        for (Map.Entry<UUID, Task> entry : claims.entrySet()) {
            if (!entry.getKey().equals(porter) && builder.equals(entry.getValue().builder())) {
                return true;
            }
        }
        return false;
    }

    private Task pickup(ServerLevel level, UUID porter, BlockPos from) {
        BlockPos depot = colony.depot();
        UUID best = null;
        double bestScore = -Double.MAX_VALUE;
        for (Map.Entry<UUID, Colonist> entry : colony.colonists().entrySet()) {
            Colonist colonist = entry.getValue();
            if (colonist.role() != Role.BUILDER || builderClaimed(porter, entry.getKey())
                    || !(level.getEntity(entry.getKey()) instanceof Villager villager) || !villager.isAlive()) {
                continue;
            }
            Job job = colonist.job();
            if (job == null || !Colonist.isControlled(villager) || horizontal(villager.blockPosition(), depot) <= PorterPlan.FAR_SITE) {
                continue;
            }
            int slots = job.cargoSlots(villager);
            if (slots < PorterPlan.PICKUP_SLOTS) {
                continue;
            }
            double score = slots * 4.0 - horizontal(villager.blockPosition(), from) * 0.1;
            if (score > bestScore) {
                bestScore = score;
                best = entry.getKey();
            }
        }
        return best == null ? null : new Task(Type.PICKUP, null, best);
    }

    private boolean valid(ServerLevel level, Task task) {
        if (task.type() == Type.HAUL) {
            ColonyData.Outpost outpost = task.outpost();
            return data().outposts.contains(outpost) && outpost.blockedUntil <= level.getGameTime()
                    && colony.warehouse().outpostItems(level, outpost) > 0;
        }
        if (!(level.getEntity(task.builder()) instanceof Villager villager) || !villager.isAlive()) {
            return false;
        }
        Colonist colonist = colony.colonists().get(task.builder());
        return colonist != null && colonist.job() != null && colonist.job().cargoSlots(villager) > 0;
    }

    public void release(UUID porter) {
        claims.remove(porter);
    }

    public int porterDemand(ServerLevel level) {
        long now = level.getGameTime();
        int haul = 0;
        for (ColonyData.Outpost outpost : data().outposts) {
            if (!outpost.containers.isEmpty()
                    && PorterPlan.busy(colony.warehouse().outpostItems(level, outpost), now - outpost.lastUse)) {
                haul++;
            }
        }
        BlockPos depot = colony.depot();
        int far = 0;
        for (ColonyData.Project project : colony.construction().projects()) {
            if ("clear".equals(project.phase) && horizontal(project.building.door, depot) > PorterPlan.FAR_SITE) {
                far++;
            }
        }
        return PorterPlan.portersWanted(haul, far, colony.adultCount(level));
    }

    public JsonObject debug(ServerLevel level) {
        JsonObject json = new JsonObject();
        JsonArray list = new JsonArray();
        for (ColonyData.Outpost outpost : data().outposts) {
            JsonObject entry = new JsonObject();
            entry.addProperty("kind", outpost.kind);
            entry.addProperty("door", outpost.door.toString());
            entry.addProperty("barrels", outpost.containers.size());
            entry.addProperty("items", colony.warehouse().outpostItems(level, outpost));
            entry.addProperty("free", colony.warehouse().outpostFree(level, outpost));
            entry.addProperty("received", outpost.received);
            entry.addProperty("hauled", outpost.hauled);
            entry.addProperty("strikes", outpost.strikes);
            entry.addProperty("blockedFor", Math.max(0, outpost.blockedUntil - level.getGameTime()));
            list.add(entry);
        }
        json.add("list", list);
        json.addProperty("treeSamples", treeSamples);
        json.addProperty("treeAverage", Math.round(treeAverage() * 10) / 10.0);
        json.addProperty("porterDemand", porterDemand(level));
        JsonObject troubles = new JsonObject();
        trouble.forEach(troubles::addProperty);
        json.add("trouble", troubles);
        json.addProperty("itemsCarried", data().stat("outpost_items") + data().stat("porter_items"));
        json.addProperty("walkSaved", data().stat("walk_saved"));
        return json;
    }
}
