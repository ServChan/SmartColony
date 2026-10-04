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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.SmartColonyConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import org.lts.smartcolony.colony.Construction;
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
        return tools().test(stack) || stack.is(Items.TORCH) || stack.is(net.minecraft.tags.ItemTags.WOODEN_FENCES);
    }

    @Override
    public boolean wantsControl(ServerLevel level, Villager villager) {
        if (hasCargo(villager)) {
            return true;
        }
        return colony().workTime(level) && colony().construction().hasWork(level);
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
        if (hasCargo(villager) && (cargoFull(villager) || !colony().workTime(level) || !colony().construction().hasWork(level))) {
            phase = "deposit";
            return;
        }
        task = colony().construction().next(level);
        if (task == null) {
            status = colony().construction().status();
            wait = 100;
            return;
        }
        if (task.kind() == Construction.Kind.TORCH && Inv.count(villager, Tools.TORCH) == 0) {
            if (!atDepot(level, villager)) {
                if (goDepot(level, villager) == Mover.Result.FAILED) {
                    wait = 100;
                }
                return;
            }
            supply(level, villager, Ids.mc("torch"), 1, 8);
            String fence = Ids.mc("oak_fence");
            supply(level, villager, stack -> stack.is(net.minecraft.tags.ItemTags.WOODEN_FENCES), 1, 8);
            if (Inv.count(villager, stack -> stack.is(net.minecraft.tags.ItemTags.WOODEN_FENCES)) == 0 && colony().craft(level, fence, 3)) {
                supply(level, villager, fence, 1, 3);
            }
            if (Inv.count(villager, Tools.TORCH) == 0) {
                colony().request(Ids.mc("torch"));
                colony().construction().skip(task);
                status = "no torches for street lights";
                wait = 200;
                return;
            }
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
        boolean done = perform(level, villager);
        if (done) {
            colony().construction().completed(task);
            task = null;
            phase = "start";
            cooldown = SmartColonyConfig.get().blockPlaceTicks;
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
                    stash(level, villager, colonist.digger().takeDrops());
                    villager.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
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
                road(level, villager, pos);
                return true;
            }
            case TORCH -> {
                torch(level, villager, pos);
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
        var building = colony().data().project == null ? null : colony().data().project.building;
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

    private void road(ServerLevel level, Villager villager, BlockPos ground) {
        BlockState state = level.getBlockState(ground);
        BlockState above = level.getBlockState(ground.above());
        if (!above.isAir()) {
            if (WorldUtil.isAirLike(above) && above.getFluidState().isEmpty()) {
                level.destroyBlock(ground.above(), false, villager);
            } else {
                return;
            }
        }
        if (state.is(BlockTags.SUBSTRATE_OVERWORLD) && !state.is(Blocks.DIRT_PATH) && !state.is(Blocks.FARMLAND) && !state.is(Blocks.MUD)) {
            level.setBlock(ground, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL);
            level.playSound(null, ground, SoundEvents.SHOVEL_FLATTEN.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
            villager.getLookControl().setLookAt(ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5);
        } else if ((state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL)) && colony().warehouse().take(level, Ids.mc("cobblestone"), 1) == 1) {
            Placer.place(level, villager, ground, Blocks.COBBLESTONE.defaultBlockState());
        }
    }

    private void torch(ServerLevel level, Villager villager, BlockPos feet) {
        if (!WorldUtil.isAirLike(level.getBlockState(feet)) || !WorldUtil.isAirLike(level.getBlockState(feet.above()))) {
            return;
        }
        ItemStack fence = Inv.find(villager, stack -> stack.is(net.minecraft.tags.ItemTags.WOODEN_FENCES));
        ItemStack torch = Inv.find(villager, Tools.TORCH);
        if (torch.isEmpty()) {
            return;
        }
        BlockPos torchAt = feet;
        if (!fence.isEmpty() && fence.getItem() instanceof BlockItem blockItem) {
            Placer.place(level, villager, feet, blockItem.getBlock().defaultBlockState());
            fence.shrink(1);
            torchAt = feet.above();
        }
        BlockState torchState = Blocks.TORCH.defaultBlockState();
        if (torchState.canSurvive(level, torchAt)) {
            Placer.place(level, villager, torchAt, torchState);
            torch.shrink(1);
        }
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

    private static boolean isAttachedBlock(BlockState state) {
        return Blueprint.isAttached(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }
}
