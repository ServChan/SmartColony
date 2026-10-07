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

    public static final int SCREEN_OFF = 0;
    public static final int SCREEN_STRICT = 1;
    public static final int SCREEN_TOLERANT = 2;
    private static final int LONG_HOP = 32;

    private BlockPos target;
    private double bestDistance = Double.MAX_VALUE;
    private int noProgress;
    private int total;
    private int repath;
    private int failedPaths;
    private final List<BlockPos> openedPassages = new ArrayList<>();
    private String note = "";
    private java.util.function.Predicate<BlockPos> guarded = pos -> false;
    private int carveCooldown;
    private int screen = SCREEN_STRICT;
    private Runnable avoided = () -> {
    };
    private BlockPos avoidedGoal;
    private BlockPos screenGoal;
    private long screenTick;
    private boolean screenUnsafe;

    public void reset() {
        target = null;
        bestDistance = Double.MAX_VALUE;
        noProgress = 0;
        total = 0;
        repath = 0;
        failedPaths = 0;
        note = "";
    }

    public void guard(java.util.function.Predicate<BlockPos> area) {
        guarded = area;
    }

    public void screen(int mode) {
        screen = mode;
    }

    public void onAvoided(Runnable action) {
        avoided = action;
    }

    private boolean unsafeGoal(ServerLevel level, BlockPos goal) {
        long now = level.getGameTime();
        if (screenGoal == null || !screenGoal.equals(goal) || now - screenTick >= 20) {
            screenGoal = goal.immutable();
            screenTick = now;
            screenUnsafe = Hazards.unsafeGoal(level, goal, screen == SCREEN_TOLERANT);
        }
        return screenUnsafe;
    }

    public String note() {
        return note;
    }

    public int failures() {
        return failedPaths;
    }

    public BlockPos target() {
        return target;
    }

    public static void keepAfloat(Villager villager) {
        if ((villager.isInWater() && villager.getFluidHeight(net.minecraft.tags.FluidTags.WATER) > villager.getFluidJumpThreshold())
                || villager.isInLava()) {
            if (villager.getRandom().nextFloat() < 0.8F) {
                villager.getJumpControl().jump();
            }
        }
    }

    public static double distance(Villager villager, BlockPos pos) {
        return villager.position().distanceTo(Vec3.atBottomCenterOf(pos));
    }

    public Result tick(ServerLevel level, Villager villager, BlockPos goal, double reach, double speed) {
        return tick(level, villager, goal, reach, speed, true);
    }

    public Result tick(ServerLevel level, Villager villager, BlockPos goal, double reach, double speed, boolean allowRescue) {
        if (screen != SCREEN_OFF && unsafeGoal(level, goal)) {
            if (!goal.equals(avoidedGoal)) {
                avoidedGoal = goal.immutable();
                avoided.run();
            }
            villager.getNavigation().stop();
            reset();
            note = "hazard at goal";
            return Result.FAILED;
        }
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
        if (carveCooldown > 0) {
            carveCooldown--;
        } else if ((inPit(level, villager) || (noProgress > 60 || failedPaths >= 2) && goal.getY() > villager.getBlockY() + 1)
                && carveStep(level, villager, goal)) {
            carveCooldown = 8;
            noProgress = Math.max(0, noProgress - 20);
            failedPaths = 0;
            repath = 0;
            note = "digging out";
            return Result.MOVING;
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

    private static boolean inPit(ServerLevel level, Villager villager) {
        if (!villager.onGround() || villager.isInWater()) {
            return false;
        }
        BlockPos feet = villager.blockPosition();
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(direction);
            boolean walk = WorldUtil.passable(level, side) && WorldUtil.passable(level, side.above());
            boolean climb = WorldUtil.passable(level, side.above()) && WorldUtil.passable(level, side.above(2))
                    && WorldUtil.passable(level, feet.above(2));
            if (walk || climb) {
                return false;
            }
        }
        return true;
    }

    private boolean carveStep(ServerLevel level, Villager villager, BlockPos goal) {
        BlockPos feet = villager.blockPosition();
        if (!villager.onGround() || villager.isInWater()) {
            return false;
        }
        int dx = goal.getX() - feet.getX();
        int dz = goal.getZ() - feet.getZ();
        List<net.minecraft.core.Direction> order = new ArrayList<>(net.minecraft.core.Direction.Plane.HORIZONTAL.stream().toList());
        order.sort(java.util.Comparator.comparingDouble(d -> -(d.getStepX() * dx + d.getStepZ() * dz)));
        for (net.minecraft.core.Direction direction : order) {
            BlockPos step = feet.relative(direction);
            if (WorldUtil.passable(level, step)) {
                continue;
            }
            List<BlockPos> cut = new ArrayList<>();
            for (BlockPos pos : new BlockPos[] {feet.above(2), step.above(), step.above(2)}) {
                if (!WorldUtil.passable(level, pos)) {
                    cut.add(pos);
                }
            }
            if (cut.isEmpty()) {
                villager.getJumpControl().jump();
                villager.getMoveControl().setWantedPosition(step.getX() + 0.5, step.getY() + 1, step.getZ() + 0.5, 0.6);
                return true;
            }
            boolean allowed = true;
            for (BlockPos pos : cut) {
                if (!carvable(level, pos)) {
                    allowed = false;
                    break;
                }
            }
            if (!allowed) {
                continue;
            }
            BlockPos pos = cut.getFirst();
            villager.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            villager.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            level.destroyBlock(pos, true, villager);
            return true;
        }
        return false;
    }

    private boolean carvable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty() || level.getBlockEntity(pos) != null || guarded.test(pos)) {
            return false;
        }
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0 || hardness > 3.5F) {
            return false;
        }
        return WorldUtil.naturalGround(state) || WorldUtil.removable(level, pos) || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.COPPER_ORES)
                || state.is(BlockTags.IRON_ORES);
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
