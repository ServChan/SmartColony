package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Diplomacy {
    public static final int MIN = -100;
    public static final int MAX = 100;
    public static final int HOSTILE_AT = -25;
    public static final int FRIENDLY_AT = 25;
    public static final int TRADE_AT = 20;
    public static final int ALLY_AT = 60;
    public static final int WAR_AT = -60;
    public static final int RAGE_AT = -85;
    public static final int NEIGHBOR_RANGE = 640;
    public static final int MISSION_RANGE = 480;
    public static final int CLOSE_GAP = 48;
    public static final int NEAR_GAP = 120;
    public static final int TRUCE_DAYS = 3;
    public static final int PEACE_FLOOR = -20;
    public static final int WEARY_AT = 40;
    public static final int MAX_RAIDERS = 4;
    public static final int MIN_RAIDERS = 2;
    public static final long RAID_INTERVAL = 18000L;
    public static final int CARAVAN_RECEIVED = 3;
    public static final int CARAVAN_SENT = 1;
    public static final int VISIT = 2;
    public static final int AID = 15;
    public static final int RAIDED = -22;
    public static final int KILLED = -12;
    public static final int REJECTED = -3;
    public static final int WAR_DECLARED = -15;
    public static final int MAX_GIFT_BONUS = 12;
    public static final int GIFT_COURTESY = 2;

    public enum Treaty {
        NONE,
        TRADE,
        ALLIANCE,
        WAR,
        TRUCE;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Treaty byKey(String key) {
            for (Treaty treaty : values()) {
                if (treaty.key().equals(key)) {
                    return treaty;
                }
            }
            return NONE;
        }
    }

    public enum Temper {
        PEACEFUL,
        MERCHANT,
        PROUD;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Temper byKey(String key) {
            for (Temper temper : values()) {
                if (temper.key().equals(key)) {
                    return temper;
                }
            }
            return null;
        }
    }

    public enum Policy {
        AUTO,
        PEACEFUL,
        WARLIKE;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Policy byKey(String key) {
            for (Policy policy : values()) {
                if (policy.key().equals(key)) {
                    return policy;
                }
            }
            return AUTO;
        }
    }

    public enum Stance {
        WAR,
        HOSTILE,
        NEUTRAL,
        FRIENDLY,
        ALLIED;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Action {
        NONE,
        GIFT,
        PROPOSE_TRADE,
        PROPOSE_ALLIANCE,
        PROPOSE_TRUCE,
        DECLARE_WAR,
        RAID,
        VISIT,
        AID;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Action byKey(String key) {
            for (Action action : values()) {
                if (action.key().equals(key)) {
                    return action;
                }
            }
            return NONE;
        }

        public boolean proposal() {
            return this == PROPOSE_TRADE || this == PROPOSE_ALLIANCE || this == PROPOSE_TRUCE;
        }
    }

    public record Facts(Temper temper, Policy policy, int standing, Treaty treaty, double distance, int radius, int theirRadius,
                        int guards, int theirGuards, boolean contestedOre, long daysUnderTreaty, int weariness) {
        public int gap() {
            return (int) Math.max(0, Math.round(distance - radius - theirRadius));
        }

        public Temper effectiveTemper() {
            return switch (policy) {
                case PEACEFUL -> Temper.PEACEFUL;
                case WARLIKE -> Temper.PROUD;
                case AUTO -> temper;
            };
        }
    }

    public record Reason(String key, int delta) {
    }

    public record Drift(int delta, List<Reason> reasons) {
    }

    public record Incident(String key, int delta) {
    }

    private Diplomacy() {
    }

    public static int clamp(int value) {
        return Math.max(MIN, Math.min(MAX, value));
    }

    public static Temper temperOf(String colonyId) {
        int roll = Math.floorMod(mix(colonyId == null ? 0 : colonyId.hashCode()), 100);
        if (roll < 40) {
            return Temper.PEACEFUL;
        }
        return roll < 75 ? Temper.MERCHANT : Temper.PROUD;
    }

    public static Stance stance(int standing, Treaty treaty) {
        if (treaty == Treaty.WAR) {
            return Stance.WAR;
        }
        if (treaty == Treaty.ALLIANCE) {
            return Stance.ALLIED;
        }
        if (standing <= HOSTILE_AT) {
            return Stance.HOSTILE;
        }
        return standing >= FRIENDLY_AT ? Stance.FRIENDLY : Stance.NEUTRAL;
    }

    public static boolean neighbors(double distance) {
        return distance <= NEIGHBOR_RANGE;
    }

    public static boolean tradeAllowed(int standing, Treaty treaty) {
        return treaty != Treaty.WAR && standing > HOSTILE_AT;
    }

    public static Drift drift(Facts facts) {
        List<Reason> reasons = new ArrayList<>();
        Temper temper = facts.effectiveTemper();
        int gap = facts.gap();
        int border = gap < CLOSE_GAP ? -2 : gap < NEAR_GAP ? -1 : 0;
        if (border != 0) {
            if (temper == Temper.PROUD) {
                border *= 2;
            } else if (temper == Temper.PEACEFUL) {
                border = border / 2;
            }
            if (facts.treaty() == Treaty.ALLIANCE) {
                border = 0;
            }
            add(reasons, "border", border);
        }
        if (facts.contestedOre() && facts.treaty() != Treaty.ALLIANCE) {
            add(reasons, "ore", temper == Temper.PEACEFUL ? 0 : -1);
        }
        if (temper == Temper.PROUD && facts.guards() >= 3 && facts.guards() >= facts.theirGuards() * 2
                && facts.treaty() != Treaty.ALLIANCE) {
            add(reasons, "contempt", -1);
        }
        switch (facts.treaty()) {
            case TRADE -> add(reasons, "trade_pact", temper == Temper.MERCHANT ? 2 : 1);
            case ALLIANCE -> add(reasons, "alliance", 1);
            case WAR -> add(reasons, "war", -1);
            default -> {
            }
        }
        if (facts.treaty() != Treaty.WAR && facts.standing() <= -10) {
            add(reasons, "grudge_fades", 1);
        }
        if (facts.treaty() == Treaty.NONE && facts.standing() >= 40) {
            add(reasons, "goodwill_fades", -1);
        }
        int delta = 0;
        for (Reason reason : reasons) {
            delta += reason.delta();
        }
        return new Drift(delta, reasons);
    }

    private static void add(List<Reason> reasons, String key, int delta) {
        if (delta != 0) {
            reasons.add(new Reason(key, delta));
        }
    }

    public static Incident incident(long seed, Temper a, Temper b, Stance stance, boolean contestedOre, int gap) {
        if (gap > NEAR_GAP * 2 && stance != Stance.WAR) {
            return null;
        }
        java.util.Random random = new java.util.Random(seed);
        if (random.nextInt(100) >= (stance == Stance.WAR ? 35 : 22)) {
            return null;
        }
        if (stance == Stance.WAR) {
            return random.nextBoolean() ? new Incident("skirmish", -4) : new Incident("taunts", -3);
        }
        int proud = (a == Temper.PROUD ? 1 : 0) + (b == Temper.PROUD ? 1 : 0);
        int peaceful = (a == Temper.PEACEFUL ? 1 : 0) + (b == Temper.PEACEFUL ? 1 : 0);
        int badWeight = 4 + proud * 3 + (stance == Stance.HOSTILE ? 3 : 0) - peaceful;
        int goodWeight = 4 + peaceful * 3 + (stance == Stance.FRIENDLY || stance == Stance.ALLIED ? 3 : 0) - proud;
        boolean bad = random.nextInt(Math.max(1, badWeight) + Math.max(1, goodWeight)) < Math.max(1, badWeight);
        if (bad) {
            List<Incident> pool = new ArrayList<>(List.of(new Incident("border_quarrel", -6), new Incident("stray_herd", -5),
                    new Incident("insult", -4), new Incident("felled_trees", -5)));
            if (contestedOre) {
                pool.add(new Incident("poached_ore", -7));
            }
            return pool.get(random.nextInt(pool.size()));
        }
        List<Incident> pool = List.of(new Incident("wedding", 8), new Incident("lost_traveller", 5), new Incident("shared_harvest", 4),
                new Incident("fair_dealing", 3), new Incident("song_night", 4));
        return pool.get(random.nextInt(pool.size()));
    }

    public static boolean canDeclareWar(Facts facts) {
        Temper temper = facts.effectiveTemper();
        if (facts.policy() == Policy.PEACEFUL || facts.treaty() == Treaty.WAR || facts.treaty() == Treaty.TRUCE
                || facts.treaty() == Treaty.ALLIANCE || facts.guards() < MIN_RAIDERS) {
            return false;
        }
        int threshold = facts.policy() == Policy.WARLIKE ? -30 : temper == Temper.PROUD ? WAR_AT : RAGE_AT;
        return facts.standing() <= threshold;
    }

    public static int raidParty(int guards) {
        if (guards <= MIN_RAIDERS) {
            return 0;
        }
        return Math.min(MAX_RAIDERS, Math.max(1, guards - Math.max(1, guards / 3)));
    }

    public static boolean raidDue(long now, long lastRaid) {
        return now - lastRaid >= RAID_INTERVAL;
    }

    public static Action decide(Facts facts, long seed, boolean festivalThere, boolean raidDue) {
        java.util.Random random = new java.util.Random(seed);
        Temper temper = facts.effectiveTemper();
        Stance stance = stance(facts.standing(), facts.treaty());
        switch (stance) {
            case WAR -> {
                if (facts.weariness() >= WEARY_AT || facts.policy() == Policy.PEACEFUL) {
                    return Action.PROPOSE_TRUCE;
                }
                if (raidDue && raidParty(facts.guards()) > 0 && random.nextInt(100) < (temper == Temper.PROUD ? 75 : 50)) {
                    return Action.RAID;
                }
                return facts.daysUnderTreaty() >= 4 && temper != Temper.PROUD ? Action.PROPOSE_TRUCE : Action.NONE;
            }
            case HOSTILE -> {
                if (canDeclareWar(facts)) {
                    return Action.DECLARE_WAR;
                }
                if (facts.treaty() == Treaty.TRUCE) {
                    return random.nextInt(100) < 25 ? Action.GIFT : Action.NONE;
                }
                return temper != Temper.PROUD && random.nextInt(100) < 40 ? Action.GIFT : Action.NONE;
            }
            case NEUTRAL -> {
                if (facts.standing() >= TRADE_AT && facts.treaty() == Treaty.NONE) {
                    return Action.PROPOSE_TRADE;
                }
                if (festivalThere && random.nextInt(100) < 50) {
                    return Action.VISIT;
                }
                int chance = temper == Temper.MERCHANT ? 45 : temper == Temper.PEACEFUL ? 35 : 15;
                return random.nextInt(100) < chance ? Action.GIFT : Action.NONE;
            }
            case FRIENDLY -> {
                if (facts.treaty() == Treaty.NONE || facts.treaty() == Treaty.TRUCE) {
                    return Action.PROPOSE_TRADE;
                }
                if (facts.treaty() == Treaty.TRADE && facts.standing() >= ALLY_AT && temper != Temper.MERCHANT
                        || facts.treaty() == Treaty.TRADE && facts.standing() >= ALLY_AT + 15) {
                    return Action.PROPOSE_ALLIANCE;
                }
                if (festivalThere) {
                    return Action.VISIT;
                }
                return random.nextInt(100) < 25 ? Action.GIFT : Action.NONE;
            }
            case ALLIED -> {
                if (festivalThere) {
                    return Action.VISIT;
                }
                return random.nextInt(100) < 20 ? Action.GIFT : Action.NONE;
            }
        }
        return Action.NONE;
    }

    public static boolean accepts(Action proposal, Temper temper, Policy policy, int standing, Treaty treaty, int weariness) {
        Temper effective = policy == Policy.PEACEFUL ? Temper.PEACEFUL : policy == Policy.WARLIKE ? Temper.PROUD : temper;
        return switch (proposal) {
            case PROPOSE_TRADE -> treaty != Treaty.WAR && treaty != Treaty.ALLIANCE
                    && standing >= (effective == Temper.MERCHANT ? TRADE_AT - 10 : effective == Temper.PROUD ? TRADE_AT + 10 : TRADE_AT);
            case PROPOSE_ALLIANCE -> treaty == Treaty.TRADE && policy != Policy.WARLIKE
                    && standing >= (effective == Temper.PROUD ? ALLY_AT + 15 : ALLY_AT);
            case PROPOSE_TRUCE -> treaty == Treaty.WAR && (policy == Policy.PEACEFUL || effective == Temper.PEACEFUL
                    || weariness >= WEARY_AT / 2 || standing > RAGE_AT && effective != Temper.PROUD || weariness >= WEARY_AT);
            default -> false;
        };
    }

    public static int giftBonus(long valueMillis, Temper receiver) {
        if (valueMillis <= 0) {
            return 0;
        }
        int bonus = GIFT_COURTESY + (int) Math.min(MAX_GIFT_BONUS - GIFT_COURTESY, valueMillis / 150);
        if (receiver == Temper.MERCHANT) {
            bonus += 1;
        } else if (receiver == Temper.PROUD) {
            bonus = Math.max(1, bonus - 2);
        }
        return Math.min(MAX_GIFT_BONUS, bonus);
    }

    public static int weariness(int lost, int raidsSuffered, long daysAtWar) {
        return lost * 10 + raidsSuffered * 6 + (int) Math.min(30, daysAtWar * 4);
    }

    public static boolean truceOver(long daysUnderTreaty) {
        return daysUnderTreaty >= TRUCE_DAYS;
    }

    public static int afterTruce(int standing) {
        return Math.max(standing, PEACE_FLOOR);
    }

    public static long pairSeed(String a, String b, long day, int salt) {
        long left = a.compareTo(b) <= 0 ? a.hashCode() : b.hashCode();
        long right = a.compareTo(b) <= 0 ? b.hashCode() : a.hashCode();
        return mix((int) (left * 31 + right)) * 1_000_003L + day * 7919L + salt;
    }

    public static long seed(String self, String other, long day, int salt) {
        return mix((self + ">" + other).hashCode()) * 1_000_003L + day * 7919L + salt;
    }

    private static int mix(int value) {
        int h = value * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return h;
    }
}
