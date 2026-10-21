package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.lts.smartcolony.core.PreBuildCheck.Verdict;

class PreBuildCheckTest {
    private static List<Pos> fill(int base, int... drops) {
        List<Pos> list = new ArrayList<>();
        for (int i = 0; i < drops.length; i++) {
            for (int y = base - drops[i]; y < base; y++) {
                list.add(new Pos(i, y, 0));
            }
        }
        return list;
    }

    @Test
    void unchangedPlanIsKept() {
        List<Pos> old = fill(64, 0, 1, 2);
        List<Pos> reordered = new ArrayList<>(old);
        java.util.Collections.reverse(reordered);
        assertEquals(Verdict.KEEP, PreBuildCheck.decide(true, 64, old, 64, reordered));
    }

    @Test
    void changedFillOrBaseRefitsThePlan() {
        assertEquals(Verdict.REFIT, PreBuildCheck.decide(true, 64, fill(64, 0, 1), 64, fill(64, 0, 3)));
        assertEquals(Verdict.REFIT, PreBuildCheck.decide(true, 64, fill(64, 1), 65, fill(65, 1)));
        assertEquals(Verdict.REFIT, PreBuildCheck.decide(true, 64, List.of(), 64, fill(64, 2)));
    }

    @Test
    void infeasibleSiteIsRelocatedWhateverTheFill() {
        assertEquals(Verdict.RELOCATE, PreBuildCheck.decide(false, 64, fill(64, 1), 64, fill(64, 1)));
        assertEquals(Verdict.RELOCATE, PreBuildCheck.decide(false, 64, fill(64, 1), 70, fill(70, 4)));
    }

    @Test
    void evaluatorAtFixedBaseKeepsTheBaseAndCountsFill() {
        int[][] ground = {{64, 62}, {64, 64}};
        boolean[][] free = new boolean[2][2];
        SiteEvaluator.Result result = SiteEvaluator.evaluateAt(ground, free, 64, 2, 0);
        assertTrue(result.ok());
        assertEquals(64, result.baseY());
        assertEquals(2, result.maxFill());
        assertEquals(false, SiteEvaluator.evaluateAt(ground, free, 64, 1, 0).ok());
        boolean[][] wet = {{false, true}, {false, false}};
        assertEquals(false, SiteEvaluator.evaluateAt(ground, wet, 64, 9, 9).ok());
        assertEquals(SiteEvaluator.evaluate(ground, free, 3, 4).baseY(), SiteEvaluator.evaluateAt(ground, free, 64, 3, 4).baseY());
    }

    @Test
    void reservedMaterialsAreSubtractedFromWhatIsStillNeeded() {
        Map<String, Integer> required = Map.of("minecraft:oak_planks", 40, "minecraft:cobblestone", 12, "minecraft:glass", 4);
        Map<String, Integer> reserved = Map.of("minecraft:oak_planks", 40, "minecraft:cobblestone", 20);
        assertEquals(Map.of("minecraft:glass", 4), PreBuildCheck.deficit(required, reserved));
        assertEquals(Map.of("minecraft:cobblestone", 8), PreBuildCheck.surplus(required, reserved));
        assertTrue(PreBuildCheck.deficit(required, required).isEmpty());
        assertTrue(PreBuildCheck.surplus(required, required).isEmpty());
        assertEquals(required, PreBuildCheck.deficit(required, Map.of()));
    }

    @Test
    void supportGapFindsTheDepthOfAirBelow() {
        assertEquals(0, Support.gap(depth -> Support.Cell.SOLID, 4));
        assertEquals(2, Support.gap(depth -> depth <= 2 ? Support.Cell.EMPTY : Support.Cell.SOLID, 4));
        assertEquals(4, Support.gap(depth -> depth <= 4 ? Support.Cell.EMPTY : Support.Cell.SOLID, 4));
        assertEquals(-1, Support.gap(depth -> depth <= 5 ? Support.Cell.EMPTY : Support.Cell.SOLID, 4));
        assertEquals(-1, Support.gap(depth -> depth == 3 ? Support.Cell.BLOCKED : depth < 3 ? Support.Cell.EMPTY : Support.Cell.SOLID, 4));
        assertEquals(-1, Support.gap(depth -> Support.Cell.EMPTY, 3));
    }

    @Test
    void chestCraftUsesPlanksFirstAndLogsForTheRest() {
        ChestCraft.Spend planks = ChestCraft.plan(10, 0);
        assertNotNull(planks);
        assertEquals(8, planks.planks());
        assertEquals(0, planks.logs());
        assertEquals(0, planks.planksBack());
        ChestCraft.Spend logs = ChestCraft.plan(0, 2);
        assertEquals(0, logs.planks());
        assertEquals(2, logs.logs());
        assertEquals(0, logs.planksBack());
        ChestCraft.Spend mixed = ChestCraft.plan(3, 2);
        assertEquals(3, mixed.planks());
        assertEquals(2, mixed.logs());
        assertEquals(3, mixed.planksBack());
        ChestCraft.Spend oneLog = ChestCraft.plan(5, 1);
        assertEquals(5, oneLog.planks());
        assertEquals(1, oneLog.logs());
        assertEquals(1, oneLog.planksBack());
        assertNull(ChestCraft.plan(0, 1));
        assertNull(ChestCraft.plan(3, 0));
        assertEquals(8, ChestCraft.PLANKS_PER_CHEST);
    }

    @Test
    void lateItemsAreSplitOffSoTheBuildCanStart() {
        java.util.Map<String, Integer> need = new java.util.LinkedHashMap<>();
        need.put("minecraft:oak_planks", 40);
        need.put("minecraft:white_bed", 12);
        need.put("minecraft:glass_pane", 6);
        need.put("minecraft:cobblestone", 20);
        java.util.Map<String, Integer> late = PreBuildCheck.late(need);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Map.of("minecraft:white_bed", 12, "minecraft:glass_pane", 6), late);
        java.util.Map<String, Integer> early = PreBuildCheck.without(need, late);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Map.of("minecraft:oak_planks", 40, "minecraft:cobblestone", 20), early);
        org.junit.jupiter.api.Assertions.assertFalse(PreBuildCheck.lateItem("minecraft:oak_door"));
    }

    @Test
    void aHouseWaitingOnlyForWoolGoodsIsHandedOverAfterAWhile() {
        List<String> beds = List.of("minecraft:white_bed", "minecraft:white_carpet");
        assertTrue(PreBuildCheck.handOver(beds, PreBuildCheck.HAND_OVER_AFTER));
        assertFalse(PreBuildCheck.handOver(beds, PreBuildCheck.HAND_OVER_AFTER - 1));
        assertFalse(PreBuildCheck.handOver(List.of("minecraft:white_bed", "minecraft:furnace"), PreBuildCheck.HAND_OVER_AFTER * 10));
        assertFalse(PreBuildCheck.handOver(List.of(), PreBuildCheck.HAND_OVER_AFTER * 10));
        assertTrue(PreBuildCheck.furnishing("minecraft:red_banner"));
        assertFalse(PreBuildCheck.furnishing("minecraft:lantern"));
    }
}
