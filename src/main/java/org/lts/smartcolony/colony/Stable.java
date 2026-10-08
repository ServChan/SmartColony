package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.TraderLlama;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.Specialists;
import org.lts.smartcolony.mixin.AbstractHorseAccessor;

public final class Stable {
    private static final int MAX_ANIMALS = 6;

    private final Colony colony;
    private List<BlockPos> posts = List.of();
    private ColonyData.Building postsFor;
    private final Map<UUID, UUID> borrowed = new HashMap<>();

    public Stable(Colony colony) {
        this.colony = colony;
    }

    public ColonyData.Building building() {
        return colony.buildingOfKind(Specialists.STABLE);
    }

    public int animals() {
        return colony.data().packAnimals.size();
    }

    public List<BlockPos> posts(ServerLevel level) {
        ColonyData.Building building = building();
        if (building == null) {
            return List.of();
        }
        if (building != postsFor) {
            postsFor = building;
            List<BlockPos> found = new ArrayList<>();
            int y = building.origin.y() + 1;
            for (int x = building.min.x(); x <= building.max.x(); x++) {
                for (int z = building.min.z(); z <= building.max.z(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getBlockState(pos).is(BlockTags.FENCES)) {
                        found.add(pos);
                    }
                }
            }
            posts = found;
        }
        return posts;
    }

    private AABB area(ColonyData.Building building) {
        return new AABB(building.min.x(), building.min.y(), building.min.z(), building.max.x() + 1, building.max.y() + 1,
                building.max.z() + 1).inflate(6, 2, 6);
    }

    public void tick(ServerLevel level, long ticks) {
        if (ticks % 200 != 77) {
            return;
        }
        ColonyData.Building building = building();
        List<String> known = colony.data().packAnimals;
        known.removeIf(id -> {
            Entity entity = level.getEntity(UUID.fromString(id));
            return entity != null && !entity.isAlive();
        });
        if (building == null) {
            return;
        }
        for (AbstractHorse horse : level.getEntitiesOfClass(AbstractHorse.class, area(building),
                h -> h.isAlive() && !h.isBaby() && !(h instanceof TraderLlama) && tiedHere(level, h))) {
            String id = horse.getUUID().toString();
            if (!horse.isTamed()) {
                horse.setTamed(true);
            }
            if (horse instanceof AbstractChestedHorse chested && !chested.hasChest()
                    && colony.warehouse().take(level, Ids.mc("chest"), 1) == 1) {
                chested.setChest(true);
                ((AbstractHorseAccessor) chested).smartcolony$createInventory();
                colony.journal(level, "a pack chest strapped onto a " + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(horse.getType()).getPath() + " in the stable");
            }
            if (!known.contains(id)) {
                known.add(id);
                colony.data().bump("pack_animals", 1);
                colony.markDirty();
            }
        }
    }

    private boolean tiedHere(ServerLevel level, AbstractHorse horse) {
        Entity holder = horse.getLeashHolder();
        if (holder instanceof LeashFenceKnotEntity knot) {
            return posts(level).contains(knot.getPos());
        }
        return colony.data().packAnimals.contains(horse.getUUID().toString()) && holder == null;
    }

    public boolean wantsAnimal(ServerLevel level) {
        if (building() == null) {
            return false;
        }
        int porters = Math.max(1, colony.desired().getOrDefault(Role.PORTER, 0));
        return animals() < Math.min(MAX_ANIMALS, Math.min(posts(level).size(), porters + 1));
    }

    public AbstractHorse findWild(ServerLevel level, Villager villager) {
        int radius = SmartColonyConfig.get().forestRadius + 48;
        return level.getEntitiesOfClass(AbstractHorse.class, new AABB(colony.center()).inflate(radius, 24, radius),
                        h -> h.isAlive() && !h.isBaby() && !h.isLeashed() && !h.isVehicle() && !h.isTamed()
                                && !(h instanceof TraderLlama) && !(h instanceof net.minecraft.world.entity.animal.equine.SkeletonHorse)
                                && !(h instanceof net.minecraft.world.entity.animal.equine.ZombieHorse)
                                && !colony.data().packAnimals.contains(h.getUUID().toString()) && !h.isInWater())
                .stream()
                .min(Comparator.comparingDouble(h -> (h instanceof AbstractChestedHorse ? 0 : 400) + h.distanceToSqr(villager)))
                .orElse(null);
    }

    public BlockPos freePost(ServerLevel level) {
        for (BlockPos post : posts(level)) {
            boolean taken = false;
            for (LeashFenceKnotEntity knot : level.getEntitiesOfClass(LeashFenceKnotEntity.class, new AABB(post).inflate(0.5))) {
                for (AbstractHorse horse : level.getEntitiesOfClass(AbstractHorse.class, new AABB(post).inflate(8))) {
                    if (horse.getLeashHolder() == knot) {
                        taken = true;
                        break;
                    }
                }
            }
            if (!taken) {
                return post;
            }
        }
        return posts(level).isEmpty() ? null : posts(level).getFirst();
    }

    public void tie(ServerLevel level, AbstractHorse horse, BlockPos post) {
        LeashFenceKnotEntity knot = LeashFenceKnotEntity.getOrCreateKnot(level, post);
        horse.setLeashedTo(knot, true);
        borrowed.remove(horse.getUUID());
        String id = horse.getUUID().toString();
        if (!colony.data().packAnimals.contains(id)) {
            colony.data().packAnimals.add(id);
            colony.markDirty();
        }
    }

    public AbstractHorse borrow(ServerLevel level, UUID porter, BlockPos near) {
        borrowed.entrySet().removeIf(entry -> entry.getValue().equals(porter));
        AbstractHorse best = null;
        double bestDistance = 96 * 96;
        for (String id : colony.data().packAnimals) {
            UUID uuid = UUID.fromString(id);
            if (borrowed.containsKey(uuid) || !(level.getEntity(uuid) instanceof AbstractChestedHorse horse) || !horse.isAlive()
                    || !horse.hasChest() || horse.getLeashHolder() instanceof Villager) {
                continue;
            }
            double distance = horse.distanceToSqr(near.getX(), near.getY(), near.getZ());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = horse;
            }
        }
        if (best != null) {
            borrowed.put(best.getUUID(), porter);
        }
        return best;
    }

    public void giveUp(UUID porter) {
        borrowed.entrySet().removeIf(entry -> entry.getValue().equals(porter));
    }

    public static boolean keepClose(AbstractHorse horse, Villager leader) {
        double gap = horse.distanceTo(leader);
        if (gap > 3.5) {
            horse.getNavigation().moveTo(leader, 1.3);
        }
        if (gap > 6.0) {
            leader.getNavigation().stop();
            leader.getLookControl().setLookAt(horse);
            return true;
        }
        return false;
    }

    public static SimpleContainer pack(AbstractHorse horse) {
        return ((AbstractHorseAccessor) horse).smartcolony$inventory();
    }

    public static int load(AbstractHorse horse, List<ItemStack> overflow) {
        SimpleContainer pack = pack(horse);
        int moved = 0;
        for (int i = 0; i < overflow.size(); i++) {
            ItemStack stack = overflow.get(i);
            int before = stack.getCount();
            ItemStack rest = pack.addItem(stack);
            moved += before - rest.getCount();
            overflow.set(i, rest);
        }
        return moved;
    }
}
