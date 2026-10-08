package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.Category;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Hospital;
import org.lts.smartcolony.core.ItemClassifier;
import org.lts.smartcolony.core.Reputation;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.TownHall;
import org.lts.smartcolony.core.TradePlanner;
import org.lts.smartcolony.work.Colonist;

public record ColonyView(
        String name,
        int members,
        int children,
        boolean kindergarten,
        Graveyard.Summary graveyard,
        int beds,
        int buildings,
        List<RoleCount> roles,
        List<Storage> storages,
        int food,
        String project,
        String phase,
        List<ItemCount> missing,
        String mine,
        int mineProgress,
        int mineTotal,
        int reputation,
        String status,
        List<Member> villagers,
        List<String> journal,
        List<Market.Deal> deals,
        List<String> requests,
        String wallMaterial,
        int wallBuilt,
        int wallTotal,
        List<String> sites,
        String advisorState,
        String advisorText,
        String age,
        TraderInfo trader,
        int townHall,
        int townHallNeeds,
        TavernInfo tavern,
        Education education,
        String hospital,
        int hospitalNeeds,
        Hall.Summary hall,
        Society society) {

    public record Society(String focus, List<String> zones, List<String> orders, String slot, boolean restDay, boolean fed,
                          int ores, int villages, int nests, int recruits, int caravans, int packAnimals, int fires, int raids,
                          int raidsWon, int knowledge, int potions, int snowCleared, int chores, List<String> specialists) {
    }

    public record TavernInfo(boolean built, boolean open, int meals, int travellers, int settlers, int guests) {
    }

    public record Education(boolean school, boolean barracks, int pupils, int lessons, int graduates, List<RoleCount> byRole,
                            int drillSessions, int drillArrows, int drillXp) {
    }

    public record TraderOffer(String give, int giveCount, String result, int resultCount, int uses) {
    }

    public record TraderInfo(boolean marketBuilt, boolean hired, List<TraderOffer> buys, List<TraderOffer> sells) {
    }

    public record RoleCount(String role, int count) {
    }

    public record ItemCount(String item, int count) {
    }

    public record Storage(String category, int percent, List<ItemCount> items) {
    }

    public record Member(String role, boolean baby, boolean working, float health, float maxHealth, String hand, int distance, int skill) {
    }

    public static ColonyView capture(ServerLevel level, Colony colony, UUID player) {
        ColonyData data = colony.data();
        EnumMap<Role, Integer> roleCounts = new EnumMap<>(Role.class);
        List<Member> villagers = new ArrayList<>();
        int children = 0;
        int pupils = 0;
        BlockPos center = colony.center();
        for (Map.Entry<UUID, Colonist> entry : colony.colonists().entrySet()) {
            Colonist colonist = entry.getValue();
            if (!(level.getEntity(entry.getKey()) instanceof Villager villager)) {
                continue;
            }
            boolean baby = villager.isBaby();
            if (baby) {
                children++;
                ColonyData.Member pupil = data.members.get(entry.getKey().toString());
                if (pupil != null && !pupil.study.isEmpty()) {
                    pupils++;
                }
            } else {
                roleCounts.merge(colonist.role(), 1, Integer::sum);
            }
            String hand = villager.getMainHandItem().isEmpty() ? ""
                    : BuiltInRegistries.ITEM.getKey(villager.getMainHandItem().getItem()).toString();
            int distance = (int) Math.sqrt(villager.blockPosition().distSqr(center));
            villagers.add(new Member(colonist.role().key(), baby, Colonist.isControlled(villager), villager.getHealth(),
                    villager.getMaxHealth(), hand, distance, colony.skill(entry.getKey(), colonist.role())));
        }
        villagers.sort((a, b) -> a.role().equals(b.role()) ? Integer.compare(a.distance(), b.distance()) : a.role().compareTo(b.role()));
        List<RoleCount> roles = new ArrayList<>();
        for (Map.Entry<Role, Integer> entry : roleCounts.entrySet()) {
            roles.add(new RoleCount(entry.getKey().key(), entry.getValue()));
        }
        Map<Category, Double> fill = colony.fill(level);
        Map<String, Integer> stock = colony.warehouse().stock(level).sorted();
        List<Storage> storages = new ArrayList<>();
        for (Category category : Category.values()) {
            List<ItemCount> items = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : stock.entrySet()) {
                if (ItemClassifier.classify(entry.getKey()) == category && entry.getValue() > 0) {
                    items.add(new ItemCount(entry.getKey(), entry.getValue()));
                }
            }
            items.sort((a, b) -> Integer.compare(b.count(), a.count()));
            storages.add(new Storage(category.key(), (int) Math.round(fill.getOrDefault(category, 0.0) * 100), items));
        }
        String project = null;
        String phase = null;
        List<ItemCount> missing = new ArrayList<>();
        List<String> sites = new ArrayList<>();
        ColonyData.Project primary = colony.construction().primary();
        for (ColonyData.Project other : data.projects) {
            if (other != primary) {
                sites.add(other.building.blueprint + "|" + other.phase);
            }
        }
        if (primary != null) {
            project = primary.building.blueprint;
            phase = primary.phase;
            for (Map.Entry<String, Integer> entry : colony.construction().missing().entrySet()) {
                missing.add(new ItemCount(entry.getKey(), entry.getValue()));
            }
        }
        String mine = null;
        int mineProgress = 0;
        int mineTotal = 0;
        if (data.mine != null) {
            mine = data.mine.entrance.x() + ", " + data.mine.entrance.y() + ", " + data.mine.entrance.z();
            mineProgress = data.mine.progress;
            mineTotal = data.mine.plan().size();
        }
        int reputation = player == null ? 0 : colony.reputation(player);
        List<String> journal = new ArrayList<>();
        List<String> lines = data.journal;
        for (int i = lines.size() - 1; i >= Math.max(0, lines.size() - 60); i--) {
            String line = lines.get(i);
            int space = line.indexOf(' ');
            journal.add(space > 0 ? line.substring(space + 1) : line);
        }
        List<Market.Deal> ledger = colony.economy().market().ledger();
        List<Market.Deal> deals = new ArrayList<>(ledger.subList(Math.max(0, ledger.size() - 40), ledger.size()));
        java.util.Collections.reverse(deals);
        List<TraderOffer> traderBuys = new ArrayList<>();
        List<TraderOffer> traderSells = new ArrayList<>();
        if (colony.trader().hired()) {
            TradePlanner.Plan plan = TraderTrades.plan(level, colony, Reputation.status(reputation));
            for (TradePlanner.Offer offer : plan.buys()) {
                traderBuys.add(new TraderOffer(offer.give(), offer.giveCount(), offer.result(), offer.resultCount(), offer.maxUses()));
            }
            for (TradePlanner.Offer offer : plan.sells()) {
                traderSells.add(new TraderOffer(offer.give(), offer.giveCount(), offer.result(), offer.resultCount(), offer.maxUses()));
            }
        }
        List<RoleCount> graduates = new ArrayList<>();
        for (Role role : Role.values()) {
            int count = data.stat("graduates_" + role.key());
            if (count > 0) {
                graduates.add(new RoleCount(role.key(), count));
            }
        }
        Education education = new Education(Schoolhouse.find(colony) != null, Garrison.find(colony) != null, pupils,
                data.stat("lessons_given"), data.stat("graduates"), graduates, data.stat("drill_sessions"),
                data.stat("drill_arrows"), data.stat("drill_xp"));
        TraderInfo traderInfo = new TraderInfo(colony.trader().marketBuilt(), colony.trader().hired(), traderBuys, traderSells);
        return new ColonyView(data.name, colony.colonists().size(), children, Nursery.find(colony) != null, colony.graveyard().summary(), data.beds(), data.buildings.size(), roles, storages,
                colony.foodPoints(level), project, phase, missing, mine, mineProgress, mineTotal, reputation,
                Reputation.status(reputation).key(), villagers, journal, deals, new ArrayList<>(colony.requests()),
                data.wall == null ? null : data.wall.material, data.wall == null ? 0 : data.wall.built,
                data.wall == null ? 0 : data.wall.total, sites, colony.advisor().active() || !colony.advisor().state().equals("off")
                        ? colony.advisor().state() : "off",
                colony.advisor().state().equals("error") ? colony.advisor().error() : colony.advisor().thoughts(), data.age, traderInfo,
                TownHall.stage(data), TownHall.stage(data) >= TownHall.STAGES ? 0
                : TownHall.requiredAdults(TownHall.stage(data) + 1, SmartColonyConfig.get().townHallAdults),
                new TavernInfo(colony.tavern().built(), colony.tavern().open(), colony.tavern().meals(), colony.tavern().travellers(),
                        colony.tavern().settlers(), colony.tavern().guestCount()), education,

                colony.ward().summary(), Hospital.stage(data) >= Hospital.STAGES ? 0
                : Hospital.requiredAdults(Hospital.stage(data) + 1, SmartColonyConfig.get().infirmaryAdults),
                colony.hall().summary(level), society(level, colony));
    }

    private static Society society(ServerLevel level, Colony colony) {
        ColonyData data = colony.data();
        List<String> zones = new ArrayList<>();
        for (org.lts.smartcolony.core.Decree.Zone zone : data.zones) {
            zones.add(zone.x() + " " + zone.z() + " r" + zone.radius());
        }
        List<String> orders = new ArrayList<>();
        for (ColonyData.Contract contract : data.contracts) {
            orders.add(contract.item + "|" + (contract.delivered + contract.parcel) + "|" + contract.count + "|" + contract.parcel);
        }
        int ores = 0;
        int villages = 0;
        int nests = 0;
        for (ColonyData.Discovery discovery : data.discoveries) {
            switch (discovery.kind) {
                case org.lts.smartcolony.core.Expedition.ORE -> ores++;
                case org.lts.smartcolony.core.Expedition.VILLAGE -> villages++;
                case org.lts.smartcolony.core.Expedition.NEST -> nests++;
                default -> {
                }
            }
        }
        List<String> specialists = new ArrayList<>();
        for (String kind : List.of(org.lts.smartcolony.core.Specialists.MASONRY, org.lts.smartcolony.core.Specialists.APIARY,
                org.lts.smartcolony.core.Specialists.APOTHECARY, org.lts.smartcolony.core.Specialists.STABLE,
                org.lts.smartcolony.core.Specialists.LIBRARY)) {
            if (colony.builtKind(kind)) {
                specialists.add(kind);
            }
        }
        return new Society(data.focus, zones, orders, colony.daily().slot(level).name().toLowerCase(java.util.Locale.ROOT),
                colony.daily().restDay(level), colony.daily().fed(level), ores, villages, nests, data.stat("settlers_recruited"),
                data.caravans.size(), data.packAnimals.size(), data.stat("fires_put_out"), data.stat("raids"), data.stat("raids_won"),
                data.knowledge, data.stat("potions_brewed"), data.stat("snow_cleared"), colony.chores().pending(), specialists);
    }
}
