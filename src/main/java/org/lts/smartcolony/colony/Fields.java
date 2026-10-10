package org.lts.smartcolony.colony;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import org.lts.smartcolony.core.Blueprints;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.FarmKeepOut;
import org.lts.smartcolony.core.FarmPlan;
import org.lts.smartcolony.core.Pier;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.TreeFarmPlan;
import org.lts.smartcolony.util.WorldUtil;

public final class Fields {
    private static final long REBUILD_TICKS = 300;
    private static final long FORCED_REBUILD_TICKS = 3000;
    private static final int PLAZA_RADIUS = 8;
    private static final int BUILDING_MARGIN = 3;
    private static final int DOOR_APRON = 3;
    private static final int MIN_LAND = 10;

    record Margins(int plaza, int building, int door, int road, boolean trees) {
    }

    static final Margins FIELDS = new Margins(PLAZA_RADIUS, BUILDING_MARGIN, DOOR_APRON, FarmKeepOut.ROAD_MARGIN, false);
    static final Margins GROVES = new Margins(10, 5, 5, 3, true);

    private final Colony colony;
    private FarmKeepOut keepOut = new FarmKeepOut();
    private long builtAt = -100000;
    private int signature = -1;
    private FarmKeepOut groves = new FarmKeepOut();
    private long grovesBuiltAt = -100000;
    private int grovesSignature = -1;

    Fields(Colony colony) {
        this.colony = colony;
    }

    private ColonyData data() {
        return colony.data();
    }

    private int currentSignature() {
        ColonyData d = data();
        int hash = d.buildings.size() * 31 + d.projects.size() * 17 + d.roads.size() * 7 + d.pendingRoad.size() * 5
                + d.lamps.size() * 3 + d.graves.size() + d.outposts.size() * 11 + (d.pen == null ? 0 : 13)
                + (d.mine == null ? 0 : 19 + d.mine.generation) + d.mines.size() * 37 + (d.wall == null ? 0 : d.wall.columns.size() * 23);
        return hash;
    }

    private FarmKeepOut zones(ServerLevel level) {
        long now = level.getGameTime();
        int sign = currentSignature();
        if (now - builtAt >= FORCED_REBUILD_TICKS || sign != signature && now - builtAt >= REBUILD_TICKS) {
            keepOut = build(level);
            builtAt = now;
            signature = sign;
        }
        return keepOut;
    }

    private FarmKeepOut groveZones(ServerLevel level) {
        long now = level.getGameTime();
        int sign = currentSignature() + data().farms.size() * 29;
        if (now - grovesBuiltAt >= FORCED_REBUILD_TICKS || sign != grovesSignature && now - grovesBuiltAt >= REBUILD_TICKS) {
            groves = build(level, GROVES);
            grovesBuiltAt = now;
            grovesSignature = sign;
        }
        return groves;
    }

    public boolean treeBlocked(ServerLevel level, int x, int z) {
        return groveZones(level).blocked(x, z) || FarmKeepOut.terrainBlocked(colony.survey().map(), x, z);
    }

    private FarmKeepOut build(ServerLevel level) {
        return build(level, FIELDS);
    }

    private FarmKeepOut build(ServerLevel level, Margins margins) {
        ColonyData d = data();
        FarmKeepOut zones = new FarmKeepOut(margins.road());
        zones.addDisc(d.center.x(), d.center.z(), margins.plaza());
        zones.addDisc(d.depot.x(), d.depot.z(), margins.plaza() - 1);
        for (ColonyData.Building building : d.buildings) {
            if (margins.trees() && TreeFarmPlan.KIND.equals(building.kind)) {
                continue;
            }
            addBuilding(zones, building, margins);
        }
        for (ColonyData.Project project : d.projects) {
            if (margins.trees() && TreeFarmPlan.KIND.equals(project.building.kind)) {
                continue;
            }
            addBuilding(zones, project.building, margins);
        }
        if (margins.trees()) {
            for (Pos farm : d.farms) {
                zones.addDisc(farm.x(), farm.z(), 9);
            }
        }
        for (Pos road : d.roads) {
            zones.addCell(road.x(), road.z());
        }
        for (Pos road : d.pendingRoad) {
            zones.addCell(road.x(), road.z());
        }
        for (Pos torch : d.torches) {
            zones.addCell(torch.x(), torch.z());
        }
        for (ColonyData.Lamp lamp : d.lamps) {
            zones.addCell(lamp.pos.x(), lamp.pos.z());
        }
        if (d.wall != null) {
            for (Pos column : d.wall.columns) {
                zones.addCell(column.x(), column.z());
            }
            for (Pos gate : d.wall.gates) {
                zones.addDisc(gate.x(), gate.z(), 4);
            }
        }
        for (ColonyData.Mine mine : colony.allMines()) {
            if (mine.quarry()) {
                zones.addBox(mine.corner.x(), mine.corner.z(), mine.corner.x() + mine.width, mine.corner.z() + mine.width, BUILDING_MARGIN);
            } else if (mine.entrance != null) {
                zones.addDisc(mine.entrance.x(), mine.entrance.z(), 5);
            }
            if (mine.shed != null) {
                zones.addBox(mine.shed.x(), mine.shed.z(), mine.shed.x() + Improvements.SHED_LENGTH, mine.shed.z() + Improvements.SHED_WIDTH, 2);
            }
        }
        if (d.pen != null) {
            zones.addBox(d.pen.min.x(), d.pen.min.z(), d.pen.max.x(), d.pen.max.z(), margins.building());
            zones.addDisc(d.pen.gate.x(), d.pen.gate.z(), margins.door());
        }
        for (Pos grave : d.graves) {
            zones.addDisc(grave.x(), grave.z(), 2);
        }
        if (!margins.trees()) {
            for (Pos tree : d.plantedTrees) {
                zones.addDisc(tree.x(), tree.z(), 2);
            }
        }
        for (ColonyData.Outpost outpost : d.outposts) {
            zones.addDisc(outpost.door.x(), outpost.door.z(), 4);
            zones.addDisc(outpost.center.x(), outpost.center.z(), 5);
        }
        for (ColonyData.Building building : d.buildings) {
            if (!Pier.waterKind(building.kind) && !building.onWater) {
                continue;
            }
            for (int[] f : Blueprints.get(building.blueprint).fishing()) {
                BlockPos stand = colony.construction().world(building, f[0], f[1], f[2]);
                zones.addDisc(stand.getX(), stand.getZ(), 4);
            }
        }
        for (org.lts.smartcolony.core.Decree.Zone zone : d.zones) {
            zones.addDisc(zone.x(), zone.z(), zone.radius() + 1);
        }
        for (org.lts.smartcolony.core.Clearance.Door door : colony.construction().doors()) {
            org.lts.smartcolony.core.Clearance.Box corridor = door.corridor();
            zones.addBox(corridor.minX(), corridor.minZ(), corridor.maxX(), corridor.maxZ(), 0);
        }
        return zones;
    }

    private static void addBuilding(FarmKeepOut zones, ColonyData.Building building, Margins margins) {
        zones.addBox(building.min.x(), building.min.z(), building.max.x(), building.max.z(), margins.building());
        zones.addDisc(building.door.x(), building.door.z(), margins.door() + 1);
    }

    public boolean blocked(ServerLevel level, int x, int z) {
        return zones(level).blocked(x, z) || FarmKeepOut.terrainBlocked(colony.survey().map(), x, z);
    }

    public boolean tillable(ServerLevel level, BlockPos pos) {
        if (blocked(level, pos.getX(), pos.getZ())) {
            return false;
        }
        if (colony.waterVillage(level)) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                if (!level.getFluidState(pos.relative(direction)).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    public int budget(ServerLevel level) {
        return FarmPlan.budget(colony.adultCount(level), colony.foodPoints(level));
    }

    public boolean mayAddField(ServerLevel level) {
        return FarmPlan.mayAddAnchor(data().farms.size(), colony.adultCount(level), colony.foodPoints(level));
    }

    public boolean[] fallow(ServerLevel level) {
        return FarmPlan.fallow(data().farms, data().center.x(), data().center.z(), FarmPlan.ceiling(colony.adultCount(level)));
    }

    private int landAround(ServerLevel level, BlockPos water) {
        int land = 0;
        for (BlockPos check : BlockPos.betweenClosed(water.offset(-4, 0, -4), water.offset(4, 0, 4))) {
            BlockState state = level.getBlockState(check);
            if ((state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)) && WorldUtil.isAirLike(level.getBlockState(check.above()))
                    && tillable(level, check)) {
                land++;
            }
        }
        return land;
    }

    public boolean expand(ServerLevel level) {
        if (!mayAddField(level)) {
            return false;
        }
        RandomSource random = level.getRandom();
        List<Pos> anchors = data().farms;
        int radius = Math.max(16, data().radius + 8);
        for (int attempt = 0; attempt < 120; attempt++) {
            int x;
            int z;
            if (anchors.isEmpty()) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double distance = PLAZA_RADIUS + 6 + random.nextDouble() * Math.max(1, radius - PLAZA_RADIUS - 6);
                x = colony.center().getX() + (int) Math.round(Math.cos(angle) * distance);
                z = colony.center().getZ() + (int) Math.round(Math.sin(angle) * distance);
            } else {
                Pos base = anchors.get(random.nextInt(anchors.size()));
                x = base.x() + random.nextInt(FarmPlan.CLUSTER_REACH * 2 + 1) - FarmPlan.CLUSTER_REACH;
                z = base.z() + random.nextInt(FarmPlan.CLUSTER_REACH * 2 + 1) - FarmPlan.CLUSTER_REACH;
            }
            if (!WorldUtil.loaded(level, new BlockPos(x, 0, z)) || !FarmPlan.clusterOk(x, z, anchors) || blocked(level, x, z)) {
                continue;
            }
            BlockPos water = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
            if (!level.getFluidState(water).isSourceOfType(Fluids.WATER) || colony.isProtected(water.above(), false)) {
                continue;
            }
            if (landAround(level, water) < MIN_LAND) {
                continue;
            }
            BlockPos seed = null;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos side = water.relative(direction);
                BlockState state = level.getBlockState(side);
                if ((state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)) && WorldUtil.isAirLike(level.getBlockState(side.above()))
                        && !blocked(level, side.getX(), side.getZ())) {
                    seed = side;
                    break;
                }
            }
            if (seed == null) {
                continue;
            }
            colony.addFarm(water);
            if (!level.getBlockState(seed.above()).isAir()) {
                level.destroyBlock(seed.above(), true);
            }
            level.setBlock(seed, Blocks.FARMLAND.defaultBlockState(), 3);
            return true;
        }
        return false;
    }

    public BlockPos pondSite(ServerLevel level) {
        if (!mayAddField(level)) {
            return null;
        }
        RandomSource random = level.getRandom();
        List<Pos> anchors = data().farms;
        for (int attempt = 0; attempt < 80; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            int x;
            int z;
            if (anchors.isEmpty()) {
                double distance = PLAZA_RADIUS + 6 + random.nextDouble() * 14;
                x = colony.center().getX() + (int) Math.round(Math.cos(angle) * distance);
                z = colony.center().getZ() + (int) Math.round(Math.sin(angle) * distance);
            } else {
                Pos base = anchors.get(random.nextInt(anchors.size()));
                double distance = FarmPlan.MIN_SPACING + random.nextDouble() * (FarmPlan.CLUSTER_REACH - FarmPlan.MIN_SPACING);
                x = base.x() + (int) Math.round(Math.cos(angle) * distance);
                z = base.z() + (int) Math.round(Math.sin(angle) * distance);
            }
            if (!WorldUtil.loaded(level, new BlockPos(x - 5, 0, z - 5)) || !WorldUtil.loaded(level, new BlockPos(x + 5, 0, z + 5))
                    || !FarmPlan.clusterOk(x, z, anchors)) {
                continue;
            }
            BlockPos ground = WorldUtil.surface(level, x, z).below();
            boolean flat = true;
            for (int dx = -2; dx <= 2 && flat; dx++) {
                for (int dz = -2; dz <= 2 && flat; dz++) {
                    BlockPos column = WorldUtil.surface(level, x + dx, z + dz).below();
                    BlockState state = level.getBlockState(column);
                    if (column.getY() != ground.getY() || !(state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT))
                            || colony.isProtected(column.above(), false) || blocked(level, column.getX(), column.getZ())
                            || level.getHeight(Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ()) > column.getY() + 1) {
                        flat = false;
                    }
                }
            }
            if (flat) {
                return ground.immutable();
            }
        }
        return null;
    }
}
