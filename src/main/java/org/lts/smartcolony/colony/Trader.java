package org.lts.smartcolony.colony;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.goal.UseItemGoal;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.PriceTable;
import org.lts.smartcolony.core.Reputation;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.core.TradePlanner;
import org.lts.smartcolony.mixin.MobAccessor;
import org.lts.smartcolony.util.ItemIds;
import org.lts.smartcolony.util.WorldUtil;

public final class Trader {
    private static final String TAG = "smartcolony_trader";
    private static final long REHIRE_COOLDOWN = 24000L;
    private static final int MISSING_LIMIT = 12000;
    private static final double STRAY_DISTANCE = 24.0;
    private static final double TELEPORT_DISTANCE = 64.0;
    private static final int HOME_RADIUS = 16;

    private final Colony colony;
    private final Set<UUID> configured = new HashSet<>();
    private final Map<UUID, Long> tradeValue = new HashMap<>();
    private int missingTicks;

    public Trader(Colony colony) {
        this.colony = colony;
    }

    private ColonyData data() {
        return colony.data();
    }

    public boolean hired() {
        return !data().traderId.isEmpty();
    }

    public boolean marketBuilt() {
        return market() != null;
    }

    public boolean isTrader(Entity entity) {
        return hired() && entity.getUUID().toString().equals(data().traderId);
    }

    private ColonyData.Building market() {
        for (ColonyData.Building building : data().buildings) {
            if ("market".equals(building.blueprint)) {
                return building;
            }
        }
        return null;
    }

    private BlockPos marketCenter(ColonyData.Building building) {
        return new BlockPos((building.min.x() + building.max.x()) / 2, building.door.y(), (building.min.z() + building.max.z()) / 2);
    }

    public void tick(ServerLevel level, long ticks) {
        if (ticks % 100 != 31) {
            return;
        }
        ColonyData.Building market = market();
        if (market == null) {
            return;
        }
        if (hired()) {
            if (level.getEntity(UUID.fromString(data().traderId)) instanceof WanderingTrader trader && trader.isAlive()) {
                missingTicks = 0;
                configure(level, trader, market);
                keepNear(level, trader, market);
                if (ticks % 1200 == 31) {
                    discardStrays(level, market);
                }
                if (trader.isTrading()) {
                    refreshStock(level, trader);
                }
                return;
            }
            missingTicks += 100;
            if (missingTicks >= MISSING_LIMIT) {
                lost(level, "the trader went missing");
            }
            return;
        }
        long now = level.getGameTime();
        if (data().traderLost == 0 || now - data().traderLost >= REHIRE_COOLDOWN) {
            hire(level, market);
        }
    }

    private void hire(ServerLevel level, ColonyData.Building market) {
        discardStrays(level, market);
        WanderingTrader trader = EntityTypes.WANDERING_TRADER.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (trader == null) {
            return;
        }
        BlockPos at = WorldUtil.bp(market.door);
        trader.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360.0F, 0.0F);
        if (!level.noCollision(trader)) {
            BlockPos fallback = colony.depot().above();
            trader.snapTo(fallback.getX() + 0.5, fallback.getY(), fallback.getZ() + 0.5, 0.0F, 0.0F);
        }
        configure(level, trader, market);
        level.addFreshEntity(trader);
        data().traderId = trader.getUUID().toString();
        data().traderLost = 0;
        missingTicks = 0;
        data().bump("traders_hired", 1);
        colony.journal(level, "trader hired at the market (the market building is the hiring cost, nothing is created)");
        colony.notify(level, Component.translatable("smartcolony.chat.trader_hired"));
    }

    private void configure(ServerLevel level, WanderingTrader trader, ColonyData.Building market) {
        trader.setPersistenceRequired();
        trader.setDespawnDelay(0);
        trader.addTag(TAG);
        trader.setCustomName(Component.translatable("smartcolony.trader.name", data().name));
        trader.setCustomNameVisible(true);
        trader.setHomeTo(marketCenter(market), HOME_RADIUS);
        if (trader.hasEffect(MobEffects.INVISIBILITY)) {
            trader.removeEffect(MobEffects.INVISIBILITY);
        }
        if (configured.add(trader.getUUID())) {
            ((MobAccessor) trader).smartcolony$goalSelector().removeAllGoals(goal -> goal instanceof UseItemGoal<?>);
            trader.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    private void keepNear(ServerLevel level, WanderingTrader trader, ColonyData.Building market) {
        if (trader.isTrading()) {
            return;
        }
        BlockPos center = marketCenter(market);
        double distance = trader.position().distanceTo(Vec3.atCenterOf(center));
        if (distance > TELEPORT_DISTANCE) {
            trader.snapTo(center.getX() + 0.5, center.getY(), center.getZ() + 0.5, trader.getYRot(), 0.0F);
            colony.journal(level, "trader was brought back to the market");
        } else if (distance > STRAY_DISTANCE) {
            trader.setWanderTarget(center);
        }
    }

    private void discardStrays(ServerLevel level, ColonyData.Building market) {
        BlockPos center = marketCenter(market);
        AABB box = new AABB(center).inflate(data().radius + 64.0, 48.0, data().radius + 64.0);
        for (WanderingTrader other : level.getEntitiesOfClass(WanderingTrader.class, box,
                t -> t.entityTags().contains(TAG) && !t.getUUID().toString().equals(data().traderId) && !t.isTrading())) {
            other.discard();
        }
    }

    public void died(ServerLevel level) {
        if (!hired()) {
            return;
        }
        configured.remove(UUID.fromString(data().traderId));
        data().traderId = "";
        data().traderLost = level.getGameTime();
        colony.journal(level, "the trader died, a new one is hired in a day");
        colony.notify(level, Component.translatable("smartcolony.chat.trader_died"));
        colony.markDirty();
    }

    private void lost(ServerLevel level, String reason) {
        configured.remove(UUID.fromString(data().traderId));
        data().traderId = "";
        data().traderLost = level.getGameTime();
        missingTicks = 0;
        colony.journal(level, reason + ", a new one is hired in a day");
        colony.markDirty();
    }

    public void dismiss(ServerLevel level) {
        if (hired() && level.getEntity(UUID.fromString(data().traderId)) instanceof WanderingTrader trader) {
            trader.discard();
        }
        data().traderId = "";
    }

    public InteractionResult beforeTrade(ServerLevel level, ServerPlayer player, WanderingTrader trader) {
        if (trader.isTrading() || !trader.isAlive()) {
            return InteractionResult.PASS;
        }
        Reputation.Status status = colony.status(player.getUUID());
        if (status == Reputation.Status.ENEMY) {
            player.sendSystemMessage(Component.translatable("smartcolony.chat.prefix", data().name).append(" ")
                    .append(Component.translatable("smartcolony.chat.trader_enemy")));
            return InteractionResult.SUCCESS_SERVER;
        }
        TradePlanner.Plan plan = TraderTrades.plan(level, colony, status);
        MerchantOffers offers = trader.getOffers();
        offers.clear();
        for (TradePlanner.Offer offer : plan.all()) {
            offers.add(TraderTrades.toMerchantOffer(offer));
        }
        if (offers.isEmpty()) {
            player.sendSystemMessage(Component.translatable("smartcolony.chat.prefix", data().name).append(" ")
                    .append(Component.translatable("smartcolony.chat.trader_nothing")));
            return InteractionResult.SUCCESS_SERVER;
        }
        return InteractionResult.PASS;
    }

    public void settle(ServerLevel level, WanderingTrader trader, MerchantOffer offer) {
        Warehouse warehouse = colony.warehouse();
        ItemStack costA = offer.getCostA();
        ItemStack costB = offer.getCostB();
        ItemStack result = offer.getResult();
        for (ItemStack cost : List.of(costA, costB)) {
            if (!cost.isEmpty()) {
                warehouse.give(level, ItemIds.id(cost), cost.getCount());
            }
        }
        String resultId = ItemIds.id(result);
        int taken = warehouse.take(level, resultId, result.getCount());
        if (taken < result.getCount()) {
            offer.setToOutOfStock();
            data().bump("trader_shortfalls", 1);
            colony.journal(level, "trader shortfall: storage lacked " + (result.getCount() - taken) + " " + shortName(resultId));
        }
        data().bump("trader_trades", 1);
        String paid = costA.getCount() + " " + shortName(ItemIds.id(costA));
        colony.journal(level, "trader: got " + paid + ", gave " + result.getCount() + " " + shortName(resultId));
        colony.economy().market().ledger().add(new Market.Deal(ItemIds.id(costA), costA.getCount(), resultId, result.getCount(), "trader"));
        while (colony.economy().market().ledger().size() > 40) {
            colony.economy().market().ledger().removeFirst();
        }
        boolean changed = restock(level, trader);
        if (trader.getTradingPlayer() instanceof ServerPlayer player) {
            reward(level, player, result, costA);
            if (changed) {
                resend(player, trader);
            }
        }
    }

    private static String shortName(String id) {
        return org.lts.smartcolony.core.Ids.path(id);
    }

    private void reward(ServerLevel level, ServerPlayer player, ItemStack result, ItemStack cost) {
        long value = Math.max((long) PriceTable.base(ItemIds.id(result)) * result.getCount(),
                (long) PriceTable.base(ItemIds.id(cost)) * cost.getCount());
        long total = tradeValue.merge(player.getUUID(), value, Long::sum);
        int points = TradePlanner.reputationPoints(total);
        if (points > 0) {
            tradeValue.put(player.getUUID(), total - points * TradePlanner.REPUTATION_STEP);
            if (colony.reputation(player.getUUID()) < 60) {
                colony.adjustReputation(level, player, points, "smartcolony.chat.trader_trade");
            }
        }
    }

    private boolean restock(ServerLevel level, WanderingTrader trader) {
        colony.warehouse().invalidate();
        Stock stock = colony.warehouse().stock(level);
        boolean changed = false;
        for (MerchantOffer other : trader.getOffers()) {
            if (!other.isOutOfStock() && !TradePlanner.covers(ItemIds.id(other.getResult()), other.getResult().getCount(), stock)) {
                other.setToOutOfStock();
                changed = true;
            }
        }
        return changed;
    }

    private void refreshStock(ServerLevel level, WanderingTrader trader) {
        if (restock(level, trader) && trader.getTradingPlayer() instanceof ServerPlayer player) {
            resend(player, trader);
        }
    }

    private void resend(ServerPlayer player, WanderingTrader trader) {
        if (player.containerMenu instanceof MerchantMenu menu) {
            player.sendMerchantOffers(menu.containerId, trader.getOffers(), 1, 0, false, false);
        }
    }
}
