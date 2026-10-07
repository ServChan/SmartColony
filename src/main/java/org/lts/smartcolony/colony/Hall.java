package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.lts.smartcolony.core.Blueprint;
import org.lts.smartcolony.core.Blueprints;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.GraveNames;
import org.lts.smartcolony.core.HallRest;
import org.lts.smartcolony.core.Mayor;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.TownHall;
import org.lts.smartcolony.core.Valuables;
import org.lts.smartcolony.util.WorldUtil;
import org.lts.smartcolony.work.Colonist;

public final class Hall {
    public record Summary(String head, boolean headHoused, int containers, int emeralds, int stacks) {
    }

    private final Colony colony;
    private String scanned = "";
    private List<BlockPos> cells = List.of();
    private List<String> restIds = List.of();
    private BlockPos bed;
    private int tickCount;

    Hall(Colony colony) {
        this.colony = colony;
    }

    private ColonyData data() {
        return colony.data();
    }

    ColonyData.Building building() {
        return TownHallBuilder.standing(data());
    }

    public boolean finished() {
        ColonyData.Building building = building();
        return building != null && !colony.construction().upgrading(building);
    }

    public int stage() {
        return TownHall.stage(data());
    }

    public boolean restOpen() {
        return !cells.isEmpty();
    }

    public BlockPos restSpot(UUID uuid) {
        if (cells.isEmpty()) {
            return null;
        }
        int slot = HallRest.slot(restIds, uuid.toString(), cells.size());
        return slot < 0 ? null : cells.get(slot);
    }

    public String headName() {
        return data().head.isEmpty() ? "" : GraveNames.of(data().head);
    }

    public boolean isHead(UUID uuid) {
        return !data().head.isEmpty() && data().head.equals(uuid.toString());
    }

    public void tick(ServerLevel level) {
        tickCount++;
        ensureHead(level);
        ColonyData.Building building = building();
        if (building == null || colony.construction().upgrading(building)) {
            cells = List.of();
            return;
        }
        if (!building.blueprint.equals(scanned) && scan(level, building)) {
            scanned = building.blueprint;
        }
        refreshCells(level, building);
        cleanTreasury(level);
        if (tickCount % 6 == 0 && !data().treasury.isEmpty()) {
            colony.warehouse().consolidateValuables(level, 4);
        }
        houseHead(level, building);
    }

    private void ensureHead(ServerLevel level) {
        List<Mayor.Candidate> candidates = new ArrayList<>();
        for (Map.Entry<UUID, Colonist> entry : colony.colonists().entrySet()) {
            ColonyData.Member member = data().members.get(entry.getKey().toString());
            if (member != null && level.getEntity(entry.getKey()) instanceof Villager villager && villager.isAlive()
                    && !villager.isBaby()) {
                candidates.add(new Mayor.Candidate(entry.getKey().toString(), member.since, Mayor.totalXp(member.xp)));
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        if (!data().head.isEmpty() && data().members.containsKey(data().head)) {
            return;
        }
        String previous = data().head;
        String chosen = Mayor.choose(candidates);
        if (chosen.isEmpty()) {
            return;
        }
        data().head = chosen;
        data().headHoused = false;
        String name = GraveNames.of(chosen);
        if (previous.isEmpty()) {
            colony.journal(level, "head of the colony chosen: " + name);
            colony.notify(level, Component.translatable("smartcolony.chat.head_chosen", name));
        } else {
            colony.journal(level, "the head of the colony is gone; " + name + " takes over");
            colony.notify(level, Component.translatable("smartcolony.chat.head_new", name));
        }
        colony.markDirty();
    }

    private boolean scan(ServerLevel level, ColonyData.Building building) {
        BlockPos min = WorldUtil.bp(building.min);
        BlockPos max = WorldUtil.bp(building.max);
        if (!WorldUtil.loaded(level, min) || !WorldUtil.loaded(level, max)) {
            return false;
        }
        List<Pos> found = new ArrayList<>();
        BlockPos foundBed = null;
        for (BlockPos pos : BlockPos.betweenClosed(min, max.above(1))) {
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.CHEST) || state.is(Blocks.BARREL) || state.is(Blocks.TRAPPED_CHEST)) {
                Pos p = WorldUtil.pos(pos);
                if (Warehouse.container(level, pos) != null && !inStorage(p)) {
                    found.add(p);
                }
            } else if (foundBed == null && state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD) {
                foundBed = pos.immutable();
            }
        }
        bed = foundBed;
        boolean first = data().treasury.isEmpty() && !found.isEmpty();
        if (!found.equals(data().treasury)) {
            data().treasury = found;
            colony.warehouse().invalidate();
            colony.markDirty();
        }
        if (first) {
            colony.journal(level, "treasury opened in the town hall: " + found.size() + " containers");
            colony.notify(level, Component.translatable("smartcolony.chat.treasury_ready", found.size()));
        }
        return true;
    }

    private boolean inStorage(Pos pos) {
        for (List<Pos> list : data().storage.values()) {
            if (list.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    private void cleanTreasury(ServerLevel level) {
        boolean changed = data().treasury.removeIf(pos -> WorldUtil.loaded(level, WorldUtil.bp(pos))
                && Warehouse.container(level, WorldUtil.bp(pos)) == null);
        if (changed) {
            colony.warehouse().invalidate();
            colony.markDirty();
        }
    }

    private void refreshCells(ServerLevel level, ColonyData.Building building) {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<UUID, Colonist> entry : colony.colonists().entrySet()) {
            if (entry.getValue().role() != Role.GUARD) {
                ids.add(entry.getKey().toString());
            }
        }
        restIds = ids;
        Blueprint blueprint = Blueprints.get(building.blueprint);
        List<BlockPos> valid = new ArrayList<>();
        for (int[] cell : HallRest.cells(blueprint)) {
            BlockPos pos = colony.construction().world(building, cell[0], cell[1], cell[2]);
            if (WorldUtil.loaded(level, pos) && WorldUtil.standable(level, pos)) {
                valid.add(pos);
            }
        }
        cells = valid;
    }

    private void houseHead(ServerLevel level, ColonyData.Building building) {
        String head = data().head;
        if (head.isEmpty() || bed == null || !Mayor.movesIn(TownHall.stageOf(building.blueprint), true)) {
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(head);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!(level.getEntity(id) instanceof Villager villager) || !villager.isAlive() || villager.isBaby()) {
            return;
        }
        BlockState state = level.getBlockState(bed);
        if (!(state.getBlock() instanceof BedBlock)) {
            bed = null;
            return;
        }
        GlobalPos target = GlobalPos.of(level.dimension(), bed);
        if (target.equals(villager.getBrain().getMemory(MemoryModuleType.HOME).orElse(null))) {
            return;
        }
        for (Map.Entry<UUID, Colonist> entry : colony.colonists().entrySet()) {
            if (!entry.getKey().equals(id) && level.getEntity(entry.getKey()) instanceof Villager other
                    && target.equals(other.getBrain().getMemory(MemoryModuleType.HOME).orElse(null))) {
                other.releasePoi(MemoryModuleType.HOME);
                other.getBrain().eraseMemory(MemoryModuleType.HOME);
            }
        }
        villager.releasePoi(MemoryModuleType.HOME);
        BlockPos claim = bed;
        level.getPoiManager().take(type -> type.is(PoiTypes.HOME), (holder, pos) -> pos.equals(claim), claim, 1);
        villager.getBrain().setMemory(MemoryModuleType.HOME, target);
        if (!data().headHoused) {
            data().headHoused = true;
            String name = GraveNames.of(head);
            colony.journal(level, "the head of the colony " + name + " moved into the town hall");
            colony.notify(level, Component.translatable("smartcolony.chat.head_moved_in", name));
            colony.markDirty();
        }
    }

    public Summary summary(ServerLevel level) {
        Map<String, Integer> contents = colony.warehouse().treasuryContents(level);
        int stacks = 0;
        for (int count : contents.values()) {
            stacks += count > 0 ? 1 : 0;
        }
        return new Summary(headName(), data().headHoused, data().treasury.size(), Valuables.emeralds(contents), stacks);
    }
}
