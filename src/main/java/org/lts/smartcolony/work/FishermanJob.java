package org.lts.smartcolony.work;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.lts.smartcolony.core.Role;

public final class FishermanJob extends Job {
    private BlockPos shore;
    private BlockPos water;
    private int bite;
    private int catches;

    public FishermanJob(Colonist colonist) {
        super(colonist);
        depositThreshold = 5;
    }

    @Override
    public Role role() {
        return Role.FISHERMAN;
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        if (wait > 0) {
            wait--;
            return;
        }
        switch (phase) {
            case "start" -> start(level, villager);
            case "walk" -> walk(level, villager);
            case "fish" -> fish(level, villager);
            case "deposit" -> deposit(level, villager);
            default -> phase = "start";
        }
    }

    private void start(ServerLevel level, Villager villager) {
        if (hasCargo(villager) && (cargoFull(villager) || !colony().workTime(level))) {
            phase = "deposit";
            return;
        }
        if (!colony().workTime(level)) {
            wait = 100;
            return;
        }
        Equip equip = equip(level, villager, Tools.ROD, List.of(org.lts.smartcolony.core.Ids.mc("fishing_rod")));
        if (equip == Equip.BUSY) {
            return;
        }
        if (equip == Equip.MISSING) {
            status = "no fishing rod";
            wait = 600;
            return;
        }
        BlockPos[] spot = colony().fishingSpot(level, villager.blockPosition());
        if (spot == null) {
            status = "no open water nearby";
            wait = 600;
            return;
        }
        shore = spot[0];
        water = spot[1];
        phase = "walk";
    }

    private void walk(ServerLevel level, Villager villager) {
        Mover.Result result = move(level, villager, shore, 1.2);
        if (result == Mover.Result.FAILED) {
            colony().rejectFishingSpot(shore);
            phase = "start";
            wait = 40;
            return;
        }
        if (result == Mover.Result.ARRIVED) {
            bite = 100 + level.getRandom().nextInt(400);
            phase = "fish";
        }
    }

    private void fish(ServerLevel level, Villager villager) {
        if (!Tools.ROD.test(villager.getMainHandItem())) {
            phase = "start";
            return;
        }
        if (cargoFull(villager) || !colony().workTime(level)) {
            phase = "deposit";
            return;
        }
        Vec3 bobber = Vec3.atCenterOf(water).add(0, 0.4, 0);
        villager.getLookControl().setLookAt(bobber.x, bobber.y, bobber.z);
        if (level.getGameTime() % 40 == 0) {
            level.sendParticles(ParticleTypes.FISHING, bobber.x, bobber.y, bobber.z, 2, 0.1, 0.0, 0.1, 0.0);
        }
        if (--bite > 0) {
            return;
        }
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, bobber)
                .withParameter(LootContextParams.TOOL, villager.getMainHandItem())
                .withOptionalParameter(LootContextParams.THIS_ENTITY, villager)
                .create(LootContextParamSets.FISHING);
        LootTable table = level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.FISHING);
        List<ItemStack> loot = table.getRandomItems(params);
        level.sendParticles(ParticleTypes.SPLASH, bobber.x, bobber.y, bobber.z, 12, 0.3, 0.1, 0.3, 0.1);
        level.playSound(null, water, SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL, 0.6F, 1.0F);
        level.playSound(null, villager.blockPosition(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.NEUTRAL, 0.6F, 1.0F);
        villager.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        stash(level, villager, loot);
        villager.getMainHandItem().hurtAndBreak(1, villager, EquipmentSlot.MAINHAND);
        catches++;
        colony().data().bump("fish_caught", 1);
        bite = 100 + level.getRandom().nextInt(500);
    }

    private void deposit(ServerLevel level, Villager villager) {
        if (!atDepot(level, villager)) {
            if (goDepot(level, villager) == Mover.Result.FAILED) {
                wait = 100;
            }
            return;
        }
        depositAll(level, villager);
        colony().cook(level);
        phase = "start";
    }
}
