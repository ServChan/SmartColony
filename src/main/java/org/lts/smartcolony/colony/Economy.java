package org.lts.smartcolony.colony;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.util.ItemIds;

public final class Economy {
    private static final Map<String, Double> NUTRITION = Map.ofEntries(
            Map.entry(Ids.mc("bread"), 5.0),
            Map.entry(Ids.mc("wheat"), 5.0 / 3.0),
            Map.entry(Ids.mc("carrot"), 3.0),
            Map.entry(Ids.mc("potato"), 1.0),
            Map.entry(Ids.mc("baked_potato"), 5.0),
            Map.entry(Ids.mc("beetroot"), 1.0),
            Map.entry(Ids.mc("apple"), 4.0),
            Map.entry(Ids.mc("melon_slice"), 2.0),
            Map.entry(Ids.mc("sweet_berries"), 2.0),
            Map.entry(Ids.mc("cod"), 2.0),
            Map.entry(Ids.mc("salmon"), 2.0),
            Map.entry(Ids.mc("cooked_cod"), 5.0),
            Map.entry(Ids.mc("cooked_salmon"), 6.0),
            Map.entry(Ids.mc("beef"), 3.0),
            Map.entry(Ids.mc("porkchop"), 3.0),
            Map.entry(Ids.mc("mutton"), 2.0),
            Map.entry(Ids.mc("chicken"), 2.0),
            Map.entry(Ids.mc("rabbit"), 3.0),
            Map.entry(Ids.mc("cooked_beef"), 8.0),
            Map.entry(Ids.mc("cooked_porkchop"), 8.0),
            Map.entry(Ids.mc("cooked_mutton"), 6.0),
            Map.entry(Ids.mc("cooked_chicken"), 6.0),
            Map.entry(Ids.mc("cooked_rabbit"), 5.0),
            Map.entry(Ids.mc("dried_kelp"), 1.0));
    private static final List<String> RAW = List.of("cod", "salmon", "beef", "porkchop", "mutton", "chicken", "rabbit", "potato");

    private final Colony colony;
    private final Market market;

    public Economy(Colony colony) {
        this.colony = colony;
        this.market = new Market(colony);
    }

    public Market market() {
        return market;
    }

    public int foodPoints(Stock stock) {
        double total = 0;
        for (Map.Entry<String, Double> entry : NUTRITION.entrySet()) {
            total += stock.get(entry.getKey()) * entry.getValue();
        }
        return (int) Math.round(total);
    }

    public static double nutrition(String id) {
        return NUTRITION.getOrDefault(id, 0.0);
    }

    public void tick(ServerLevel level, long ticks) {
        if (ticks % 600 == 101) {
            golems(level);
        }
        if (ticks % 1200 == 211) {
            feed(level);
            cook(level);
        }
        if (ticks % 3000 == 1517) {
            births(level);
        }
        if (ticks % SmartColonyConfig.get().marketIntervalTicks == 307) {
            market.run(level);
        }
    }

    public void cook(ServerLevel level) {
        Stock stock = colony.warehouse().stock(level);
        for (String raw : RAW) {
            int have = stock.get(Ids.mc(raw));
            if (have <= 0) {
                continue;
            }
            String cooked = raw.equals("potato") ? Ids.mc("baked_potato") : Ids.mc("cooked_" + raw);
            int portion = raw.equals("potato") ? Math.max(0, have - 24) / 2 : (have + 1) / 2;
            if (portion > 0) {
                colony.craft(level, cooked, stock.get(cooked) + portion);
                stock = colony.warehouse().stock(level);
            }
        }
        for (String raw : List.of("raw_iron", "raw_copper", "raw_gold")) {
            int have = stock.get(Ids.mc(raw));
            if (have > 0) {
                String ingot = Ids.mc(raw.substring(4) + "_ingot");
                colony.craft(level, ingot, stock.get(ingot) + have);
                stock = colony.warehouse().stock(level);
            }
        }
        int wheat = stock.get(Ids.mc("wheat"));
        if (wheat > 30) {
            int loaves = (wheat - 30) / 3;
            colony.craft(level, Ids.mc("bread"), stock.get(Ids.mc("bread")) + loaves);
        }
    }

    private void golems(ServerLevel level) {
        SmartColonyConfig config = SmartColonyConfig.get();
        int radius = colony.data().radius + 16;
        AABB box = new AABB(colony.center()).inflate(radius, 32, radius);
        List<IronGolem> golems = level.getEntitiesOfClass(IronGolem.class, box, IronGolem::isAlive);
        int repaired = 0;
        for (IronGolem golem : golems) {
            if (repaired >= 3 || golem.getHealth() > golem.getMaxHealth() * 0.6F) {
                continue;
            }
            if (colony.warehouse().take(level, Ids.mc("iron_ingot"), 1) == 1) {
                golem.heal(25.0F);
                level.playSound(null, golem.blockPosition(), SoundEvents.IRON_GOLEM_REPAIR, SoundSource.NEUTRAL, 1.0F, 1.0F);
                colony.data().bump("golems_repaired", 1);
                repaired++;
            }
        }
        int adults = colony.adultCount(level);
        int wanted = Math.min(config.maxGolems, adults / config.villagersPerGolem);
        if (golems.size() >= wanted) {
            return;
        }
        int cost = config.golemIronCost;
        Stock stock = colony.warehouse().stock(level);
        if (stock.get(Ids.mc("iron_ingot")) < cost && !colony.craft(level, Ids.mc("iron_ingot"), cost)) {
            colony.request(Ids.mc("iron_ingot"));
            return;
        }
        if (colony.warehouse().stock(level).get(Ids.mc("iron_ingot")) < cost) {
            return;
        }
        BlockPos at = colony.depot();
        IronGolem golem = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.MOB_SUMMONED);
        if (golem == null) {
            return;
        }
        colony.warehouse().take(level, Ids.mc("iron_ingot"), cost);
        golem.setPlayerCreated(false);
        golem.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360.0F, 0.0F);
        level.addFreshEntity(golem);
        level.sendParticles(ParticleTypes.CLOUD, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 30, 0.5, 1.0, 0.5, 0.02);
        level.playSound(null, at, SoundEvents.IRON_GOLEM_REPAIR, SoundSource.NEUTRAL, 1.0F, 0.6F);
        colony.data().bump("golems_built", 1);
        colony.journal(level, "iron golem forged from " + cost + " ingots");
        colony.notify(level, Component.translatable("smartcolony.chat.golem", cost));
    }

    private void births(ServerLevel level) {
        int radius = colony.data().radius + 8;
        List<BlockPos> free = level.getPoiManager().findAll(type -> type.is(PoiTypes.HOME), pos -> true, colony.center(), radius,
                PoiManager.Occupancy.HAS_SPACE).limit(4).toList();
        long beds = level.getPoiManager().getCountInRange(type -> type.is(PoiTypes.HOME), colony.center(), radius, PoiManager.Occupancy.ANY);
        if (free.isEmpty() || colony.colonists().size() >= beds || foodPoints(colony.warehouse().stock(level)) < 100) {
            return;
        }
        List<Villager> parents = colony.loadedVillagers(level).stream()
                .filter(v -> !v.isBaby() && v.getAge() == 0 && v.canBreed() && !v.isSleeping())
                .limit(2).toList();
        if (parents.size() < 2) {
            return;
        }
        Villager mother = parents.get(0);
        Villager father = parents.get(1);
        Villager baby = mother.getBreedOffspring(level, father);
        if (baby == null) {
            return;
        }
        BlockPos bed = free.getFirst();
        BlockPos spot = mother.blockPosition();
        baby.setAge(-24000);
        baby.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0.0F, 0.0F);
        level.addFreshEntity(baby);
        mother.setAge(6000);
        father.setAge(6000);
        for (Villager parent : parents) {
            org.lts.smartcolony.work.Inv.remove(parent, stack -> stack.is(net.minecraft.world.item.Items.BREAD), 3);
            level.sendParticles(ParticleTypes.HEART, parent.getX(), parent.getY() + 2.0, parent.getZ(), 4, 0.3, 0.3, 0.3, 0.0);
        }
        colony.data().bump("births", 1);
        colony.journal(level, "a child was born, free bed at " + bed.toShortString());
        colony.notify(level, Component.translatable("smartcolony.chat.birth"));
    }

    private static final int BREED_BREAD = 3;

    private void reclaimBread(ServerLevel level) {
        String bread = Ids.mc("bread");
        int returned = 0;
        for (Villager villager : colony.loadedVillagers(level)) {
            int extra = org.lts.smartcolony.work.Inv.count(villager, bread) - BREED_BREAD;
            if (extra > 0) {
                int removed = org.lts.smartcolony.work.Inv.remove(villager, stack -> stack.is(net.minecraft.world.item.Items.BREAD), extra);
                if (removed > 0) {
                    colony.warehouse().give(level, bread, removed);
                    returned += removed;
                }
            }
        }
        if (returned > 0) {
            colony.data().bump("bread_returned", returned);
            colony.journal(level, "took " + returned + " spare bread back from villagers' pockets");
            colony.markDirty();
        }
    }

    private void feed(ServerLevel level) {
        reclaimBread(level);
        int radius = colony.data().radius;
        long freeBeds = level.getPoiManager().getCountInRange(type -> type.is(PoiTypes.HOME), colony.center(), radius,
                PoiManager.Occupancy.HAS_SPACE);
        if (freeBeds < 1) {
            return;
        }
        if (foodPoints(colony.warehouse().stock(level)) < 80) {
            return;
        }
        int fed = 0;
        for (Villager villager : colony.loadedVillagers(level)) {
            if (fed >= 2) {
                break;
            }
            if (villager.isBaby() || villager.isSleeping() || villager.canBreed() || villager.getAge() != 0
                    || org.lts.smartcolony.work.Inv.count(villager, Ids.mc("bread")) >= BREED_BREAD) {
                continue;
            }
            if (colony.warehouse().stock(level).get(Ids.mc("bread")) < 3) {
                colony.craft(level, Ids.mc("bread"), 3);
            }
            List<ItemStack> food = colony.warehouse().extract(level, stack -> ItemIds.id(stack).equals(Ids.mc("bread")), 3);
            if (food.isEmpty()) {
                break;
            }
            for (ItemStack stack : food) {
                ItemStack rest = villager.getInventory().addItem(stack);
                if (!rest.isEmpty()) {
                    colony.warehouse().insert(level, rest);
                }
            }
            fed++;
        }
        if (fed > 0) {
            colony.data().bump("villagers_fed", fed);
        }
    }
}
