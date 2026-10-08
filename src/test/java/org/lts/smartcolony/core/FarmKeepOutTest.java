package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FarmKeepOutTest {
    @Test
    void boxKeepsItsMargin() {
        FarmKeepOut zones = new FarmKeepOut();
        zones.addBox(10, 10, 20, 20, 3);
        assertTrue(zones.blocked(7, 7));
        assertTrue(zones.blocked(23, 23));
        assertTrue(zones.blocked(15, 15));
        assertFalse(zones.blocked(6, 15));
        assertFalse(zones.blocked(15, 24));
    }

    @Test
    void discCoversPlazaRing() {
        FarmKeepOut zones = new FarmKeepOut();
        zones.addDisc(0, 0, 8);
        assertTrue(zones.blocked(8, 0));
        assertTrue(zones.blocked(5, 5));
        assertFalse(zones.blocked(7, 7));
        assertFalse(zones.blocked(9, 0));
    }

    @Test
    void roadCellsKeepOneBlockMargin() {
        FarmKeepOut zones = new FarmKeepOut();
        zones.addCell(5, 5);
        assertTrue(zones.blocked(5, 5));
        assertTrue(zones.blocked(6, 6));
        assertTrue(zones.blocked(4, 5));
        assertFalse(zones.blocked(7, 5));
        assertFalse(zones.blocked(5, 3));
    }

    @Test
    void negativeCoordinatesDoNotCollide() {
        FarmKeepOut zones = new FarmKeepOut();
        zones.addCell(-3, 4);
        assertTrue(zones.blocked(-3, 4));
        assertFalse(zones.blocked(4, -3));
        assertFalse(zones.blocked(3, 4));
    }

    @Test
    void emptyZonesBlockNothing() {
        assertFalse(new FarmKeepOut().blocked(0, 0));
    }

    private static TerrainMap grass() {
        TerrainMap map = new TerrainMap(0, 0, 12);
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                map.set(x, z, new TerrainMap.Column(64, TerrainMap.Surface.GROUND, 0), 0);
            }
        }
        return map;
    }

    @Test
    void foreignStructuresAndNaturalPathsAreAvoided() {
        TerrainMap map = grass();
        assertFalse(FarmKeepOut.terrainBlocked(map, 0, 0));
        map.set(2, 0, new TerrainMap.Column(64, TerrainMap.Surface.STRUCTURE, 0), 0);
        assertTrue(FarmKeepOut.terrainBlocked(map, 0, 0));
        assertFalse(FarmKeepOut.terrainBlocked(map, -1, 0));
        map.set(-5, 5, new TerrainMap.Column(64, TerrainMap.Surface.PATH, 0), 0);
        assertTrue(FarmKeepOut.terrainBlocked(map, -5, 6));
        assertFalse(FarmKeepOut.terrainBlocked(map, -5, 8));
    }

    @Test
    void quarryEdgeIsAvoidedAndMissingMapIsIgnored() {
        TerrainMap map = grass();
        map.set(0, 2, new TerrainMap.Column(64, TerrainMap.Surface.QUARRY, 0), 0);
        assertTrue(FarmKeepOut.terrainBlocked(map, 0, 0));
        assertFalse(FarmKeepOut.terrainBlocked(null, 0, 0));
    }
    @Test
    void wideRoadMarginKeepsTreesOffTheRoadside() {
        FarmKeepOut groves = new FarmKeepOut(3);
        groves.addCell(0, 0);
        groves.addBox(10, 10, 18, 18, 5);
        assertTrue(groves.blocked(3, 0));
        assertFalse(groves.blocked(4, 0));
        assertTrue(groves.blocked(5, 14));
        assertFalse(groves.blocked(4, 14));
        FarmKeepOut fields = new FarmKeepOut();
        fields.addCell(0, 0);
        assertFalse(fields.blocked(2, 0));
    }
}
