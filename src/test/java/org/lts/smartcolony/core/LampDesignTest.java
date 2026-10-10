package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LampDesignTest {
    @Test
    void stoneAgePostCagesTheTorchUnderASlab() {
        List<LampDesign.Layer> layers = LampDesign.layers(LampDesign.STONE_POST, "oak", "minecraft:cobblestone_wall", 0, 0);
        assertEquals(9, layers.size());
        assertEquals("minecraft:cobblestone_wall", layers.get(0).state());
        assertEquals("minecraft:oak_fence", layers.get(1).state());
        assertEquals("minecraft:torch", layers.get(3).state());
        assertEquals(4, layers.stream().filter(l -> l.state().startsWith("minecraft:oak_trapdoor") && l.state().contains("open=true")
                && l.dy() == 3).count());
        assertEquals("minecraft:oak_slab[type=bottom]", layers.getLast().state());
        assertEquals(4, layers.getLast().dy());
        assertTrue(LampDesign.bottomUp(layers));
    }

    @Test
    void lanternPostHangsTheLanternFromAnArm() {
        List<LampDesign.Layer> layers = LampDesign.layers(LampDesign.LANTERN_POST, "spruce", "minecraft:stone_bricks", 1, 0);
        assertTrue(LampDesign.bottomUp(layers));
        assertEquals("minecraft:stone_bricks", layers.getFirst().state());
        LampDesign.Layer arm = layers.get(4);
        LampDesign.Layer lantern = layers.get(5);
        assertEquals(1, arm.dx());
        assertEquals(3, arm.dy());
        assertEquals("minecraft:lantern[hanging=true]", lantern.state());
        assertEquals(2, lantern.dy());
        assertEquals(arm.dx(), lantern.dx());
    }

    @Test
    void lanternPostWithoutRoomForAnArmStandsTheLanternOnTop() {
        List<LampDesign.Layer> layers = LampDesign.layers(LampDesign.LANTERN_POST, "oak", "minecraft:cobblestone", 0, 0);
        assertTrue(LampDesign.bottomUp(layers));
        assertEquals("minecraft:lantern[hanging=false]", layers.getLast().state());
        assertEquals(4, layers.getLast().dy());
    }

    @Test
    void aBrokenOrderIsNotBottomUp() {
        List<LampDesign.Layer> layers = LampDesign.layers(LampDesign.STONE_POST, "oak", "minecraft:cobblestone", 0, 0);
        assertFalse(LampDesign.bottomUp(List.of(layers.get(1), layers.get(0))));
        assertFalse(LampDesign.bottomUp(List.of(layers.get(0), layers.get(3))));
    }

    @Test
    void needsCountBlocksPerItem() {
        var needs = LampDesign.needs(LampDesign.layers(LampDesign.LANTERN_POST, "oak", "minecraft:stone_bricks", 0, 1));
        assertEquals(1, needs.get("minecraft:stone_bricks"));
        assertEquals(4, needs.get("minecraft:oak_fence"));
        assertEquals(1, needs.get("minecraft:lantern"));
    }

    @Test
    void tierFollowsTheAgeOnlyWhenLanternsAreWithinReach() {
        assertEquals(LampDesign.STONE_POST, LampDesign.tierFor(Tech.STONE_AGE, true));
        assertEquals(LampDesign.STONE_POST, LampDesign.tierFor(Tech.IRON_AGE, false));
        assertEquals(LampDesign.LANTERN_POST, LampDesign.tierFor(Tech.IRON_AGE, true));
    }

    @Test
    void spacingIsMeasuredOnTheGround() {
        List<Pos> lamps = List.of(new Pos(0, 64, 0));
        assertFalse(LampDesign.spaced(lamps, new Pos(3, 70, 3), LampDesign.SPACING));
        assertTrue(LampDesign.spaced(lamps, new Pos(LampDesign.SPACING, 64, 0), LampDesign.SPACING));
    }

    @Test
    void pathStyleKeepsEarlyStreetsDirtAndPavesMainStreetsInTheIronAge() {
        assertEquals(PathStyle.DIRT_PATH, PathStyle.material(PathStyle.PLAIN, false, 4, 4, true, true));
        assertEquals(PathStyle.DIRT_PATH, PathStyle.material(PathStyle.PLAIN, true, 4, 4, true, true));
        assertEquals(PathStyle.DIRT_PATH, PathStyle.material(PathStyle.SIDE, false, 1, 1, false, false));
        String paved = PathStyle.material(PathStyle.MAIN, true, 4, 4, true, true);
        assertTrue(paved.equals(PathStyle.COBBLESTONE) || paved.equals(PathStyle.STONE_BRICKS));
        assertEquals(PathStyle.COBBLESTONE, PathStyle.material(PathStyle.MAIN, true, 4, 4, false, false));
        int gravel = 0;
        for (int i = 0; i < 100; i++) {
            if (PathStyle.material(PathStyle.SIDE, false, i, i * 3, false, true).equals(PathStyle.GRAVEL)) {
                gravel++;
            }
        }
        assertTrue(gravel > 5 && gravel < 40);
    }

    @Test
    void styleFlagsCompose() {
        String style = PathStyle.with(PathStyle.MAIN, PathStyle.STEP);
        assertTrue(PathStyle.step(style));
        assertEquals(PathStyle.MAIN, PathStyle.base(style));
        assertFalse(PathStyle.paved(style));
        assertTrue(PathStyle.paved(PathStyle.with(style, PathStyle.STONE)));
    }
}
