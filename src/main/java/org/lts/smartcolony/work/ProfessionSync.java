package org.lts.smartcolony.work;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Professions;
import org.lts.smartcolony.core.Role;

public final class ProfessionSync {
    private static final int PERIOD = 100;

    private ProfessionSync() {
    }

    public static void tick(ServerLevel level, Colonist colonist, Villager villager) {
        if (Math.floorMod(level.getGameTime() + colonist.uuid().hashCode(), PERIOD) != 0) {
            return;
        }
        if (villager.isBaby() || !villager.isAlive() || villager.isTrading() || colonist.role() == Role.IDLE) {
            return;
        }
        Colony colony = colonist.colony();
        ColonyData.Member member = colony.data().members.get(colonist.uuid().toString());
        if (member == null) {
            return;
        }
        VillagerData data = villager.getVillagerData();
        String current = data.profession().unwrapKey().map(key -> key.identifier().getPath()).orElse(Professions.NONE);
        int skill = colony.skill(colonist.uuid(), colonist.role());
        Professions.Plan plan = Professions.plan(current, data.level(), colonist.role(), skill, level.getGameTime() - member.since);
        if (plan != null) {
            if (member.affinity.isEmpty()) {
                member.affinity = Role.affinity(current).key();
                colony.markDirty();
            }
            apply(level, villager, data, plan);
        }
        if (!Professions.NITWIT.equals(current)) {
            int level1 = villager.getVillagerData().level();
            int xp = Professions.clampXp(level1, villager.getVillagerXp());
            if (xp != villager.getVillagerXp()) {
                villager.setVillagerXp(xp);
            }
        }
    }

    private static void apply(ServerLevel level, Villager villager, VillagerData data, Professions.Plan plan) {
        ProfessionAccess access = (ProfessionAccess) villager;
        int oldLevel = data.level();
        boolean incremental = Professions.incremental(plan.professionChange(), oldLevel, plan.level());
        MerchantOffers previous = plan.professionChange() ? null : villager.getOffers();
        int from = incremental ? oldLevel + 1 : 1;
        access.smartcolony$setProfessionOpen(true);
        try {
            if (plan.professionChange()) {
                ResourceKey<VillagerProfession> key = ResourceKey.create(Registries.VILLAGER_PROFESSION,
                        Identifier.withDefaultNamespace(plan.profession()));
                villager.setVillagerData(villager.getVillagerData().withProfession(level.registryAccess(), key).withLevel(1));
                releaseJobSite(level, villager);
            }
            if (!incremental) {
                villager.setOffers(new MerchantOffers());
            }
            for (int step = from; step <= plan.level(); step++) {
                villager.setVillagerData(villager.getVillagerData().withLevel(step));
                access.smartcolony$updateTrades(level);
            }
            villager.setVillagerData(villager.getVillagerData().withLevel(plan.level()));
            villager.setVillagerXp(Professions.clampXp(plan.level(), 0));
            if (plan.professionChange()) {
                villager.refreshBrain(level);
            }
        } finally {
            access.smartcolony$setProfessionOpen(false);
        }
        if (previous != null && !incremental) {
            restoreUses(previous, villager.getOffers());
        }
    }

    private static void releaseJobSite(ServerLevel level, Villager villager) {
        for (MemoryModuleType<GlobalPos> type : java.util.List.of(MemoryModuleType.JOB_SITE, MemoryModuleType.POTENTIAL_JOB_SITE)) {
            villager.getBrain().getMemory(type).ifPresent(pos -> {
                ServerLevel home = level.getServer().getLevel(pos.dimension());
                if (home != null) {
                    home.getPoiManager().release(pos.pos());
                }
            });
            villager.getBrain().eraseMemory(type);
        }
    }

    private static void restoreUses(MerchantOffers previous, MerchantOffers fresh) {
        for (MerchantOffer offer : fresh) {
            for (MerchantOffer old : previous) {
                if (old.getUses() > 0 && net.minecraft.world.item.ItemStack.isSameItemSameComponents(old.getResult(), offer.getResult())
                        && net.minecraft.world.item.ItemStack.isSameItemSameComponents(old.getBaseCostA(), offer.getBaseCostA())) {
                    for (int i = 0; i < old.getUses() && i < offer.getMaxUses(); i++) {
                        offer.increaseUses();
                    }
                    break;
                }
            }
        }
    }
}
