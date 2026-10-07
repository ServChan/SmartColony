package org.lts.smartcolony.colony;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.core.Cemetery;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.CraftPlanner;
import org.lts.smartcolony.core.DeathCause;
import org.lts.smartcolony.core.GraveNames;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.util.ItemIds;
import org.lts.smartcolony.util.WorldUtil;
import org.lts.smartcolony.work.Colonist;
import org.lts.smartcolony.work.Inv;
import org.lts.smartcolony.work.Placer;

public final class Graveyard {
    public static final List<String> FLOWERS = List.of("poppy", "dandelion", "cornflower", "azure_bluet", "oxeye_daisy",
            "lily_of_the_valley", "allium");
    private static final long REMAINS_PATIENCE = 120000L;
    private static final int RECORD_LIMIT = 200;
    private static final int PICKUP_RADIUS = 10;

    public record Site(ColonyData.Building building, Cemetery.Slot slot, BlockPos stone, BlockPos stand) {
    }

    public record Summary(int deaths, int buried, int recovered, int pending) {
    }

    private final Colony colony;
    private long cachedUntil;
    private boolean cachedWork;

    public Graveyard(Colony colony) {
        this.colony = colony;
    }

    private ColonyData data() {
        return colony.data();
    }

    public Summary summary() {
        long buried = data().deaths.stream().filter(d -> d.buried).count();
        return new Summary(data().stat("deaths"), (int) buried, data().stat("remains_items"), (int) data().pendingRemains());
    }

    public void recordDeath(ServerLevel level, Villager villager, DamageSource source, Colonist colonist) {
        ColonyData.Death death = new ColonyData.Death();
        death.id = villager.getUUID().toString();
        death.name = GraveNames.of(death.id);
        death.child = villager.isBaby();
        death.role = death.child ? "child" : colonist == null ? Role.IDLE.key() : colonist.role().key();
        Entity attacker = source.getEntity();
        boolean projectile = source.getDirectEntity() instanceof Projectile;
        death.cause = DeathCause.classify(source.getMsgId(), attacker != null, projectile);
        death.pos = WorldUtil.pos(villager.blockPosition());
        death.time = level.getGameTime();
        death.day = level.getDefaultClockTime() / 24000L;
        if (DeathCause.itemsLost(death.cause)) {
            death.remains = "lost";
        } else {
            int items = 0;
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, villager.getBoundingBox().inflate(3.0, 2.0, 3.0),
                    entity -> entity.isAlive() && entity.tickCount <= 2)) {
                item.setUnlimitedLifetime();
                items += item.getItem().getCount();
            }
            items += spill(level, villager);
            death.items = items;
            death.remains = items > 0 ? "pending" : "none";
        }
        data().deaths.add(death);
        trim();
        cachedUntil = 0;
        colony.journal(level, death.role + " " + death.name + " died: " + death.cause + ", remains " + death.remains);
        colony.notify(level, Component.translatable("smartcolony.chat.death_cause", who(death),
                Component.translatable("smartcolony.cause." + death.cause)));
        if (DeathCause.itemsLost(death.cause)) {
            colony.notify(level, Component.translatable("smartcolony.chat.remains_lost"));
        }
    }

    private static int spill(ServerLevel level, Villager villager) {
        java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
        var inventory = villager.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                stacks.add(stack.copy());
                inventory.setItem(slot, ItemStack.EMPTY);
            }
        }
        for (net.minecraft.world.entity.EquipmentSlot hand : new net.minecraft.world.entity.EquipmentSlot[] {
                net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.entity.EquipmentSlot.OFFHAND}) {
            ItemStack stack = villager.getItemBySlot(hand);
            if (!stack.isEmpty()) {
                stacks.add(stack.copy());
                villager.setItemSlot(hand, ItemStack.EMPTY);
            }
        }
        int total = 0;
        for (ItemStack stack : stacks) {
            ItemEntity entity = new ItemEntity(level, villager.getX(), villager.getY() + 0.3, villager.getZ(), stack);
            entity.setUnlimitedLifetime();
            entity.setPickUpDelay(20);
            level.addFreshEntity(entity);
            total += stack.getCount();
        }
        return total;
    }

    private void trim() {
        while (data().deaths.size() > RECORD_LIMIT) {
            int drop = 0;
            while (drop < data().deaths.size() - 1 && !finished(data().deaths.get(drop))) {
                drop++;
            }
            data().deaths.remove(drop);
        }
    }

    private static boolean finished(ColonyData.Death death) {
        return death.buried && !"pending".equals(death.remains);
    }

    private static Component who(ColonyData.Death death) {
        return death.child ? Component.translatable("smartcolony.grave.child") : Component.translatable("smartcolony.role." + death.role);
    }

    public ColonyData.Death find(String id) {
        for (ColonyData.Death death : data().deaths) {
            if (death.id.equals(id)) {
                return death;
            }
        }
        return null;
    }

    public ColonyData.Death nextPending(ServerLevel level) {
        long now = level.getGameTime();
        ColonyData.Death best = null;
        for (ColonyData.Death death : data().deaths) {
            if (!"pending".equals(death.remains)) {
                continue;
            }
            if (now - death.time > REMAINS_PATIENCE) {
                markLost(level, death);
                continue;
            }
            if (best == null || death.time < best.time) {
                best = death;
            }
        }
        return best;
    }

    public void markLost(ServerLevel level, ColonyData.Death death) {
        death.remains = "lost";
        cachedUntil = 0;
        colony.journal(level, "the belongings of " + death.name + " could not be recovered");
        colony.markDirty();
    }

    public ItemEntity nearestRemains(ServerLevel level, Villager villager, ColonyData.Death death) {
        BlockPos at = WorldUtil.bp(death.pos);
        if (!WorldUtil.loaded(level, at)) {
            return null;
        }
        AABB box = new AABB(at).inflate(PICKUP_RADIUS, 4, PICKUP_RADIUS);
        return level.getEntitiesOfClass(ItemEntity.class, box, item -> item.isAlive() && item.getAge() < 0).stream()
                .min(Comparator.comparingDouble(item -> item.distanceToSqr(villager)))
                .orElse(null);
    }

    public void finishRecovery(ServerLevel level, ColonyData.Death death) {
        death.remains = death.recovered > 0 ? "recovered" : "lost";
        cachedUntil = 0;
        if (death.recovered > 0) {
            data().bump("remains_items", death.recovered);
            colony.journal(level, "recovered " + death.recovered + " items of " + death.name);
        } else {
            colony.journal(level, "nothing was left of the belongings of " + death.name);
        }
        colony.markDirty();
    }

    public ColonyData.Death nextToBury() {
        ColonyData.Death best = null;
        for (ColonyData.Death death : data().deaths) {
            if (!death.buried && !"pending".equals(death.remains) && (best == null || death.time < best.time)) {
                best = death;
            }
        }
        return best;
    }

    public int freeSlots() {
        int free = 0;
        for (ColonyData.Building building : data().buildings) {
            if (!Cemetery.ID.equals(building.blueprint)) {
                continue;
            }
            for (Cemetery.Slot slot : Cemetery.slots()) {
                BlockPos stone = colony.construction().world(building, slot.x(), 1, slot.z());
                if (!data().graves.contains(WorldUtil.pos(stone))) {
                    free++;
                }
            }
        }
        return free;
    }

    public Site nextSite() {
        for (ColonyData.Building building : data().buildings) {
            if (!Cemetery.ID.equals(building.blueprint)) {
                continue;
            }
            for (Cemetery.Slot slot : Cemetery.slots()) {
                BlockPos stone = colony.construction().world(building, slot.x(), 1, slot.z());
                if (data().graves.contains(WorldUtil.pos(stone))) {
                    continue;
                }
                BlockPos stand = colony.construction().world(building, slot.standX(), 1, slot.standZ());
                return new Site(building, slot, stone, stand);
            }
        }
        return null;
    }

    public void blockSite(Site site) {
        data().graves.add(WorldUtil.pos(site.stone()));
        cachedUntil = 0;
        colony.markDirty();
    }

    public boolean affordable(ServerLevel level, String wood) {
        Stock stock = colony.warehouse().stock(level);
        String sign = Ids.mc(wood + "_sign");
        return stock.get(Ids.mc("cobblestone")) >= 1 && (stock.get(sign) >= 1 || CraftPlanner.plan(stock, sign, 1).isPresent());
    }

    public boolean hasWork(ServerLevel level) {
        long now = level.getGameTime();
        if (now < cachedUntil) {
            return cachedWork;
        }
        cachedUntil = now + 40;
        cachedWork = computeWork(level);
        return cachedWork;
    }

    private boolean computeWork(ServerLevel level) {
        if (nextPending(level) != null) {
            return true;
        }
        if (nextToBury() == null) {
            return false;
        }
        Site site = nextSite();
        return site != null && affordable(level, site.building().wood);
    }

    public int undertakersWanted(ServerLevel level) {
        return hasWork(level) ? 1 : 0;
    }

    public boolean needsCemetery(int adults, int cemeteries, int projects) {
        return Cemetery.needed(adults, data().stat("deaths"), cemeteries, projects, freeSlots(), (int) data().unburied());
    }

    private static Predicate<ItemStack> is(String id) {
        return stack -> ItemIds.id(stack).equals(id);
    }

    public boolean bury(ServerLevel level, Villager villager, Site site, ColonyData.Death death) {
        if (!WorldUtil.isAirLike(level.getBlockState(site.stone()))) {
            blockSite(site);
            return false;
        }
        ColonyData.Building building = site.building();
        Construction construction = colony.construction();
        int x = site.slot().x();
        int z = site.slot().z();
        Inv.remove(villager, is(Ids.mc("cobblestone")), 1);
        Placer.place(level, villager, site.stone(), Construction.parse(Ids.mc("cobblestone"), 0));
        BlockPos signPos = construction.world(building, x, 1, z + 1);
        BlockState sign = Construction.parse(Ids.mc(building.wood + "_wall_sign") + "[facing=south]", building.rotation);
        if (!sign.isAir() && WorldUtil.isAirLike(level.getBlockState(signPos))
                && Inv.remove(villager, is(Ids.mc(building.wood + "_sign")), 1) > 0) {
            Placer.place(level, villager, signPos, sign);
            writeSign(level, signPos, death);
        }
        boolean mound = false;
        for (int step = 1; step <= 2; step++) {
            BlockPos ground = construction.world(building, x, 0, z + step);
            if (Inv.remove(villager, is(Ids.mc("dirt")), 1) > 0) {
                Placer.place(level, villager, ground, Construction.parse(Ids.mc("dirt"), 0));
                mound = true;
            }
        }
        BlockPos flowerPos = construction.world(building, x, 1, z + 2);
        if (mound && WorldUtil.isAirLike(level.getBlockState(flowerPos))) {
            for (String flower : FLOWERS) {
                String id = Ids.mc(flower);
                if (Inv.remove(villager, is(id), 1) > 0) {
                    BlockState state = Construction.parse(id, 0);
                    if (!state.isAir() && state.canSurvive(level, flowerPos)) {
                        Placer.place(level, villager, flowerPos, state);
                    }
                    break;
                }
            }
        }
        death.buried = true;
        death.grave = WorldUtil.pos(site.stone());
        data().graves.add(death.grave);
        data().bump("buried", 1);
        cachedUntil = 0;
        colony.journal(level, "buried " + death.role + " " + death.name);
        colony.notify(level, Component.translatable("smartcolony.chat.buried", who(death), death.name));
        colony.markDirty();
        return true;
    }

    private void writeSign(ServerLevel level, BlockPos signPos, ColonyData.Death death) {
        if (level.getBlockEntity(signPos) instanceof SignBlockEntity entity) {
            entity.setText(entity.getText(SignTextSlot.FRONT).asMutable()
                    .setLine(0, Component.literal(death.name))
                    .setLine(1, who(death))
                    .setLine(2, Component.translatable("smartcolony.grave.day", death.day))
                    .setLine(3, Component.translatable("smartcolony.cause." + death.cause))
                    .asImmutable(), SignTextSlot.FRONT);
            entity.setWaxed(true);
        }
    }
}
