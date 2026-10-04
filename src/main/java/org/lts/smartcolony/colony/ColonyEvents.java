package org.lts.smartcolony.colony;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.EntityHitResult;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.work.Colonist;

public final class ColonyEvents {
    private static final Map<UUID, Long> ASSAULT_COOLDOWN = new HashMap<>();

    private ColonyEvents() {
    }

    public static boolean allowDamage(LivingEntity victim, DamageSource source, float amount) {
        Entity attacker = source.getEntity();
        if (!(attacker instanceof Villager shooter)) {
            return true;
        }
        Colony colony = ColonyManager.colonyOf(shooter);
        if (colony == null) {
            return true;
        }
        Colonist colonist = colony.colonists().get(shooter.getUUID());
        if (colonist == null || colonist.role() != Role.GUARD && colonist.role() != Role.HERDER) {
            return true;
        }
        if (victim instanceof Villager || victim instanceof IronGolem) {
            return false;
        }
        if (victim instanceof ServerPlayer player) {
            return colony.defense().isEnemyPlayer(player);
        }
        return true;
    }

    public static void converted(net.minecraft.world.entity.Mob previous, net.minecraft.world.entity.Mob converted,
                                 net.minecraft.world.entity.ConversionParams params) {
        if (!(previous.level() instanceof ServerLevel level) || !(previous instanceof Villager villager)) {
            return;
        }
        Colony colony = ColonyManager.colonyOf(villager);
        if (colony == null) {
            return;
        }
        if (colony.ownsName(villager.getUUID())) {
            converted.setCustomName(null);
            converted.setCustomNameVisible(false);
        }
        colony.journal(level, "a villager was turned into a zombie");
        colony.memberDied(level, villager.getUUID());
    }

    public static boolean cushionMineFalls(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof Villager villager) || !source.is(net.minecraft.tags.DamageTypeTags.IS_FALL)) {
            return true;
        }
        Colony colony = ColonyManager.colonyOf(villager);
        if (colony == null || !colony.inMine(villager.blockPosition())) {
            return true;
        }
        colony.data().bump("mine_falls_cushioned", 1);
        return false;
    }

    public static void afterDeath(LivingEntity entity, DamageSource source) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (entity instanceof Villager villager) {
            Colony colony = ColonyManager.colonyOf(villager);
            if (colony == null) {
                return;
            }
            colony.memberDied(level, villager.getUUID());
            if (source.getEntity() instanceof ServerPlayer player && !player.isCreative()) {
                colony.adjustReputation(level, player, -60, "smartcolony.chat.murder");
            }
        }
    }

    public static boolean beforeBreak(Level world, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (!(world instanceof ServerLevel level) || !(player instanceof ServerPlayer serverPlayer)) {
            return true;
        }
        Colony owner = ColonyManager.warehouseOwner(level, pos);
        if (owner != null) {
            owner.theft().onBreak(level, serverPlayer, pos);
        }
        return true;
    }

    public static InteractionResult attackEntity(Player player, Level world, InteractionHand hand, Entity entity, EntityHitResult hit) {
        if (!(world instanceof ServerLevel level) || !(player instanceof ServerPlayer serverPlayer) || serverPlayer.isCreative()) {
            return InteractionResult.PASS;
        }
        if (entity instanceof Villager villager) {
            Colony colony = ColonyManager.colonyOf(villager);
            if (colony != null) {
                long now = level.getGameTime();
                Long last = ASSAULT_COOLDOWN.get(player.getUUID());
                if (last == null || now - last > 40) {
                    ASSAULT_COOLDOWN.put(player.getUUID(), now);
                    colony.adjustReputation(level, serverPlayer, -8, "smartcolony.chat.assault");
                }
            }
        }
        return InteractionResult.PASS;
    }
}
