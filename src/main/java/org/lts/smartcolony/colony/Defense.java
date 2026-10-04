package org.lts.smartcolony.colony;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Reputation;
import org.lts.smartcolony.core.ThreatMap;
import org.lts.smartcolony.util.WorldUtil;

public final class Defense {
    private final Colony colony;
    private final List<LivingEntity> enemies = new ArrayList<>();
    private long scannedAt = -1000;
    private long alarmUntil;
    private long lastRing;
    private long lastAnnounce = -100000;

    public Defense(Colony colony) {
        this.colony = colony;
    }

    public boolean alarmed(ServerLevel level) {
        return level.getGameTime() < alarmUntil;
    }

    public void tick(ServerLevel level, long ticks) {
        if (ticks % 40 == 3) {
            scan(level);
            alarm(level);
        }
        if (ticks % 1200 == 5) {
            ThreatMap.decay(colony.data().threat, 0.8);
        }
    }

    private static boolean hostileNow(LivingEntity entity) {
        if (entity instanceof net.minecraft.world.entity.monster.Enderman
                || entity instanceof net.minecraft.world.entity.monster.zombie.ZombifiedPiglin) {
            return entity instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() != null;
        }
        return true;
    }

    private void alarm(ServerLevel level) {
        BlockPos center = colony.center();
        double inner = colony.data().radius * 0.75;
        LivingEntity intruder = null;
        for (LivingEntity enemy : enemies) {
            if (enemy instanceof ServerPlayer || enemy.isInWater()) {
                continue;
            }
            if (enemy instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() == null
                    && !(enemy instanceof net.minecraft.world.entity.monster.Creeper)) {
                continue;
            }
            double dx = enemy.getX() - center.getX();
            double dz = enemy.getZ() - center.getZ();
            if (dx * dx + dz * dz <= inner * inner && Math.abs(enemy.getY() - center.getY()) < 12) {
                intruder = enemy;
                break;
            }
        }
        long now = level.getGameTime();
        if (intruder == null) {
            return;
        }
        alarmUntil = now + 400;
        if (now - lastRing >= 200 && level.getBlockState(center).getBlock() instanceof net.minecraft.world.level.block.BellBlock bell) {
            lastRing = now;
            bell.attemptToRing(level, center, net.minecraft.core.Direction.NORTH);
            colony.data().bump("alarms_rung", 1);
        }
        for (java.util.Map.Entry<java.util.UUID, org.lts.smartcolony.work.Colonist> entry : colony.colonists().entrySet()) {
            entry.getValue().hide(level, now + 400);
        }
        if (now - lastAnnounce > 2400) {
            lastAnnounce = now;
            colony.journal(level, "alarm: " + intruder.getType().toShortString() + " inside the village");
            colony.notify(level, Component.translatable("smartcolony.chat.alarm", intruder.getDisplayName()));
        }
    }

    private AABB area(int extra) {
        int radius = colony.data().radius + extra;
        return new AABB(colony.center()).inflate(radius, 32, radius);
    }

    private void scan(ServerLevel level) {
        scannedAt = level.getGameTime();
        enemies.clear();
        BlockPos center = colony.center();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area(16),
                e -> e instanceof Enemy && e.isAlive() && !(e instanceof IronGolem) && hostileNow(e))) {
            enemies.add(entity);
            ThreatMap.add(colony.data().threat, entity.getX() - center.getX(), entity.getZ() - center.getZ(), 0.2);
        }
        if (SmartColonyConfig.get().guardsAttackEnemyPlayers) {
            for (ServerPlayer player : level.players()) {
                if (isEnemyPlayer(player) && area(24).contains(player.position())) {
                    enemies.add(player);
                    for (IronGolem golem : level.getEntitiesOfClass(IronGolem.class, area(16), IronGolem::isAlive)) {
                        if (golem.getTarget() == null && golem.distanceTo(player) < 24) {
                            golem.setTarget(player);
                        }
                    }
                }
            }
        }
    }

    public boolean isEnemyPlayer(ServerPlayer player) {
        return !player.isCreative() && !player.isSpectator() && player.isAlive()
                && colony.status(player.getUUID()) == Reputation.Status.ENEMY;
    }

    public List<LivingEntity> enemies() {
        enemies.removeIf(entity -> !entity.isAlive());
        return enemies;
    }

    public LivingEntity nearestEnemy(Villager guard, double range, java.util.Set<java.util.UUID> ignored) {
        return enemies().stream()
                .filter(entity -> !ignored.contains(entity.getUUID()))
                .filter(entity -> entity.distanceTo(guard) <= range)
                .filter(entity -> !(entity instanceof ServerPlayer player) || isEnemyPlayer(player))
                .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(guard)))
                .orElse(null);
    }

    public double threatLevel() {
        return Math.min(2.0, ThreatMap.total(colony.data().threat) / 12.0);
    }

    public List<BlockPos> nightPosts(ServerLevel level, int count) {
        List<BlockPos> posts = new ArrayList<>();
        for (ColonyData.Building building : colony.data().buildings) {
            if ("guard_post".equals(building.kind)) {
                posts.add(WorldUtil.bp(building.door).relative(net.minecraft.core.Direction.UP, 0));
            }
        }
        double radius = colony.data().radius * 0.8;
        for (int sector : ThreatMap.hottest(colony.data().threat, count)) {
            double[] direction = ThreatMap.direction(sector);
            BlockPos post = dryPoint(level, direction[0], direction[1], radius);
            if (post != null) {
                posts.add(post);
            }
        }
        return posts;
    }

    private BlockPos dryPoint(ServerLevel level, double dx, double dz, double radius) {
        for (double r = radius; r >= 4; r -= 3) {
            int x = colony.center().getX() + (int) Math.round(dx * r);
            int z = colony.center().getZ() + (int) Math.round(dz * r);
            if (!WorldUtil.loaded(level, new BlockPos(x, 0, z))) {
                continue;
            }
            BlockPos surface = WorldUtil.surface(level, x, z);
            if (level.getFluidState(surface).isEmpty() && level.getFluidState(surface.below()).isEmpty()) {
                return surface;
            }
        }
        return null;
    }

    public List<BlockPos> patrolRoute(ServerLevel level) {
        List<BlockPos> route = new ArrayList<>();
        double radius = colony.data().radius * 0.75;
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4.0;
            BlockPos point = dryPoint(level, Math.cos(angle), Math.sin(angle), radius);
            if (point != null) {
                route.add(point);
            }
        }
        if (colony.data().mine != null) {
            route.add(WorldUtil.bp(colony.data().mine.entrance));
        }
        return route;
    }

    public void onStatusChange(ServerLevel level, ServerPlayer player, Reputation.Status before, Reputation.Status after) {
        if (after == Reputation.Status.ENEMY) {
            for (Villager villager : colony.loadedVillagers(level)) {
                villager.getGossips().add(player.getUUID(), GossipType.MAJOR_NEGATIVE, 25);
            }
            colony.journal(level, player.getName().getString() + " declared an enemy of the colony");
            player.sendSystemMessage(Component.translatable("smartcolony.chat.prefix", colony.data().name).append(" ")
                    .append(Component.translatable("smartcolony.chat.enemy")));
            scan(level);
        } else if (before == Reputation.Status.ENEMY) {
            for (Villager villager : colony.loadedVillagers(level)) {
                villager.getGossips().remove(player.getUUID(), GossipType.MAJOR_NEGATIVE);
            }
            for (IronGolem golem : level.getEntitiesOfClass(IronGolem.class, area(32), IronGolem::isAlive)) {
                if (golem.getTarget() == player) {
                    golem.setTarget(null);
                    golem.stopBeingAngry();
                }
            }
            colony.journal(level, player.getName().getString() + " is no longer an enemy");
            player.sendSystemMessage(Component.translatable("smartcolony.chat.prefix", colony.data().name).append(" ")
                    .append(Component.translatable("smartcolony.chat.forgiven")));
        }
    }

    public JsonObject debug(ServerLevel level) {
        JsonObject json = new JsonObject();
        JsonArray list = new JsonArray();
        for (LivingEntity entity : enemies()) {
            list.add(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath() + "@" + entity.blockPosition().toShortString());
        }
        json.add("enemies", list);
        json.addProperty("scannedAt", scannedAt);
        JsonArray threat = new JsonArray();
        for (double value : colony.data().threat) {
            threat.add(Math.round(value * 100) / 100.0);
        }
        json.add("sectors", threat);
        return json;
    }
}
