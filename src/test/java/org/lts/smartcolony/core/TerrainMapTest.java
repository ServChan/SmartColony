package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.lts.smartcolony.core.TerrainMap.Column;
import org.lts.smartcolony.core.TerrainMap.Surface;

class TerrainMapTest {
    private static Column ground(int y) {
        return new Column(y, Surface.GROUND, 0);
    }

    private static TerrainMap flat(int radius, int y) {
        TerrainMap map = new TerrainMap(100, 200, radius);
        for (int x = map.minX(); x < map.minX() + map.size(); x++) {
            for (int z = map.minZ(); z < map.minZ() + map.size(); z++) {
                map.set(x, z, ground(y), 5);
            }
        }
        return map;
    }

    @Test
    void unknownUntilScannedAndOutsideIsUnknown() {
        TerrainMap map = new TerrainMap(0, 0, 4);
        assertEquals(81, map.columns());
        assertFalse(map.known(1, 1));
        assertEquals(Surface.UNKNOWN, map.column(1, 1).surface());
        assertEquals(-1, map.scannedAt(1, 1));
        map.set(1, 1, new Column(70, Surface.WATER, 3), 40);
        assertTrue(map.known(1, 1));
        assertEquals(70, map.column(1, 1).ground());
        assertEquals(3, map.column(1, 1).depth());
        assertEquals(40, map.scannedAt(1, 1));
        assertFalse(map.contains(5, 0));
        map.set(50, 50, ground(1), 1);
        assertEquals(Surface.UNKNOWN, map.column(50, 50).surface());
    }

    @Test
    void regionStatsCountClassesAndSteps() {
        TerrainMap map = flat(10, 64);
        map.set(100, 200, ground(66), 5);
        map.set(101, 200, new Column(64, Surface.TREE, 0), 5);
        map.set(102, 200, new Column(64, Surface.WATER, 2), 5);
        TerrainMap.Stats stats = map.region(99, 199, 103, 201);
        assertEquals(15, stats.columns());
        assertEquals(15, stats.known());
        assertTrue(stats.complete());
        assertEquals(64, stats.min());
        assertEquals(66, stats.max());
        assertEquals(64, stats.median());
        assertEquals(2, stats.maxStep());
        assertEquals(1, stats.count(Surface.TREE));
        assertEquals(1, stats.count(Surface.WATER));
        assertEquals(13, stats.count(Surface.GROUND));
    }

    @Test
    void regionOutsideTheMapIsNotComplete() {
        TerrainMap map = flat(3, 70);
        TerrainMap.Stats stats = map.region(95, 195, 105, 205);
        assertFalse(stats.complete());
        assertEquals(49, stats.known());
        assertEquals(121 - 49, stats.count(Surface.UNKNOWN));
        TerrainMap.Stats empty = new TerrainMap(0, 0, 2).region(0, 0, 1, 1);
        assertEquals(0, empty.known());
        assertEquals(0, empty.maxStep());
    }

    @Test
    void diffReportsOnlyChangedColumns() {
        TerrainMap map = flat(6, 64);
        TerrainMap.Diff same = map.diff(98, 198, 102, 202, (x, z) -> ground(64));
        assertEquals(25, same.compared());
        assertEquals(0, same.changes().size());
        TerrainMap.Diff changed = map.diff(98, 198, 102, 202, (x, z) -> {
            if (x == 100 && z == 200) {
                return ground(60);
            }
            if (x == 101 && z == 200) {
                return new Column(64, Surface.STRUCTURE, 0);
            }
            if (x == 98) {
                return null;
            }
            return ground(64);
        });
        assertEquals(5, changed.unknown());
        assertEquals(20, changed.compared());
        assertEquals(2, changed.changes().size());
        assertEquals(64, changed.changes().getFirst().before().ground());
        assertEquals(60, changed.changes().getFirst().after().ground());
    }

    @Test
    void diffTreatsUnscannedColumnsAsUnknown() {
        TerrainMap map = new TerrainMap(0, 0, 3);
        TerrainMap.Diff diff = map.diff(0, 0, 1, 1, (x, z) -> ground(10));
        assertEquals(0, diff.compared());
        assertEquals(4, diff.unknown());
        assertTrue(diff.changes().isEmpty());
    }

    @Test
    void scanOrderStartsAtTheCentreVisitsEveryColumnOnceAndWraps() {
        TerrainMap map = new TerrainMap(10, 10, 5);
        Set<Integer> seen = new HashSet<>();
        int first = map.next();
        assertEquals(10, map.xOf(first));
        assertEquals(10, map.zOf(first));
        seen.add(first);
        double last = 0;
        for (int i = 1; i < map.columns(); i++) {
            int index = map.next();
            assertTrue(seen.add(index), "column visited twice");
            double dx = map.xOf(index) - 10;
            double dz = map.zOf(index) - 10;
            double distance = dx * dx + dz * dz;
            assertTrue(distance >= last, "must go outwards");
            last = distance;
        }
        assertEquals(1, map.wraps());
        assertEquals(first, map.next());
    }

    @Test
    void growingKeepsWhatWasScanned() {
        TerrainMap small = new TerrainMap(0, 0, 3);
        small.set(2, -1, new Column(80, Surface.ROAD, 0), 9);
        TerrainMap big = new TerrainMap(0, 0, 6);
        big.copyFrom(small);
        assertEquals(80, big.column(2, -1).ground());
        assertEquals(Surface.ROAD, big.column(2, -1).surface());
        assertEquals(9, big.scannedAt(2, -1));
        assertEquals(1, big.knownCount());
    }

    @Test
    void paintOnlyRepaintsKnownColumns() {
        TerrainMap map = new TerrainMap(0, 0, 4);
        map.set(0, 0, ground(64), 1);
        map.paint(-1, -1, 1, 1, Surface.BUILDING, 7);
        assertEquals(Surface.BUILDING, map.column(0, 0).surface());
        assertEquals(64, map.column(0, 0).ground());
        assertEquals(7, map.scannedAt(0, 0));
        assertEquals(Surface.UNKNOWN, map.column(1, 1).surface());
    }

    @Test
    void asciiUsesOneSymbolPerClass() {
        TerrainMap map = new TerrainMap(0, 0, 2);
        map.set(0, 0, new Column(64, Surface.WATER, 1), 1);
        map.set(1, 0, ground(64), 1);
        String[] rows = map.ascii(0, 0, 1).split("\n");
        assertEquals(3, rows.length);
        assertEquals("?~.", rows[1]);
    }
}
