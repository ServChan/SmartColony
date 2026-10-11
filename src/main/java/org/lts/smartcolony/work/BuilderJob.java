package org.lts.smartcolony.work;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.SmartColonyConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import org.lts.smartcolony.colony.Construction;
import org.lts.smartcolony.colony.Improvements;
import org.lts.smartcolony.core.Blueprint;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.util.WorldUtil;

public final class BuilderJob extends Job {
    private Construction.Task task;
    private int cooldown;
    private int failures;

    public BuilderJob(Colonist colonist) {
        super(colonist);
        depositThreshold = 6;
    }

    @Override
    public Role role() {
        return Role.BUILDER;
    }

    @Override
    protected java.util.function.Predicate<ItemStack> tools() {
        return Tools.SHOVEL;
    }

    @Override
    protected boolean keep(ItemStack stack) {
        return tools().test(stack);
    }

    @Override
    public boolean wantsControl(ServerLevel level, Villager villager) {
        if (hasCargo(villager)) {
            return true;
        }
        return onShift(level, villager) && colony().construction().hasWork(level);
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        if (wait > 0) {
            wait--;
            return;
        }
        switch (phase) {
            case "start" -> start(level, villager);
            case "work" -> work(level, villager);
            case "deposit" -> deposit(level, villager);
            default -> phase = "start";
        }
    }

    private void start(ServerLevel level, Villager villager) {
        if (hasCargo(villager) && (cargoFull(villager) || !onShift(level, villager) || !colony().construction().hasWork(level))) {
            phase = "deposit";
            return;
        }
        task = colony().construction().next(level, villager.getUUID());
        if (task == null) {
            status = colony().construction().status();
            idle(level, 400);
            return;
        }
        failures = 0;
        phase = "work";
    }

    private void work(ServerLevel level, Villager villager) {
        if (task == null) {
            phase = "start";
            return;
        }
        if (cargoFull(villager)) {
            phase = "deposit";
            return;
        }
        BlockPos pos = task.pos();
        double reach = villager.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        if (reach > 5.5) {
            Mover.Result result = move(level, villager, task.stand(), 3.0);
            if (result == Mover.Result.MOVING) {
                status = task.kind().name().toLowerCase(java.util.Locale.ROOT) + " " + pos.toShortString();
                return;
            }
            if (result == Mover.Result.FAILED) {
                failures++;
                if (reach > 14 && failures < 3) {
                    return;
                }
            }
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (task.kind() == Construction.Kind.CLEAR && overColumn(villager, pos) && !stepAside(level, villager, pos)) {
            colony().data().bump("clear_under_feet_skipped", 1);
            colony().construction().skip(task);
            task = null;
            phase = "start";
            return;
        }
        boolean done = perform(level, villager);
        if (done) {
            colony().construction().completed(task);
            task = null;
            phase = "start";
            gainXp(level, villager, 1);
            cooldown = Math.max(1, SmartColonyConfig.get().blockPlaceTicks - skill());
            wait = 0;
        }
    }

    private boolean perform(ServerLevel level, Villager villager) {
        BlockPos pos = task.pos();
        switch (task.kind()) {
            case CLEAR -> {
                BlockState state = level.getBlockState(pos);
                if (!state.getFluidState().isEmpty()) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    return true;
                }
                if (!state.isAir()) {
                    if (state.getDestroySpeed(level, pos) < 0) {
                        return true;
                    }
                    colonist.digger().breakNow(level, villager, pos, true);
                    stash(level, villager, rubble(level, villager, colonist.digger().takeDrops()));
                    villager.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                }
                BlockPos low = task.partner();
                if (low != null && low.getX() == pos.getX() && low.getZ() == pos.getZ()) {
                    for (BlockPos below = pos.below(); below.getY() >= low.getY(); below = below.below()) {
                        BlockState under = level.getBlockState(below);
                        if (under.isAir() || !under.getFluidState().isEmpty() || under.getDestroySpeed(level, below) < 0
                                || level.getBlockEntity(below) != null) {
                            break;
                        }
                        colonist.digger().breakNow(level, villager, below, true);
                        stash(level, villager, rubble(level, villager, colonist.digger().takeDrops()));
                    }
                }
                return true;
            }
            case FILL -> {
                if (!clearOf(level, villager, pos)) {
                    return false;
                }
                Placer.place(level, villager, pos, task.state());
                return true;
            }
            case PLACE -> {
                return place(level, villager);
            }
            case ROAD -> {
                if (!road(level, villager, pos)) {
                    task = null;
                    wait = 100;
                    return false;
                }
                return true;
            }
            case ORDER -> {
                if (!clearOf(level, villager, pos)) {
                    return false;
                }
                if (task.state().isAir()) {
                    if (!level.getBlockState(pos).isAir()) {
                        colonist.digger().breakNow(level, villager, pos, true);
                        stash(level, villager, colonist.digger().takeDrops());
                    }
                    colony().data().bump("improvements_dug", 1);
                    return true;
                }
                if (!colony().improvements().take(level, task.state())) {
                    task = null;
                    wait = 100;
                    return false;
                }
                BlockState here = level.getBlockState(pos);
                if (!here.isAir() && !(here.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)) {
                    colonist.digger().breakNow(level, villager, pos, true);
                    stash(level, villager, colonist.digger().takeDrops());
                }
                if (task.state().getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
                    net.minecraft.core.Direction facing = task.state().getValue(net.minecraft.world.level.block.BedBlock.FACING);
                    BlockPos head = pos.relative(facing);
                    if (WorldUtil.isAirLike(level.getBlockState(head))) {
                        int flags = Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE;
                        level.setBlock(pos, task.state(), flags);
                        level.setBlock(head, task.state().setValue(net.minecraft.world.level.block.BedBlock.PART,
                                net.minecraft.world.level.block.state.properties.BedPart.HEAD), flags);
                        level.playSound(null, pos, task.state().getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
                    }
                    colony().data().bump("improvements_placed", 1);
                    return true;
                }
                if (task.state().getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
                    BlockPos upper = pos.above();
                    BlockState above = level.getBlockState(upper);
                    if (!above.isAir() && Improvements.wallMaterial(above)) {
                        colonist.digger().breakNow(level, villager, upper, true);
                        stash(level, villager, colonist.digger().takeDrops());
                    }
                    if (WorldUtil.isAirLike(level.getBlockState(upper))) {
                        int flags = Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE;
                        level.setBlock(pos, task.state(), flags);
                        level.setBlock(upper, task.state().setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), flags);
                        level.playSound(null, pos, task.state().getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
                    }
                    colony().data().bump("improvements_placed", 1);
                    return true;
                }
                Placer.place(level, villager, pos, task.state());
                colony().data().bump("improvements_placed", 1);
                return true;
            }
        }
        return true;
    }

    private boolean clearOf(ServerLevel level, Villager villager, BlockPos pos) {
        AABB box = new AABB(pos);
        List<LivingEntity> inside = level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive);
        for (LivingEntity entity : inside) {
            if (entity instanceof Player) {
                status = "waiting for a player to step aside";
                wait = 20;
                return false;
            }
            BlockPos away = nearestFree(level, pos);
            if (away != null) {
                entity.teleportTo(away.getX() + 0.5, away.getY(), away.getZ() + 0.5);
            }
        }
        return true;
    }

    private BlockPos nearestFree(ServerLevel level, BlockPos pos) {
        var building = task == null || task.project() == null ? null : task.project().building;
        for (int r = 1; r <= 6; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) {
                        continue;
                    }
                    BlockPos column = WorldUtil.surface(level, pos.getX() + dx, pos.getZ() + dz);
                    if (building != null && building.contains(WorldUtil.pos(column), 0)) {
                        continue;
                    }
                    if (WorldUtil.standable(level, column)) {
                        return column;
                    }
                }
            }
        }
        return null;
    }

    private boolean place(ServerLevel level, Villager villager) {
        BlockPos pos = task.pos();
        BlockState state = task.state();
        if (!state.getCollisionShape(level, pos).isEmpty() && !clearOf(level, villager, pos)) {
            return false;
        }
        if (task.partner() != null && !clearOf(level, villager, task.partner())) {
            return false;
        }
        if (!state.canSurvive(level, pos) && isAttachedBlock(state)) {
            colony().data().bump("blocks_skipped", 1);
            return true;
        }
        if (task.partner() != null) {
            int flags = Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE;
            level.setBlock(pos, state, flags);
            level.setBlock(task.partner(), task.partnerState(), flags);
            level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
            villager.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        } else {
            Placer.place(level, villager, pos, state);
        }
        return true;
    }

    private boolean road(ServerLevel level, Villager villager, BlockPos ground) {
        return colony().construction().paths().build(level, villager, ground);
    }

    private void deposit(ServerLevel level, Villager villager) {
        if (!atDepot(level, villager)) {
            if (goDepot(level, villager) == Mover.Result.FAILED) {
                wait = 100;
            }
            return;
        }
        depositAll(level, villager);
        phase = "start";
    }

    private static boolean overColumn(Villager villager, BlockPos pos) {
        BlockPos feet = villager.blockPosition();
        return Math.abs(villager.getX() - (pos.getX() + 0.5)) < 0.9 && Math.abs(villager.getZ() - (pos.getZ() + 0.5)) < 0.9
                && feet.getY() > pos.getY();
    }

    private boolean stepAside(ServerLevel level, Villager villager, BlockPos pos) {
        BlockPos feet = villager.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) <= 0 && Math.abs(dz) <= 0) {
                    continue;
                }
                int x = pos.getX() + dx;
                int z = pos.getZ() + dz;
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && dx * dz != 0) {
                    continue;
                }
                for (int dy = -3; dy <= 1; dy++) {
                    BlockPos cell = new BlockPos(x, feet.getY() + dy, z);
                    if (!WorldUtil.standable(level, cell)) {
                        continue;
                    }
                    double score = Math.abs(dy) * 2.0 + dx * dx + dz * dz;
                    if (score < bestScore) {
                        bestScore = score;
                        best = cell;
                    }
                }
            }
        }
        if (best == null) {
            return false;
        }
        villager.getNavigation().stop();
        villager.teleportTo(best.getX() + 0.5, best.getY(), best.getZ() + 0.5);
        villager.resetFallDistance();
        return true;
    }

    private List<ItemStack> rubble(ServerLevel level, Villager villager, List<ItemStack> drops) {
        List<ItemStack> kept = new java.util.ArrayList<>();
        for (ItemStack drop : drops) {
            String id = org.lts.smartcolony.util.ItemIds.id(drop);
            String path = org.lts.smartcolony.core.Ids.path(id);
            int cap = switch (path) {
                case "dirt", "coarse_dirt", "rooted_dirt" -> 320;
                case "cobblestone", "cobbled_deepslate" -> 1024;
                case "andesite", "diorite", "granite", "tuff", "gravel", "sand", "red_sand" -> 256;
                case "red_mushroom", "brown_mushroom" -> 64;
                default -> Integer.MAX_VALUE;
            };
            if (cap != Integer.MAX_VALUE && colony().stockOf(level, found -> found.equals(id)) + Inv.count(villager, id) >= cap) {
                colony().data().bump("rubble_discarded", drop.getCount());
                continue;
            }
            kept.add(drop);
        }
        return kept;
    }

    private static boolean isAttachedBlock(BlockState state) {
        return Blueprint.isAttached(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }
}
