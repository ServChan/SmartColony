package org.lts.smartcolony.work;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.CrafterNeeds;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.util.WorldUtil;

public final class MasonJob extends CrafterJob {
    private static final int TRIP = 24;

    private BlockPos dig;
    private BlockPos stand;
    private int misses;
    private int dug;
    private long gatherUntil;

    public MasonJob(Colonist colonist) {
        super(colonist, Role.MASON);
        depositThreshold = 8;
    }

    @Override
    protected Predicate<ItemStack> tools() {
        return Tools.SHOVEL;
    }

    @Override
    protected boolean extraWork(ServerLevel level, Villager villager) {
        if (level.getGameTime() < gatherUntil || !CrafterNeeds.masonGathers(colony().warehouse().stock(level))) {
            return false;
        }
        phase = "dig";
        dug = 0;
        dig = null;
        misses = 0;
        return true;
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        if (phase.equals("dig")) {
            if (wait > 0) {
                wait--;
                return;
            }
            gather(level, villager);
            return;
        }
        super.tick(level, villager);
    }

    @Override
    public void onRelease(ServerLevel level, Villager villager) {
        super.onRelease(level, villager);
        dig = null;
        stand = null;
    }

    private void gather(ServerLevel level, Villager villager) {
        if (!onShift(level, villager) || cargoFull(villager) || dug >= TRIP) {
            phase = hasCargo(villager) ? "deposit" : "start";
            return;
        }
        Equip equip = equip(level, villager, Tools.SHOVEL, Tools.SHOVELS);
        if (equip == Equip.BUSY) {
            return;
        }
        if (dig == null) {
            status = "looking for sand and clay";
            dig = probe(level, villager);
            if (dig == null) {
                if (++misses > 30) {
                    gatherUntil = level.getGameTime() + 6000;
                    phase = hasCargo(villager) ? "deposit" : "start";
                    colony().request(Ids.mc("sand"));
                }
                wait = 4;
                return;
            }
            stand = standFor(level, dig);
            if (stand == null) {
                dig = null;
                return;
            }
        }
        BlockState state = level.getBlockState(dig);
        if (!diggable(state)) {
            dig = null;
            return;
        }
        status = "digging " + Ids.path(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        if (villager.getEyePosition().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(dig)) > 4.0) {
            Mover.Result result = move(level, villager, stand, 1.2);
            if (result == Mover.Result.FAILED) {
                dig = null;
                misses++;
            }
            return;
        }
        Digger.Result result = colonist.digger().tick(level, villager, dig, true);
        if (result == Digger.Result.FAILED) {
            dig = null;
            return;
        }
        if (result == Digger.Result.DONE) {
            stash(level, villager, colonist.digger().takeDrops());
            dug++;
            gainXp(level, villager, 1);
            colony().data().bump("mason_dug", 1);
            BlockPos last = dig;
            dig = null;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = last.relative(direction);
                if (WorldUtil.loaded(level, next) && diggable(level.getBlockState(next)) && allowed(next)
                        && WorldUtil.isAirLike(level.getBlockState(next.above()))) {
                    dig = next;
                    stand = standFor(level, next);
                    if (stand == null) {
                        dig = null;
                    }
                    break;
                }
            }
        }
    }

    private static boolean diggable(BlockState state) {
        return state.is(BlockTags.SAND) && !state.is(Blocks.SUSPICIOUS_SAND) || state.is(Blocks.CLAY);
    }

    private boolean allowed(BlockPos pos) {
        BlockPos center = colony().center();
        double min = colony().data().radius * 0.6;
        return pos.distSqr(center) >= min * min && !colony().isProtected(pos, true);
    }

    private BlockPos probe(ServerLevel level, Villager villager) {
        var random = villager.getRandom();
        BlockPos center = colony().center();
        int radius = SmartColonyConfig.get().forestRadius;
        BlockPos water = colony().findWater(level, center, radius);
        for (int i = 0; i < 48; i++) {
            BlockPos origin = water != null && i % 2 == 0 ? water : center;
            int spread = origin == water ? 12 : radius;
            int x = origin.getX() + random.nextInt(spread * 2 + 1) - spread;
            int z = origin.getZ() + random.nextInt(spread * 2 + 1) - spread;
            if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                continue;
            }
            BlockPos ground = WorldUtil.surface(level, x, z).below();
            BlockState state = level.getBlockState(ground);
            if (diggable(state) && allowed(ground) && level.getFluidState(ground.above()).isEmpty()) {
                return ground.immutable();
            }
        }
        return null;
    }

    private static BlockPos standFor(ServerLevel level, BlockPos dig) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy = 1; dy >= 0; dy--) {
                BlockPos feet = dig.relative(direction).above(dy);
                if (WorldUtil.standable(level, feet)) {
                    return feet;
                }
            }
        }
        BlockPos above = dig.above();
        return WorldUtil.standable(level, above.above()) ? above.above() : null;
    }
}
