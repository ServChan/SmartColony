package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import org.lts.smartcolony.SmartColony;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.Category;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Diplomacy;
import org.lts.smartcolony.core.Expedition;
import org.lts.smartcolony.core.ItemClassifier;
import org.lts.smartcolony.core.PriceTable;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.Schedule;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.util.ItemIds;
import org.lts.smartcolony.util.WorldUtil;
import org.lts.smartcolony.work.Colonist;
import org.lts.smartcolony.work.GuardJob;

public final class Embassy {
    public static final String ENVOY = "envoy";
    public static final String CARAVAN = "caravan";
    public static final String VISIT = "visit";
    public static final String AID = "aid";
    public static final String RAID = "raid";
    private static final long DAY = 24000L;
    private static final int MISSION_ADULTS = 10;
    private static final int MISSION_BUILDINGS = 3;
    private static final long ENVOY_STAY = 400L;
    private static final long CARAVAN_STAY = 200L;
    private static final long VISIT_STAY = 2400L;
    private static final long AID_STAY = 3600L;
    private static final long AID_COOLDOWN = 6000L;
    private static final long RAID_ALERT = 2400L;
    private static final long GIFT_BUDGET = 1500L;
    private static final long LOOT_BUDGET = 4000L;
    private static final int TALK_RADIUS = 160;
    private static final List<Role> ENVOY_ROLES = List.of(Role.SCRIBE, Role.TEACHER, Role.INNKEEPER, Role.IDLE, Role.COOK,
            Role.FARMER, Role.LUMBERJACK, Role.FISHERMAN, Role.HERDER, Role.MASON, Role.BEEKEEPER, Role.SMITH, Role.FLETCHER,
            Role.ALCHEMIST, Role.MINER, Role.SCOUT);
    private static final List<Role> CARRIER_ROLES = List.of(Role.PORTER, Role.IDLE, Role.SCOUT, Role.FARMER, Role.LUMBERJACK,
            Role.HERDER, Role.FISHERMAN, Role.COOK, Role.MINER, Role.MASON);

    private final Colony colony;
    private final Map<String, List<Diplomacy.Reason>> reasons = new HashMap<>();

    Embassy(Colony colony) {
        this.colony = colony;
    }

    private ColonyData data() {
        return colony.data();
    }

    public Diplomacy.Temper temper() {
        Diplomacy.Temper temper = Diplomacy.Temper.byKey(data().temper);
        if (temper == null) {
            temper = Diplomacy.temperOf(colony.id());
            data().temper = temper.key();
            colony.markDirty();
        }
        return temper;
    }

    public Diplomacy.Policy policy() {
        return Diplomacy.Policy.byKey(data().policy);
    }

    public void setPolicy(Diplomacy.Policy policy) {
        data().policy = policy.key();
        colony.markDirty();
    }

    public ColonyData.Relation relation(Colony other) {
        return data().relations.get(other.id());
    }

    private ColonyData.Relation relationFor(Colony other, long now) {
        ColonyData.Relation relation = data().relations.get(other.id());
        if (relation == null) {
            relation = new ColonyData.Relation();
            relation.met = now;
            relation.standing = firstImpression(temper(), other.embassy().temper());
            data().relations.put(other.id(), relation);
            colony.markDirty();
        }
        return relation;
    }

    private static int firstImpression(Diplomacy.Temper self, Diplomacy.Temper other) {
        int value = 0;
        if (self == Diplomacy.Temper.PEACEFUL) {
            value += 6;
        }
        if (self == Diplomacy.Temper.PROUD) {
            value -= 6;
        }
        if (other == Diplomacy.Temper.MERCHANT && self == Diplomacy.Temper.MERCHANT) {
            value += 4;
        }
        return value;
    }

    public List<Diplomacy.Reason> reasons(String otherId) {
        return reasons.getOrDefault(otherId, List.of());
    }

    public static boolean atWar(Colony a, Colony b) {
        if (a == null || b == null || a == b) {
            return false;
        }
        ColonyData.Relation relation = a.data().relations.get(b.id());
        return relation != null && relation.treaty() == Diplomacy.Treaty.WAR;
    }

    public boolean relationAtWar(Colony other) {
        return atWar(colony, other);
    }

    public static boolean tradeAllowed(Colony a, Colony b) {
        ColonyData.Relation mine = a.data().relations.get(b.id());
        ColonyData.Relation theirs = b.data().relations.get(a.id());
        return (mine == null || Diplomacy.tradeAllowed(mine.standing, mine.treaty()))
                && (theirs == null || Diplomacy.tradeAllowed(theirs.standing, theirs.treaty()));
    }

    public ColonyData.Mission missionOf(UUID uuid) {
        String key = uuid.toString();
        for (ColonyData.Mission mission : data().missions) {
            if (mission.member.equals(key) && !mission.done) {
                return mission;
            }
        }
        return null;
    }

    public boolean onMission(UUID uuid) {
        return missionOf(uuid) != null;
    }

    public boolean isRaider(Entity entity) {
        if (!(entity instanceof Villager villager) || !villager.isAlive()) {
            return false;
        }
        Colony other = ColonyManager.colonyOf(villager);
        if (other == null || other == colony) {
            return false;
        }
        ColonyData.Mission mission = other.embassy().missionOf(villager.getUUID());
        return mission != null && RAID.equals(mission.kind) && colony.id().equals(mission.target);
    }

    public void tick(ServerLevel level, long ticks) {
        if (!SmartColonyConfig.get().diplomacy || ticks % 200 != 77) {
            return;
        }
        temper();
        long now = level.getGameTime();
        meet(level, now);
        prune(level);
        checkAid(level, now);
        long day = Schedule.day(level.getDefaultClockTime());
        if (data().diplomacyDay == day || !colony.workTime(level) || colony.defense().alarmed(level)) {
            return;
        }
        data().diplomacyDay = day;
        colony.markDirty();
        for (Map.Entry<String, ColonyData.Relation> entry : new ArrayList<>(data().relations.entrySet())) {
            Colony other = ColonyManager.byId(entry.getKey());
            if (other != null) {
                try {
                    daily(level, other, entry.getValue(), now, day);
                } catch (RuntimeException e) {
                    SmartColony.LOGGER.error("Diplomacy between {} and {} failed", colony.id(), other.id(), e);
                }
            }
        }
    }

    private void meet(ServerLevel level, long now) {
        for (Colony other : ColonyManager.all()) {
            if (other == colony || !other.data().dimension.equals(data().dimension) || data().relations.containsKey(other.id())) {
                continue;
            }
            double distance = Math.sqrt(colony.center().distSqr(other.center()));
            if (!Diplomacy.neighbors(distance)) {
                continue;
            }
            ColonyData.Relation relation = relationFor(other, now);
            relation.remember(day(level), "met");
            colony.journal(level, "met the neighbouring colony " + other.data().name + " (" + other.embassy().temper().key() + ")");
            if (colony.id().compareTo(other.id()) < 0) {
                tell(level, colony, other, Component.translatable("smartcolony.diplomacy.met", other.data().name,
                        Component.translatable("smartcolony.diplomacy.temper." + other.embassy().temper().key())));
            }
        }
    }

    private void prune(ServerLevel level) {
        data().relations.keySet().removeIf(id -> ColonyManager.byId(id) == null);
        Iterator<ColonyData.Mission> it = data().missions.iterator();
        while (it.hasNext()) {
            ColonyData.Mission mission = it.next();
            if (mission.done || !colony.colonists().containsKey(UUID.fromString(mission.member))) {
                it.remove();
                colony.markDirty();
            } else if (ColonyManager.byId(mission.target) == null && !"home".equals(mission.phase)) {
                mission.phase = "home";
            }
        }
    }

    private long day(ServerLevel level) {
        return Schedule.day(level.getDefaultClockTime());
    }

    private Diplomacy.Facts facts(Colony other, ColonyData.Relation relation, long now) {
        double distance = Math.sqrt(colony.center().distSqr(other.center()));
        ColonyData.Relation theirs = other.data().relations.get(colony.id());
        int lost = relation.lost;
        int raided = relation.raidsSuffered;
        long daysUnder = Math.max(0, (now - relation.since) / DAY);
        int weariness = relation.treaty() == Diplomacy.Treaty.WAR ? Diplomacy.weariness(lost, raided, daysUnder) : 0;
        if (theirs != null && relation.treaty() == Diplomacy.Treaty.WAR) {
            weariness += theirs.lost * 2;
        }
        return new Diplomacy.Facts(temper(), policy(), relation.standing, relation.treaty(), distance, data().radius,
                other.data().radius, guards(colony), guards(other), contested(other), daysUnder, weariness);
    }

    public int weariness(Colony other, long now) {
        ColonyData.Relation relation = relation(other);
        return relation == null ? 0 : facts(other, relation, now).weariness();
    }

    private static int guards(Colony colony) {
        int count = 0;
        for (Colonist colonist : colony.colonists().values()) {
            if (colonist.role() == Role.GUARD) {
                count++;
            }
        }
        return count;
    }

    private boolean contested(Colony other) {
        double reach = other.data().radius + 32;
        for (ColonyData.Discovery discovery : data().discoveries) {
            if (Expedition.ORE.equals(discovery.kind)) {
                double dx = discovery.pos.x() - other.center().getX();
                double dz = discovery.pos.z() - other.center().getZ();
                if (dx * dx + dz * dz <= reach * reach) {
                    return true;
                }
            }
        }
        return false;
    }

    private void daily(ServerLevel level, Colony other, ColonyData.Relation relation, long now, long day) {
        if (!level.isPositionEntityTicking(other.center()) && relation.treaty() != Diplomacy.Treaty.TRUCE) {
            return;
        }
        if (relation.treaty() == Diplomacy.Treaty.TRUCE && Diplomacy.truceOver((now - relation.since) / DAY)) {
            endTruce(level, other, now);
        }
        Diplomacy.Facts facts = facts(other, relation, now);
        Diplomacy.Drift drift = Diplomacy.drift(facts);
        reasons.put(other.id(), drift.reasons());
        shift(relation, drift.delta());
        if (colony.id().compareTo(other.id()) < 0) {
            ColonyData.Relation theirs = other.embassy().relationFor(colony, now);
            Diplomacy.Incident incident = Diplomacy.incident(Diplomacy.pairSeed(colony.id(), other.id(), day, 1), temper(),
                    other.embassy().temper(), Diplomacy.stance(relation.standing, relation.treaty()), facts.contestedOre()
                            || other.embassy().contested(colony), facts.gap());
            if (incident != null) {
                shift(relation, incident.delta());
                shift(theirs, incident.delta());
                relation.remember(day, "incident." + incident.key());
                theirs.remember(day, "incident." + incident.key());
                colony.journal(level, "incident with " + other.data().name + ": " + incident.key() + " (" + incident.delta() + ")");
                other.journal(level, "incident with " + data().name + ": " + incident.key() + " (" + incident.delta() + ")");
                tell(level, colony, other, Component.translatable("smartcolony.diplomacy.incident." + incident.key(), data().name,
                        other.data().name));
                other.markDirty();
            }
        }
        if (activeMissions(other) > 0 || !settled(level)) {
            return;
        }
        boolean festivalThere = other.festival(level);
        Diplomacy.Action action = Diplomacy.decide(facts(other, relation, now), Diplomacy.seed(colony.id(), other.id(), day, 2),
                festivalThere, Diplomacy.raidDue(now, relation.lastRaid));
        if (!SmartColonyConfig.get().colonyWars && (action == Diplomacy.Action.DECLARE_WAR || action == Diplomacy.Action.RAID)) {
            action = relation.treaty() == Diplomacy.Treaty.WAR ? Diplomacy.Action.PROPOSE_TRUCE : Diplomacy.Action.NONE;
        }
        act(level, other, relation, action, now);
    }

    private boolean settled(ServerLevel level) {
        return colony.adultCount(level) >= MISSION_ADULTS && data().buildings.size() >= MISSION_BUILDINGS;
    }

    private static void shift(ColonyData.Relation relation, int delta) {
        relation.standing = Diplomacy.clamp(relation.standing + delta);
    }

    public int activeMissions(Colony other) {
        int count = 0;
        for (ColonyData.Mission mission : data().missions) {
            if (!mission.done && other.id().equals(mission.target)) {
                count++;
            }
        }
        return count;
    }

    private void act(ServerLevel level, Colony other, ColonyData.Relation relation, Diplomacy.Action action, long now) {
        switch (action) {
            case GIFT, PROPOSE_TRADE, PROPOSE_ALLIANCE, PROPOSE_TRUCE -> sendEnvoy(level, other, action);
            case DECLARE_WAR -> declareWar(level, other, now);
            case RAID -> launchRaid(level, other, now);
            case VISIT -> launchVisit(level, other, now);
            default -> {
            }
        }
    }

    public Diplomacy.Action suggestedEnvoy(Colony other) {
        ColonyData.Relation relation = relation(other);
        if (relation == null) {
            return Diplomacy.Action.GIFT;
        }
        return switch (relation.treaty()) {
            case WAR -> Diplomacy.Action.PROPOSE_TRUCE;
            case NONE, TRUCE -> relation.standing >= Diplomacy.TRADE_AT ? Diplomacy.Action.PROPOSE_TRADE : Diplomacy.Action.GIFT;
            case TRADE -> relation.standing >= Diplomacy.ALLY_AT ? Diplomacy.Action.PROPOSE_ALLIANCE : Diplomacy.Action.GIFT;
            case ALLIANCE -> Diplomacy.Action.GIFT;
        };
    }

    public String sendEnvoy(ServerLevel level, Colony other, Diplomacy.Action action) {
        long now = level.getGameTime();
        ColonyData.Relation relation = relationFor(other, now);
        Map<String, Integer> gift = gift(level, action == Diplomacy.Action.GIFT ? GIFT_BUDGET : GIFT_BUDGET / 3);
        Villager envoy = level.isPositionEntityTicking(other.center()) ? pick(level, ENVOY_ROLES) : null;
        relation.lastEnvoy = now;
        if (envoy == null) {
            ColonyData.Mission letter = new ColonyData.Mission();
            letter.kind = ENVOY;
            letter.target = other.id();
            letter.proposal = action.key();
            letter.goods.putAll(gift);
            colony.journal(level, "sent a messenger to " + other.data().name + " (" + action.key() + ")");
            deliverEnvoy(level, letter, null, other);
            returnGoods(level, letter);
            return "messenger";
        }
        ColonyData.Mission mission = start(level, envoy, ENVOY, other, now);
        mission.proposal = action.key();
        mission.goods.putAll(gift);
        colony.journal(level, "an envoy left for " + other.data().name + " (" + action.key() + (gift.isEmpty() ? "" : ", gifts " + gift) + ")");
        tell(level, colony, other, Component.translatable("smartcolony.diplomacy.envoy_sent", data().name, other.data().name,
                Component.translatable("smartcolony.diplomacy.action." + action.key())));
        return "envoy";
    }

    public boolean sendCaravan(ServerLevel level, Colony other, Map<String, Integer> goods) {
        if (!SmartColonyConfig.get().diplomacy || !level.isPositionEntityTicking(other.center())) {
            return false;
        }
        Villager carrier = pick(level, CARRIER_ROLES);
        if (carrier == null) {
            return false;
        }
        ColonyData.Mission mission = start(level, carrier, CARAVAN, other, level.getGameTime());
        mission.goods.putAll(goods);
        return true;
    }

    private void launchVisit(ServerLevel level, Colony other, long now) {
        if (!level.isPositionEntityTicking(other.center())) {
            return;
        }
        int sent = 0;
        for (int i = 0; i < 2; i++) {
            Villager guest = pick(level, List.of(Role.IDLE, Role.FARMER, Role.LUMBERJACK, Role.COOK, Role.FISHERMAN, Role.HERDER,
                    Role.SCRIBE, Role.TEACHER, Role.BEEKEEPER));
            if (guest == null) {
                break;
            }
            start(level, guest, VISIT, other, now);
            sent++;
        }
        if (sent > 0) {
            colony.journal(level, sent + " villagers went to the festival in " + other.data().name);
            tell(level, colony, other, Component.translatable("smartcolony.diplomacy.visit_sent", data().name, other.data().name, sent));
        }
    }

    private void checkAid(ServerLevel level, long now) {
        if (colony.defense().alarmed(level) || colony.defense().raidActive()) {
            return;
        }
        for (Map.Entry<String, ColonyData.Relation> entry : data().relations.entrySet()) {
            ColonyData.Relation relation = entry.getValue();
            Colony ally = ColonyManager.byId(entry.getKey());
            if (ally == null || relation.treaty() != Diplomacy.Treaty.ALLIANCE || now - relation.lastAid < AID_COOLDOWN
                    || !level.isPositionEntityTicking(ally.center()) || activeMissions(ally) > 0) {
                continue;
            }
            double distance = Math.sqrt(colony.center().distSqr(ally.center()));
            boolean pressed = ally.defense().raidActive() || ally.defense().alarmed(level) && ally.defense().enemies().size() >= 3;
            if (distance > Diplomacy.MISSION_RANGE || !pressed) {
                continue;
            }
            int available = guards(colony);
            int send = Math.min(2, available - 1);
            int sent = 0;
            for (int i = 0; i < send; i++) {
                Villager guard = pickGuard(level);
                if (guard == null) {
                    break;
                }
                ColonyData.Mission mission = start(level, guard, AID, ally, now);
                mission.until = now + AID_STAY;
                sent++;
            }
            if (sent > 0) {
                relation.lastAid = now;
                colony.journal(level, sent + " guards marched to help " + ally.data().name);
                tell(level, colony, ally, Component.translatable("smartcolony.diplomacy.aid_sent", data().name, ally.data().name, sent));
            }
        }
    }

    public int orderRaid(ServerLevel level, Colony other) {
        if (!atWar(colony, other) || activeMissions(other) > 0) {
            return 0;
        }
        launchRaid(level, other, level.getGameTime());
        return activeMissions(other);
    }

    private void launchRaid(ServerLevel level, Colony other, long now) {
        if (!SmartColonyConfig.get().colonyWars || !level.isPositionEntityTicking(other.center())
                || Math.sqrt(colony.center().distSqr(other.center())) > Diplomacy.MISSION_RANGE) {
            return;
        }
        int party = Diplomacy.raidParty(guards(colony));
        List<Villager> raiders = new ArrayList<>();
        for (int i = 0; i < party; i++) {
            Villager guard = pickGuard(level);
            if (guard == null) {
                break;
            }
            raiders.add(guard);
            start(level, guard, RAID, other, now);
        }
        if (raiders.size() < Math.min(party, Diplomacy.MIN_RAIDERS)) {
            for (Villager raider : raiders) {
                ColonyData.Mission mission = missionOf(raider.getUUID());
                if (mission != null) {
                    mission.phase = "home";
                }
            }
            return;
        }
        ColonyData.Relation relation = relationFor(other, now);
        relation.lastRaid = now;
        relation.raidsMade++;
        relation.remember(day(level), "raid");
        colony.data().bump("raids_launched", 1);
        colony.journal(level, raiders.size() + " guards set out to raid " + other.data().name);
        tell(level, colony, other, Component.translatable("smartcolony.diplomacy.raid_sent", data().name, other.data().name, raiders.size()));
        colony.markDirty();
    }

    private ColonyData.Mission start(ServerLevel level, Villager villager, String kind, Colony other, long now) {
        ColonyData.Mission mission = new ColonyData.Mission();
        mission.member = villager.getUUID().toString();
        mission.kind = kind;
        mission.target = other.id();
        mission.phase = "out";
        mission.started = now;
        data().missions.add(mission);
        data().bump("missions_" + kind, 1);
        colony.markDirty();
        return mission;
    }

    private Villager pick(ServerLevel level, List<Role> roles) {
        Villager best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Map.Entry<UUID, Colonist> entry : colony.colonists().entrySet()) {
            int rank = roles.indexOf(entry.getValue().role());
            if (rank < 0 || rank >= bestRank || onMission(entry.getKey())) {
                continue;
            }
            if (level.getEntity(entry.getKey()) instanceof Villager villager && ready(villager)) {
                best = villager;
                bestRank = rank;
            }
        }
        return best;
    }

    private Villager pickGuard(ServerLevel level) {
        Villager best = null;
        float bestHealth = 0.8F;
        for (Map.Entry<UUID, Colonist> entry : colony.colonists().entrySet()) {
            if (entry.getValue().role() != Role.GUARD || onMission(entry.getKey())
                    || !(entry.getValue().job() instanceof GuardJob guard)) {
                continue;
            }
            if (level.getEntity(entry.getKey()) instanceof Villager villager && ready(villager) && guard.armedFor(villager)) {
                float health = villager.getHealth() / villager.getMaxHealth();
                if (health >= bestHealth) {
                    best = villager;
                    bestHealth = health;
                }
            }
        }
        return best;
    }

    private boolean ready(Villager villager) {
        return villager.isAlive() && !villager.isBaby() && !villager.isSleeping() && !villager.isTrading()
                && colony.contains(villager.blockPosition());
    }

    private Map<String, Integer> gift(ServerLevel level, long budget) {
        Map<String, Integer> gift = new LinkedHashMap<>();
        if (budget <= 0) {
            return gift;
        }
        Stock stock = colony.warehouse().stock(level);
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(stock.view().entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<String, Integer> e) -> (long) PriceTable.base(e.getKey())
                * Math.max(0, e.getValue() - Market.keepLevel(e.getKey()))).reversed());
        long left = budget;
        for (Map.Entry<String, Integer> entry : entries) {
            String id = entry.getKey();
            int price = Math.max(1, PriceTable.base(id));
            int surplus = entry.getValue() - Market.keepLevel(id);
            if (surplus <= 0 || ItemClassifier.classify(id) == Category.ARSENAL || price > left) {
                continue;
            }
            int amount = (int) Math.min(Math.min(surplus, 64), left / price);
            if (amount <= 0) {
                continue;
            }
            int taken = colony.warehouse().take(level, id, amount);
            if (taken > 0) {
                gift.merge(id, taken, Integer::sum);
                left -= (long) taken * price;
            }
            if (gift.size() >= 2 || left < budget / 5) {
                break;
            }
        }
        return gift;
    }

    public BlockPos destination(ColonyData.Mission mission, Colony target) {
        if (RAID.equals(mission.kind)) {
            return target.depot();
        }
        if (ENVOY.equals(mission.kind)) {
            ColonyData.Building embassy = target.buildingOfKind("embassy");
            if (embassy != null && embassy.door != null) {
                return WorldUtil.bp(embassy.door);
            }
        }
        if (CARAVAN.equals(mission.kind)) {
            ColonyData.Building post = target.buildingOfKind("trading_post");
            if (post != null && post.door != null) {
                return WorldUtil.bp(post.door);
            }
        }
        return target.groundCenter();
    }

    public void arrived(ServerLevel level, ColonyData.Mission mission, Villager villager, Colony target) {
        long now = level.getGameTime();
        switch (mission.kind) {
            case ENVOY -> {
                deliverEnvoy(level, mission, villager, target);
                mission.phase = "stay";
                mission.until = now + ENVOY_STAY;
            }
            case CARAVAN -> {
                int delivered = unload(level, mission, target);
                target.embassy().caravanArrived(level, colony, delivered);
                mission.phase = "stay";
                mission.until = now + CARAVAN_STAY;
                level.playSound(null, villager.blockPosition(), SoundEvents.CHEST_CLOSE, SoundSource.NEUTRAL, 0.8F, 1.0F);
            }
            case VISIT -> {
                ColonyData.Relation theirs = target.embassy().relationFor(colony, now);
                shift(theirs, Diplomacy.VISIT);
                theirs.visits++;
                shift(relationFor(target, now), 1);
                theirs.remember(day(level), "visit");
                mission.phase = "stay";
                mission.until = now + VISIT_STAY;
                target.journal(level, "guests from " + data().name + " came to the festival");
                target.markDirty();
            }
            case AID -> {
                mission.phase = "stay";
                if (mission.until <= now) {
                    mission.until = now + AID_STAY;
                }
                target.journal(level, "guards from " + data().name + " arrived to help");
                tell(level, target, colony, Component.translatable("smartcolony.diplomacy.aid_arrived", data().name));
            }
            default -> mission.phase = "home";
        }
        colony.markDirty();
    }

    private void deliverEnvoy(ServerLevel level, ColonyData.Mission mission, Villager envoy, Colony target) {
        long now = level.getGameTime();
        long day = day(level);
        Diplomacy.Action action = Diplomacy.Action.byKey(mission.proposal);
        ColonyData.Relation mine = relationFor(target, now);
        ColonyData.Relation theirs = target.embassy().relationFor(colony, now);
        long value = PriceTable.total(mission.goods);
        int delivered = unload(level, mission, target);
        if (delivered > 0) {
            int bonus = Diplomacy.giftBonus(value, target.embassy().temper());
            shift(theirs, bonus);
            mine.gifts++;
            theirs.remember(day, "gift");
            target.journal(level, "received gifts from " + data().name + ": " + delivered + " items (+" + bonus + ")");
        } else {
            shift(theirs, 1);
        }
        boolean accepted = false;
        if (action.proposal()) {
            accepted = Diplomacy.accepts(action, target.embassy().temper(), target.embassy().policy(), theirs.standing, theirs.treaty(),
                    target.embassy().weariness(colony, now));
            if (accepted) {
                Diplomacy.Treaty treaty = switch (action) {
                    case PROPOSE_TRADE -> Diplomacy.Treaty.TRADE;
                    case PROPOSE_ALLIANCE -> Diplomacy.Treaty.ALLIANCE;
                    default -> Diplomacy.Treaty.TRUCE;
                };
                setTreaty(level, target, treaty, now);
            } else {
                shift(mine, Diplomacy.REJECTED);
                mine.remember(day, "rejected." + action.key());
                colony.journal(level, target.data().name + " rejected our " + action.key());
                tell(level, colony, target, Component.translatable("smartcolony.diplomacy.rejected", target.data().name,
                        Component.translatable("smartcolony.diplomacy.action." + action.key())));
            }
        } else if (delivered > 0) {
            tell(level, target, colony, Component.translatable("smartcolony.diplomacy.gift_received", data().name, delivered));
        }
        if (envoy != null) {
            ParticleOptions particle = !action.proposal() || accepted ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.ANGRY_VILLAGER;
            level.sendParticles(particle, envoy.getX(), envoy.getY() + 2.0, envoy.getZ(), 10, 0.5, 0.4, 0.5, 0.0);
            level.playSound(null, envoy.blockPosition(), !action.proposal() || accepted ? SoundEvents.VILLAGER_YES : SoundEvents.VILLAGER_NO,
                    SoundSource.NEUTRAL, 1.0F, 1.0F);
        }
        colony.markDirty();
        target.markDirty();
    }

    private int unload(ServerLevel level, ColonyData.Mission mission, Colony target) {
        int delivered = 0;
        Iterator<Map.Entry<String, Integer>> goods = mission.goods.entrySet().iterator();
        while (goods.hasNext()) {
            Map.Entry<String, Integer> entry = goods.next();
            int left = entry.getValue();
            while (left > 0) {
                ItemStack stack = ItemIds.stack(entry.getKey(), Math.min(left, 64));
                if (stack.isEmpty()) {
                    left = 0;
                    break;
                }
                int size = stack.getCount();
                ItemStack rest = target.warehouse().insert(level, stack);
                int moved = size - rest.getCount();
                left -= moved;
                delivered += moved;
                if (!rest.isEmpty()) {
                    break;
                }
            }
            if (left <= 0) {
                goods.remove();
            } else {
                entry.setValue(left);
            }
        }
        return delivered;
    }

    void caravanArrived(ServerLevel level, Colony from, int delivered) {
        long now = level.getGameTime();
        ColonyData.Relation mine = relationFor(from, now);
        ColonyData.Relation theirs = from.embassy().relationFor(colony, now);
        shift(mine, Diplomacy.CARAVAN_RECEIVED);
        shift(theirs, Diplomacy.CARAVAN_SENT);
        mine.trades++;
        theirs.trades++;
        mine.remember(day(level), "caravan");
        if (delivered > 0) {
            colony.journal(level, "caravan from " + from.data().name + " unloaded " + delivered + " items");
            colony.notify(level, Component.translatable("smartcolony.chat.caravan_arrived", from.data().name, delivered));
            colony.data().bump("caravans_received", 1);
        }
        colony.markDirty();
        from.markDirty();
    }

    public void raidEntered(ServerLevel level, Colony target, long now) {
        ColonyData.Relation theirs = target.embassy().relationFor(colony, now);
        if (now - theirs.lastRaided < RAID_ALERT) {
            return;
        }
        theirs.lastRaided = now;
        theirs.raidsSuffered++;
        shift(theirs, Diplomacy.RAIDED);
        theirs.remember(day(level), "raided");
        target.data().bump("raids_suffered", 1);
        target.journal(level, "raiders from " + data().name + " broke into the village");
        tell(level, target, colony, Component.translatable("smartcolony.diplomacy.raid_entered", data().name));
        target.markDirty();
    }

    public void plunder(ServerLevel level, ColonyData.Mission mission, Villager raider, Colony target) {
        Stock stock = target.warehouse().stock(level);
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(stock.view().entrySet());
        entries.removeIf(e -> e.getValue() <= 0 || ItemClassifier.classify(e.getKey()) == Category.ARSENAL);
        entries.sort(Comparator.comparingLong((Map.Entry<String, Integer> e) -> (long) PriceTable.base(e.getKey()) * e.getValue()).reversed());
        long left = LOOT_BUDGET;
        int taken = 0;
        for (Map.Entry<String, Integer> entry : entries) {
            if (mission.goods.size() >= 3 || left <= 0) {
                break;
            }
            int price = Math.max(1, PriceTable.base(entry.getKey()));
            int amount = (int) Math.min(Math.min(32, Math.max(1, entry.getValue() / 4)), Math.max(1, left / price));
            int got = target.warehouse().take(level, entry.getKey(), amount);
            if (got > 0) {
                mission.goods.merge(entry.getKey(), got, Integer::sum);
                left -= (long) got * price;
                taken += got;
            }
        }
        mission.phase = "home";
        level.playSound(null, raider.blockPosition(), SoundEvents.CHEST_OPEN, SoundSource.HOSTILE, 1.0F, 0.8F);
        level.sendParticles(ParticleTypes.SMOKE, raider.getX(), raider.getY() + 1.0, raider.getZ(), 12, 0.4, 0.5, 0.4, 0.02);
        if (taken > 0) {
            target.data().bump("items_plundered", taken);
            target.journal(level, "raiders from " + data().name + " carried off " + taken + " items " + mission.goods);
            tell(level, target, colony, Component.translatable("smartcolony.diplomacy.plundered", data().name, taken));
        }
        colony.markDirty();
        target.markDirty();
    }

    public void returned(ServerLevel level, ColonyData.Mission mission, Villager villager) {
        long now = level.getGameTime();
        Colony target = ColonyManager.byId(mission.target);
        if (AID.equals(mission.kind) && target != null) {
            ColonyData.Relation theirs = target.embassy().relationFor(colony, now);
            shift(theirs, Diplomacy.AID);
            theirs.aid++;
            theirs.remember(day(level), "aid");
            target.markDirty();
        }
        if (!mission.goods.isEmpty()) {
            long value = PriceTable.total(mission.goods);
            int items = mission.goods.values().stream().mapToInt(Integer::intValue).sum();
            String what = mission.goods.toString();
            returnGoods(level, mission);
            if (RAID.equals(mission.kind)) {
                colony.data().bump("items_looted", items);
                colony.journal(level, "raiders came home with " + what);
                colony.notify(level, Component.translatable("smartcolony.diplomacy.loot_home", items, value / 1000));
            }
        }
        mission.done = true;
        colony.markDirty();
    }

    private void returnGoods(ServerLevel level, ColonyData.Mission mission) {
        for (Map.Entry<String, Integer> entry : mission.goods.entrySet()) {
            int left = entry.getValue();
            while (left > 0) {
                ItemStack stack = ItemIds.stack(entry.getKey(), Math.min(left, 64));
                if (stack.isEmpty()) {
                    break;
                }
                left -= stack.getCount();
                ItemStack rest = colony.warehouse().insert(level, stack);
                if (!rest.isEmpty()) {
                    net.minecraft.world.entity.item.ItemEntity drop = new net.minecraft.world.entity.item.ItemEntity(level,
                            colony.depot().getX() + 0.5, colony.depot().getY() + 1.0, colony.depot().getZ() + 0.5, rest);
                    level.addFreshEntity(drop);
                }
            }
        }
        mission.goods.clear();
    }

    public void abandoned(ServerLevel level, ColonyData.Mission mission, String why) {
        if (!"home".equals(mission.phase)) {
            colony.journal(level, mission.kind + " mission to " + nameOf(mission.target) + " turned back: " + why);
        }
        mission.phase = "home";
        colony.markDirty();
    }

    private static String nameOf(String id) {
        Colony other = ColonyManager.byId(id);
        return other == null ? id : other.data().name;
    }

    void memberLost(ServerLevel level, Villager villager, DamageSource source) {
        ColonyData.Mission mission = missionOf(villager.getUUID());
        if (mission != null) {
            for (Map.Entry<String, Integer> entry : mission.goods.entrySet()) {
                int left = entry.getValue();
                while (left > 0) {
                    ItemStack stack = ItemIds.stack(entry.getKey(), Math.min(left, 64));
                    if (stack.isEmpty()) {
                        break;
                    }
                    left -= stack.getCount();
                    villager.spawnAtLocation(level, stack);
                }
            }
            mission.goods.clear();
            mission.done = true;
            colony.journal(level, "a " + mission.kind + " mission member died on the way to " + nameOf(mission.target));
        }
        if (source != null && source.getEntity() instanceof Villager killer) {
            Colony enemy = ColonyManager.colonyOf(killer);
            if (enemy != null && enemy != colony) {
                long now = level.getGameTime();
                ColonyData.Relation mine = relationFor(enemy, now);
                ColonyData.Relation theirs = enemy.embassy().relationFor(colony, now);
                mine.lost++;
                theirs.killed++;
                shift(mine, Diplomacy.KILLED);
                mine.remember(day(level), "lost");
                theirs.remember(day(level), "killed");
                colony.journal(level, "a villager was killed by a guard of " + enemy.data().name);
                tell(level, colony, enemy, Component.translatable("smartcolony.diplomacy.killed", data().name, enemy.data().name));
                enemy.markDirty();
            }
        }
        colony.markDirty();
    }

    void forget(UUID uuid) {
        ColonyData.Mission mission = missionOf(uuid);
        if (mission != null) {
            mission.done = true;
            colony.markDirty();
        }
    }

    public void declareWar(ServerLevel level, Colony other, long now) {
        if (!SmartColonyConfig.get().colonyWars) {
            return;
        }
        ColonyData.Relation mine = relationFor(other, now);
        ColonyData.Relation theirs = other.embassy().relationFor(colony, now);
        if (mine.treaty() == Diplomacy.Treaty.WAR) {
            return;
        }
        shift(theirs, Diplomacy.WAR_DECLARED);
        setTreaty(level, other, Diplomacy.Treaty.WAR, now);
        mine.lastRaid = now - Diplomacy.RAID_INTERVAL / 2;
        for (Colony side : List.of(colony, other)) {
            if (level.getBlockState(side.center()).getBlock() instanceof net.minecraft.world.level.block.BellBlock bell) {
                bell.attemptToRing(level, side.center(), net.minecraft.core.Direction.NORTH);
            }
        }
    }

    private void endTruce(ServerLevel level, Colony other, long now) {
        ColonyData.Relation mine = relationFor(other, now);
        ColonyData.Relation theirs = other.embassy().relationFor(colony, now);
        mine.standing = Diplomacy.afterTruce(mine.standing);
        theirs.standing = Diplomacy.afterTruce(theirs.standing);
        setTreaty(level, other, Diplomacy.Treaty.NONE, now);
    }

    private void setTreaty(ServerLevel level, Colony other, Diplomacy.Treaty treaty, long now) {
        ColonyData.Relation mine = relationFor(other, now);
        ColonyData.Relation theirs = other.embassy().relationFor(colony, now);
        Diplomacy.Treaty before = mine.treaty();
        if (before == treaty) {
            return;
        }
        long day = day(level);
        for (ColonyData.Relation relation : List.of(mine, theirs)) {
            relation.treaty = treaty.key();
            relation.since = now;
            relation.remember(day, "treaty." + treaty.key());
            if (treaty == Diplomacy.Treaty.TRUCE) {
                relation.standing = Math.max(relation.standing, Diplomacy.PEACE_FLOOR - 15);
            }
        }
        if (treaty == Diplomacy.Treaty.TRUCE || treaty == Diplomacy.Treaty.NONE && before == Diplomacy.Treaty.WAR) {
            for (ColonyData.Mission mission : data().missions) {
                if (RAID.equals(mission.kind) && other.id().equals(mission.target)) {
                    mission.phase = "home";
                }
            }
            for (ColonyData.Mission mission : other.data().missions) {
                if (RAID.equals(mission.kind) && colony.id().equals(mission.target)) {
                    mission.phase = "home";
                }
            }
        }
        String line = "treaty with %s: " + before.key() + " -> " + treaty.key();
        colony.journal(level, String.format(line, other.data().name));
        other.journal(level, String.format(line, data().name));
        String key = treaty == Diplomacy.Treaty.NONE ? "smartcolony.diplomacy.treaty.ended." + before.key()
                : "smartcolony.diplomacy.treaty." + treaty.key();
        tell(level, colony, other, Component.translatable(key, data().name, other.data().name));
        colony.markDirty();
        other.markDirty();
    }

    static void tell(ServerLevel level, Colony speaker, Colony other, Component message) {
        if (!SmartColonyConfig.get().notifyPlayers) {
            return;
        }
        Component line = Component.translatable("smartcolony.chat.prefix", speaker.data().name).append(" ").append(message);
        for (ServerPlayer player : level.players()) {
            BlockPos pos = player.blockPosition();
            if (pos.distSqr(speaker.center()) < TALK_RADIUS * TALK_RADIUS || other != null && pos.distSqr(other.center()) < TALK_RADIUS * TALK_RADIUS) {
                player.sendSystemMessage(line);
            }
        }
    }

    public String describe(ColonyData.Mission mission) {
        return mission.kind + " -> " + nameOf(mission.target) + ": " + mission.phase;
    }

    public boolean stillPressed(ServerLevel level, Colony ally) {
        return ally.defense().raidActive() || ally.defense().alarmed(level);
    }
}
