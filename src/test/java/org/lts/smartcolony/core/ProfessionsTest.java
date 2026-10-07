package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProfessionsTest {
    @Test
    void everyRoleExceptIdleHasAProfession() {
        for (Role role : Role.values()) {
            if (role == Role.IDLE) {
                assertNull(Professions.forRole(role));
            } else {
                assertNotNull(Professions.forRole(role));
            }
        }
    }

    @Test
    void mappingMatchesTheTrade() {
        assertEquals("farmer", Professions.forRole(Role.FARMER));
        assertEquals("weaponsmith", Professions.forRole(Role.GUARD));
        assertEquals("armorer", Professions.forRole(Role.SMITH));
        assertEquals("toolsmith", Professions.forRole(Role.MINER));
        assertEquals("mason", Professions.forRole(Role.BUILDER));
        assertEquals("butcher", Professions.forRole(Role.INNKEEPER));
        assertEquals("cleric", Professions.forRole(Role.UNDERTAKER));
    }

    @Test
    void levelFollowsSkillWithinVanillaRange() {
        assertEquals(1, Professions.levelFor(0));
        assertEquals(3, Professions.levelFor(3));
        assertEquals(5, Professions.levelFor(9));
    }

    @Test
    void xpStaysInsideTheLevelBandAndAboveZero() {
        assertEquals(1, Professions.clampXp(1, 0));
        assertEquals(9, Professions.clampXp(1, 400));
        assertEquals(10, Professions.clampXp(2, 0));
        assertEquals(69, Professions.clampXp(2, 500));
        assertEquals(300, Professions.clampXp(5, 300));
        assertEquals(250, Professions.clampXp(5, 0));
    }

    @Test
    void unemployedVillagerGetsTheProfessionAtOnce() {
        Professions.Plan plan = Professions.plan("none", 1, Role.FARMER, 2, 0);
        assertEquals("farmer", plan.profession());
        assertEquals(2, plan.level());
        assertTrue(plan.professionChange());
    }

    @Test
    void recentRoleChangeWaitsBeforeChangingProfession() {
        assertNull(Professions.plan("librarian", 2, Role.FARMER, 2, Professions.HOLD_TICKS - 1));
        assertEquals("farmer", Professions.plan("librarian", 2, Role.FARMER, 2, Professions.HOLD_TICKS).profession());
    }

    @Test
    void idleAndNitwitAreLeftAlone() {
        assertNull(Professions.plan("farmer", 3, Role.IDLE, 0, 99999));
        assertNull(Professions.plan("nitwit", 1, Role.MINER, 5, 99999));
    }

    @Test
    void matchingStateNeedsNoPlan() {
        assertNull(Professions.plan("mason", 4, Role.BUILDER, 4, 99999));
    }

    @Test
    void skillGainRaisesLevelWithoutProfessionChange() {
        Professions.Plan plan = Professions.plan("mason", 1, Role.BUILDER, 3, 99999);
        assertFalse(plan.professionChange());
        assertEquals(3, plan.level());
        assertTrue(Professions.incremental(false, 1, 3));
        assertFalse(Professions.incremental(false, 4, 3));
        assertFalse(Professions.incremental(true, 1, 3));
    }
}
