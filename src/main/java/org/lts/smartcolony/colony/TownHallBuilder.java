package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.lts.smartcolony.core.Blueprint;
import org.lts.smartcolony.core.Blueprints;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.TownHall;
import org.lts.smartcolony.util.WorldUtil;

final class TownHallBuilder {
    private TownHallBuilder() {
    }

    static ColonyData.Building standing(ColonyData data) {
        ColonyData.Building best = null;
        for (ColonyData.Building building : data.buildings) {
            if (TownHall.stageOf(building.blueprint) > 0
                    && (best == null || TownHall.stageOf(building.blueprint) > TownHall.stageOf(best.blueprint))) {
                best = building;
            }
        }
        return best;
    }

    static ColonyData.Project upgrade(ColonyData.Building standing, String blueprintId) {
        Blueprint blueprint = Blueprints.get(blueprintId);
        ColonyData.Project project = new ColonyData.Project();
        project.upgrade = true;
        project.swaps = new LinkedHashMap<>(standing.swaps);
        ColonyData.Building building = project.building;
        building.blueprint = blueprintId;
        building.kind = blueprint.kind();
        building.wood = standing.wood;
        building.rotation = standing.rotation;
        building.beds = blueprint.beds();
        building.origin = standing.origin;
        building.door = standing.door;
        building.min = standing.min;
        building.max = standing.max;
        return project;
    }

    static void apply(ColonyData.Building standing, ColonyData.Project project) {
        standing.blueprint = project.building.blueprint;
        standing.kind = project.building.kind;
        standing.min = project.building.min;
        standing.max = project.building.max;
        standing.swaps = new LinkedHashMap<>(project.swaps);
    }

    static Map<String, Integer> pending(Construction construction, ServerLevel level, ColonyData.Project project) {
        Map<String, Integer> required = new TreeMap<>();
        for (Blueprint.Cell cell : construction.cells(project)) {
            if (construction.placed(level, project, cell)) {
                continue;
            }
            Blueprint.Material material = Blueprint.material(cell.state());
            if (material != null) {
                required.merge(material.item(), material.count(), Integer::sum);
            }
        }
        return required;
    }

    static Map<String, Integer> materials(Construction construction, ServerLevel level, String blueprintId, String wood) {
        if (TownHall.stageOf(blueprintId) <= 1) {
            return Blueprints.get(blueprintId).materials(wood);
        }
        ColonyData.Building standing = standing(construction.colonyData());
        if (standing == null) {
            return Blueprints.get(blueprintId).materials(wood);
        }
        return pending(construction, level, upgrade(standing, blueprintId));
    }

    static List<BlockPos> replaced(Construction construction, ServerLevel level, ColonyData.Project project) {
        List<BlockPos> list = new ArrayList<>();
        for (Blueprint.Cell cell : construction.cells(project)) {
            if (cell.y() < 1) {
                continue;
            }
            BlockPos pos = construction.world(project.building, cell.x(), cell.y(), cell.z());
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || WorldUtil.isAirLike(state) && state.getFluidState().isEmpty()) {
                continue;
            }
            if (!construction.placed(level, project, cell)) {
                list.add(pos.immutable());
            }
        }
        return list;
    }

    static void finished(ServerLevel level, Colony colony, ColonyData.Building building) {
        int stage = TownHall.stageOf(building.blueprint);
        colony.journal(level, "town hall finished: stage " + stage + " of " + TownHall.STAGES + " at " + building.origin);
        colony.notify(level, Component.translatable("smartcolony.chat.townhall_stage", stage, TownHall.STAGES));
    }
}
