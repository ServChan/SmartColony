package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiplomacyTest {
    private static Diplomacy.Facts facts(Diplomacy.Temper temper, Diplomacy.Policy policy, int standing, Diplomacy.Treaty treaty,
                                         double distance, int guards, int theirGuards, int weariness) {
        return new Diplomacy.Facts(temper, policy, standing, treaty, distance, 32, 32, guards, theirGuards, false, 0, weariness);
    }

    @Test
    void temperIsStableAndEveryKindOccurs() {
        assertEquals(Diplomacy.temperOf("colony-7"), Diplomacy.temperOf("colony-7"));
        Set<Diplomacy.Temper> seen = EnumSet.noneOf(Diplomacy.Temper.class);
        for (int i = 0; i < 200; i++) {
            seen.add(Diplomacy.temperOf("c" + i));
        }
        assertEquals(EnumSet.allOf(Diplomacy.Temper.class), seen);
    }

    @Test
    void stanceFollowsStandingUnlessATreatyDecides() {
        assertEquals(Diplomacy.Stance.HOSTILE, Diplomacy.stance(-30, Diplomacy.Treaty.NONE));
        assertEquals(Diplomacy.Stance.NEUTRAL, Diplomacy.stance(0, Diplomacy.Treaty.NONE));
        assertEquals(Diplomacy.Stance.FRIENDLY, Diplomacy.stance(30, Diplomacy.Treaty.TRADE));
        assertEquals(Diplomacy.Stance.WAR, Diplomacy.stance(50, Diplomacy.Treaty.WAR));
        assertEquals(Diplomacy.Stance.ALLIED, Diplomacy.stance(10, Diplomacy.Treaty.ALLIANCE));
    }

    @Test
    void crowdedBorderAngersProudColoniesMoreThanPeacefulOnes() {
        int proud = Diplomacy.drift(facts(Diplomacy.Temper.PROUD, Diplomacy.Policy.AUTO, 0, Diplomacy.Treaty.NONE, 100, 1, 1, 0)).delta();
        int peaceful = Diplomacy.drift(facts(Diplomacy.Temper.PEACEFUL, Diplomacy.Policy.AUTO, 0, Diplomacy.Treaty.NONE, 100, 1, 1, 0)).delta();
        int far = Diplomacy.drift(facts(Diplomacy.Temper.PROUD, Diplomacy.Policy.AUTO, 0, Diplomacy.Treaty.NONE, 400, 1, 1, 0)).delta();
        assertEquals(-4, proud);
        assertEquals(-1, peaceful);
        assertEquals(0, far);
    }

    @Test
    void alliesDoNotQuarrelOverTheBorderAndPactsWarmRelations() {
        Diplomacy.Drift allied = Diplomacy.drift(facts(Diplomacy.Temper.PROUD, Diplomacy.Policy.AUTO, 70, Diplomacy.Treaty.ALLIANCE,
                90, 6, 1, 0));
        assertEquals(1, allied.delta());
        Diplomacy.Drift trade = Diplomacy.drift(facts(Diplomacy.Temper.MERCHANT, Diplomacy.Policy.AUTO, 30, Diplomacy.Treaty.TRADE,
                400, 1, 1, 0));
        assertEquals(2, trade.delta());
    }

    @Test
    void grudgesFadeWithoutWar() {
        assertEquals(1, Diplomacy.drift(facts(Diplomacy.Temper.MERCHANT, Diplomacy.Policy.AUTO, -40, Diplomacy.Treaty.NONE, 400, 1, 1, 0))
                .delta());
        assertEquals(-1, Diplomacy.drift(facts(Diplomacy.Temper.MERCHANT, Diplomacy.Policy.AUTO, -40, Diplomacy.Treaty.WAR, 400, 1, 1, 0))
                .delta());
    }

    @Test
    void onlyAngryArmedColoniesDeclareWar() {
        assertTrue(Diplomacy.canDeclareWar(facts(Diplomacy.Temper.PROUD, Diplomacy.Policy.AUTO, -60, Diplomacy.Treaty.NONE, 300, 3, 2, 0)));
        assertFalse(Diplomacy.canDeclareWar(facts(Diplomacy.Temper.MERCHANT, Diplomacy.Policy.AUTO, -60, Diplomacy.Treaty.NONE, 300, 3, 2, 0)));
        assertTrue(Diplomacy.canDeclareWar(facts(Diplomacy.Temper.MERCHANT, Diplomacy.Policy.AUTO, -90, Diplomacy.Treaty.NONE, 300, 3, 2, 0)));
        assertTrue(Diplomacy.canDeclareWar(facts(Diplomacy.Temper.PEACEFUL, Diplomacy.Policy.WARLIKE, -30, Diplomacy.Treaty.NONE, 300, 3, 2, 0)));
        assertFalse(Diplomacy.canDeclareWar(facts(Diplomacy.Temper.PROUD, Diplomacy.Policy.PEACEFUL, -100, Diplomacy.Treaty.NONE, 300, 3, 2, 0)));
        assertFalse(Diplomacy.canDeclareWar(facts(Diplomacy.Temper.PROUD, Diplomacy.Policy.AUTO, -100, Diplomacy.Treaty.TRUCE, 300, 3, 2, 0)));
        assertFalse(Diplomacy.canDeclareWar(facts(Diplomacy.Temper.PROUD, Diplomacy.Policy.AUTO, -100, Diplomacy.Treaty.NONE, 300, 1, 2, 0)));
    }

    @Test
    void decisionsMatchTheStateOfRelations() {
        assertEquals(Diplomacy.Action.DECLARE_WAR, Diplomacy.decide(facts(Diplomacy.Temper.PROUD, Diplomacy.Policy.AUTO, -70,
                Diplomacy.Treaty.NONE, 300, 4, 2, 0), 1, false, true));
        assertEquals(Diplomacy.Action.PROPOSE_TRADE, Diplomacy.decide(facts(Diplomacy.Temper.MERCHANT, Diplomacy.Policy.AUTO, 30,
                Diplomacy.Treaty.NONE, 300, 1, 1, 0), 1, false, false));
        assertEquals(Diplomacy.Action.PROPOSE_ALLIANCE, Diplomacy.decide(facts(Diplomacy.Temper.PEACEFUL, Diplomacy.Policy.AUTO, 65,
                Diplomacy.Treaty.TRADE, 300, 1, 1, 0), 1, false, false));
        assertEquals(Diplomacy.Action.PROPOSE_TRUCE, Diplomacy.decide(facts(Diplomacy.Temper.PROUD, Diplomacy.Policy.AUTO, -70,
                Diplomacy.Treaty.WAR, 300, 4, 2, Diplomacy.WEARY_AT), 1, false, true));
        assertEquals(Diplomacy.Action.VISIT, Diplomacy.decide(facts(Diplomacy.Temper.PEACEFUL, Diplomacy.Policy.AUTO, 70,
                Diplomacy.Treaty.ALLIANCE, 300, 1, 1, 0), 1, true, false));
    }

    @Test
    void warringColoniesRaidOnlyWithEnoughGuards() {
        boolean raided = false;
        for (long seed = 0; seed < 20; seed++) {
            Diplomacy.Action action = Diplomacy.decide(facts(Diplomacy.Temper.PROUD, Diplomacy.Policy.AUTO, -70, Diplomacy.Treaty.WAR,
                    300, 2, 2, 0), seed, false, true);
            assertTrue(action != Diplomacy.Action.RAID);
            raided |= Diplomacy.decide(facts(Diplomacy.Temper.PROUD, Diplomacy.Policy.AUTO, -70, Diplomacy.Treaty.WAR, 300, 5, 2, 0),
                    seed, false, true) == Diplomacy.Action.RAID;
        }
        assertTrue(raided);
        assertEquals(0, Diplomacy.raidParty(2));
        assertEquals(2, Diplomacy.raidParty(3));
        assertEquals(Diplomacy.MAX_RAIDERS, Diplomacy.raidParty(12));
    }

    @Test
    void treatiesAreAcceptedOnlyWhenTheyMakeSense() {
        assertTrue(Diplomacy.accepts(Diplomacy.Action.PROPOSE_TRADE, Diplomacy.Temper.MERCHANT, Diplomacy.Policy.AUTO, 12,
                Diplomacy.Treaty.NONE, 0));
        assertFalse(Diplomacy.accepts(Diplomacy.Action.PROPOSE_TRADE, Diplomacy.Temper.PROUD, Diplomacy.Policy.AUTO, 22,
                Diplomacy.Treaty.NONE, 0));
        assertFalse(Diplomacy.accepts(Diplomacy.Action.PROPOSE_ALLIANCE, Diplomacy.Temper.PEACEFUL, Diplomacy.Policy.AUTO, 90,
                Diplomacy.Treaty.NONE, 0));
        assertTrue(Diplomacy.accepts(Diplomacy.Action.PROPOSE_ALLIANCE, Diplomacy.Temper.PEACEFUL, Diplomacy.Policy.AUTO, 65,
                Diplomacy.Treaty.TRADE, 0));
        assertFalse(Diplomacy.accepts(Diplomacy.Action.PROPOSE_TRUCE, Diplomacy.Temper.PROUD, Diplomacy.Policy.AUTO, -95,
                Diplomacy.Treaty.WAR, 0));
        assertTrue(Diplomacy.accepts(Diplomacy.Action.PROPOSE_TRUCE, Diplomacy.Temper.PROUD, Diplomacy.Policy.AUTO, -95,
                Diplomacy.Treaty.WAR, Diplomacy.WEARY_AT));
        assertTrue(Diplomacy.accepts(Diplomacy.Action.PROPOSE_TRUCE, Diplomacy.Temper.PROUD, Diplomacy.Policy.PEACEFUL, -95,
                Diplomacy.Treaty.WAR, 0));
    }

    @Test
    void giftsHelpButNeverBuyFriendshipOutright() {
        assertEquals(0, Diplomacy.giftBonus(0, Diplomacy.Temper.MERCHANT));
        assertTrue(Diplomacy.giftBonus(100, Diplomacy.Temper.PEACEFUL) >= Diplomacy.GIFT_COURTESY);
        assertEquals(Diplomacy.MAX_GIFT_BONUS, Diplomacy.giftBonus(1_000_000, Diplomacy.Temper.MERCHANT));
        assertTrue(Diplomacy.giftBonus(1500, Diplomacy.Temper.PROUD) < Diplomacy.giftBonus(1500, Diplomacy.Temper.MERCHANT));
    }

    @Test
    void warWearinessGrowsWithLossesAndTime() {
        assertTrue(Diplomacy.weariness(2, 1, 1) > Diplomacy.weariness(0, 0, 1));
        assertTrue(Diplomacy.weariness(0, 0, 20) <= 30);
        assertEquals(Diplomacy.PEACE_FLOOR, Diplomacy.afterTruce(-80));
        assertEquals(10, Diplomacy.afterTruce(10));
    }

    @Test
    void incidentsAreDeterministicAndOnlyBetweenCloseNeighbours() {
        int found = 0;
        for (long seed = 0; seed < 200; seed++) {
            Diplomacy.Incident a = Diplomacy.incident(seed, Diplomacy.Temper.PROUD, Diplomacy.Temper.MERCHANT, Diplomacy.Stance.NEUTRAL, true, 60);
            Diplomacy.Incident b = Diplomacy.incident(seed, Diplomacy.Temper.PROUD, Diplomacy.Temper.MERCHANT, Diplomacy.Stance.NEUTRAL, true, 60);
            assertEquals(a, b);
            if (a != null) {
                found++;
            }
            assertNull(Diplomacy.incident(seed, Diplomacy.Temper.PROUD, Diplomacy.Temper.MERCHANT, Diplomacy.Stance.NEUTRAL, true, 600));
        }
        assertTrue(found > 20 && found < 80);
        Diplomacy.Incident war = null;
        for (long seed = 0; war == null && seed < 50; seed++) {
            war = Diplomacy.incident(seed, Diplomacy.Temper.PEACEFUL, Diplomacy.Temper.PEACEFUL, Diplomacy.Stance.WAR, false, 600);
        }
        assertNotNull(war);
        assertTrue(war.delta() < 0);
    }

    @Test
    void seedsDifferPerSideButPairSeedIsShared() {
        assertEquals(Diplomacy.pairSeed("a", "b", 5, 1), Diplomacy.pairSeed("b", "a", 5, 1));
        assertTrue(Diplomacy.seed("a", "b", 5, 1) != Diplomacy.seed("b", "a", 5, 1));
    }
}
