package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MayorTest {
    @Test
    void nobodyToChoose() {
        assertEquals("", Mayor.choose(List.of()));
    }

    @Test
    void highestTotalSkillWins() {
        List<Mayor.Candidate> list = List.of(new Mayor.Candidate("a", 10, 40), new Mayor.Candidate("b", 500, 90),
                new Mayor.Candidate("c", 1, 10));
        assertEquals("b", Mayor.choose(list));
    }

    @Test
    void tiesGoToTheOldestMemberThenTheId() {
        assertEquals("b", Mayor.choose(List.of(new Mayor.Candidate("a", 50, 20), new Mayor.Candidate("b", 5, 20))));
        assertEquals("a", Mayor.choose(List.of(new Mayor.Candidate("b", 5, 20), new Mayor.Candidate("a", 5, 20))));
    }

    @Test
    void choiceDoesNotDependOnOrder() {
        List<Mayor.Candidate> one = List.of(new Mayor.Candidate("x", 3, 7), new Mayor.Candidate("y", 3, 7),
                new Mayor.Candidate("z", 9, 7));
        assertEquals(Mayor.choose(one), Mayor.choose(List.of(one.get(2), one.get(1), one.get(0))));
    }

    @Test
    void sittingHeadIsKeptWhilePresent() {
        List<Mayor.Candidate> list = List.of(new Mayor.Candidate("a", 10, 1), new Mayor.Candidate("b", 1, 99));
        assertTrue(Mayor.keeps("a", list));
        assertFalse(Mayor.keeps("gone", list));
        assertFalse(Mayor.keeps("", list));
        assertFalse(Mayor.keeps(null, list));
    }

    @Test
    void headMovesInFromStageThreeWithABed() {
        assertFalse(Mayor.movesIn(2, true));
        assertFalse(Mayor.movesIn(3, false));
        assertTrue(Mayor.movesIn(3, true));
        assertTrue(Mayor.movesIn(5, true));
    }

    @Test
    void totalSkillIgnoresNegativeValues() {
        assertEquals(15, Mayor.totalXp(Map.of("miner", 10, "guard", 5, "bad", -4)));
    }

    @Test
    void headSurvivesInColonyData() {
        ColonyData data = new ColonyData();
        assertEquals("", data.head);
        assertFalse(data.headHoused);
        assertTrue(data.treasury.isEmpty());
        data.head = "abc";
        data.headHoused = true;
        data.treasury.add(new Pos(1, 2, 3));
        ColonyData copy = ColonyCodec.read(ColonyCodec.write(List.of(data))).getFirst();
        assertEquals("abc", copy.head);
        assertTrue(copy.headHoused);
        assertEquals(List.of(new Pos(1, 2, 3)), copy.treasury);
    }

    @Test
    void oldSaveWithoutTheFieldsGetsDefaults() {
        String json = "{\"schemaVersion\":1,\"colonies\":[{\"id\":\"x\",\"name\":\"old\"}]}";
        ColonyData copy = ColonyCodec.read(json).getFirst();
        assertEquals("", copy.head);
        assertFalse(copy.headHoused);
        assertTrue(copy.treasury.isEmpty());
    }
}
