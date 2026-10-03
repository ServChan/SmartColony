package org.lts.smartcolony.work;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.util.WorldUtil;

public final class Mover {
    public enum Result {
        MOVING,
        ARRIVED,
        FAILED
    }

    private static final int LONG_HOP = 32;

    private BlockPos target;
    private double bestDistance = Double.MAX_VALUE;
    private int noProgress;
    private int total;
    private int repath;
    private int failedPaths;
    private final List<BlockPos> openedPassages = new ArrayList<>();
    private String note = "";

    public void reset() {
        target = null;
        bestDistance = Double.MAX_VALUE;
        noProgress = 0;
        total = 0;
        repath = 0;
        failedPaths = 0;
        note = "";
    }

    public String note() {
        return note;
    }

    public BlockPos target() {
        return target;
    }

    public static double distance(Villager villager, BlockPos pos) {
        return villager.position().distanceTo(Vec3.atBottomCenterOf(pos));
    }

    public Result tick(ServerLevel level, Villager villager, BlockPos goal, double reach, double speed) {
        return tick(level, villager, goal, reach, speed, true);
    }

    public Result tick(ServerLevel level, Villager villager, BlockPos goal, double reach, double speed, boolean allowRescue) {
        if (target == null || !target.equals(goal)) {
            reset();
            target = goal.immutable();
        }
        total++;
        handlePassages(level, villager);
        double distance = distance(villager, goal);
        if (distance <= reach) {
            villager.getNavigation().stop();
            return Result.ARRIVED;
        }
        if (distance < bestDistance - 0.3) {
            bestDistance = distance;
            noProgress = 0;
        } else {
            noProgress++;
        }
        if (repath > 0) {
            repath--;
        }
        if (villager.getNavigation().isDone() || repath <= 0) {
            BlockPos hop = hop(level, villager, goal);
            if (hop == null) {
                note = "waiting for chunk";
                return Result.MOVING;
            }
            int reachRange = hop.equals(goal) ? (int) Math.max(0, Math.floor(reach - 0.5)) : 2;
            Path path = villager.getNavigation().createPath(hop, reachRange);
            if (path == null) {
                failedPaths++;
                note = "no path x" + failedPaths;
            } else {
                villager.getNavigation().moveTo(path, speed);
                note = path.canReach() ? "path" : "partial path";
            }
            repath = 30;
        }
        if (noProgress > 200 || failedPaths > 8 || total > 3000) {
            if (allowRescue && teleportAllowed(level, villager) && rescue(level, villager, goal)) {
                reset();
                return Result.ARRIVED;
            }
            reset();
            return Result.FAILED;
        }
        return Result.MOVING;
    }

    private BlockPos hop(ServerLevel level, Villager villager, BlockPos goal) {
        Vec3 from = villager.position();
        double dx = goal.getX() + 0.5 - from.x;
        double dz = goal.getZ() + 0.5 - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal <= LONG_HOP + 8) {
            return WorldUtil.loaded(level, goal) ? goal : null;
        }
        int x = (int) Math.floor(from.x + dx / horizontal * LONG_HOP);
        int z = (int) Math.floor(from.z + dz / horizontal * LONG_HOP);
        BlockPos column = new BlockPos(x, 0, z);
        if (!WorldUtil.loaded(level, column)) {
            return null;
        }
        return WorldUtil.surface(level, x, z);
    }

    private static boolean teleportAllowed(ServerLevel level, Villager villager) {
        if (!SmartColonyConfig.get().stuckTeleport) {
            return false;
        }
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(villager) < 24 * 24 && player.hasLineOfSight(villager)) {
                return false;
            }
        }
        return true;
    }

    public static boolean rescue(ServerLevel level, Villager villager, BlockPos goal) {
        for (int r = 0; r <= 2; r++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        BlockPos candidate = goal.offset(dx, dy, dz);
                        if (WorldUtil.standable(level, candidate)) {
                            villager.getNavigation().stop();
                            villager.teleportTo(candidate.getX() + 0.5, candidate.getY(), candidate.getZ() + 0.5);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private void handlePassages(ServerLevel level, Villager villager) {
        List<BlockPos> check = new ArrayList<>(3);
        check.add(villager.blockPosition());
        Path path = villager.getNavigation().getPath();
        if (path != null && !path.isDone()) {
            int index = path.getNextNodeIndex();
            for (int i = index; i < Math.min(path.getNodeCount(), index + 2); i++) {
                Node node = path.getNode(i);
                check.add(new BlockPos(node.x, node.y, node.z));
            }
        }
        for (BlockPos pos : check) {
            if (pos.distSqr(villager.blockPosition()) > 4) {
                continue;
            }
            open(level, villager, pos);
        }
        Iterator<BlockPos> it = openedPassages.iterator();
        while (it.hasNext()) {
            BlockPos pos = it.next();
            if (villager.blockPosition().distSqr(pos) > 6.5) {
                close(level, villager, pos);
                it.remove();
            }
        }
    }

    private void open(ServerLevel level, Villager villager, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock door && state.is(BlockTags.MOB_INTERACTABLE_DOORS) && !door.isOpen(state)) {
            door.setOpen(villager, level, state, pos, true);
            remember(pos);
        } else if (state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) {
            level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, true), Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
            level.playSound(null, pos, SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS, 1.0F, 1.0F);
            remember(pos);
        }
    }

    private void remember(BlockPos pos) {
        BlockPos key = pos.immutable();
        if (!openedPassages.contains(key)) {
            openedPassages.add(key);
        }
    }

    private static void close(ServerLevel level, Villager villager, BlockPos pos) {
        if (!WorldUtil.loaded(level, pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock door && door.isOpen(state)) {
            door.setOpen(villager, level, state, pos, false);
        } else if (state.getBlock() instanceof FenceGateBlock && state.getValue(FenceGateBlock.OPEN)) {
            level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, false), Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
            level.playSound(null, pos, SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }

    public void closeAll(ServerLevel level, Villager villager) {
        for (BlockPos pos : openedPassages) {
            close(level, villager, pos);
        }
        openedPassages.clear();
    }
}
