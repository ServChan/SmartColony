package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BunkerPlanTest {
    private static final BunkerPlan.Templates TEMPLATES = load();

    private static BunkerPlan.Templates load() {
        try {
            return new BunkerPlan.Templates(
                    Blueprint.parse(Files.readString(Path.of("src/main/resources/smartcolony/bunker/bunker_hut.json"), StandardCharsets.UTF_8)),
                    Blueprint.parse(Files.readString(Path.of("src/main/resources/smartcolony/bunker/bunker_room.json"), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<BunkerPlan.Layout> layouts() {
        List<BunkerPlan.Layout> layouts = new ArrayList<>();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dir : dirs) {
            layouts.add(BunkerPlan.Layout.standard(100, 70, -40, dir[0], dir[1], BunkerPlan.DEPTH));
        }
        layouts.add(BunkerPlan.Layout.standard(-5, 64, 12, 0, -1, 13));
        return layouts;
    }

    private static int along(BunkerPlan.Layout layout, int x, int z) {
        return (x - layout.x()) * layout.dirX() + (z - layout.z()) * layout.dirZ();
    }

    private static int across(BunkerPlan.Layout layout, int x, int z) {
        return (x - layout.x()) * -layout.dirZ() + (z - layout.z()) * layout.dirX();
    }

    @Test
    void cellsNeverRepeatAndDoorsComeAfterTheDigging() {
        for (BunkerPlan.Layout layout : layouts()) {
            List<BunkerPlan.Cell> cells = BunkerPlan.cells(layout, TEMPLATES, "spruce");
            Set<Long> seen = new HashSet<>();
            Set<Long> dug = BunkerPlan.digSet(cells);
            boolean placing = false;
            for (BunkerPlan.Cell cell : cells) {
                long key = BunkerPlan.key(cell.x(), cell.y(), cell.z());
                if (cell.kind() == BunkerPlan.Kind.DIG) {
                    assertFalse(placing, "dig after placing started: " + cell);
                    assertTrue(seen.add(key), "dig repeats: " + cell);
                    continue;
                }
                placing = true;
                assertTrue(seen.add(key) || dug.contains(key), "cell placed twice: " + cell);
            }
        }
    }

    @Test
    void everyDugCellTouchesAnEarlierOneOrTheSurface() {
        for (BunkerPlan.Layout layout : layouts()) {
            Set<Long> dug = new HashSet<>();
            for (BunkerPlan.Cell cell : BunkerPlan.cells(layout, TEMPLATES, "oak")) {
                if (cell.kind() != BunkerPlan.Kind.DIG) {
                    continue;
                }
                boolean surface = cell.y() >= layout.y();
                boolean touches = false;
                for (int[] side : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                    if (dug.contains(BunkerPlan.key(cell.x() + side[0], cell.y() + side[1], cell.z() + side[2]))) {
                        touches = true;
                        break;
                    }
                }
                assertTrue(surface || touches, "unreachable dig " + cell);
                dug.add(BunkerPlan.key(cell.x(), cell.y(), cell.z()));
            }
        }
    }

    @Test
    void stairsDropOneBlockPerStepOnStoneBrickStepsWithHeadroom() {
        for (BunkerPlan.Layout layout : layouts()) {
            List<BunkerPlan.Cell> cells = BunkerPlan.cells(layout, TEMPLATES, "oak");
            Set<Long> dug = BunkerPlan.digSet(cells);
            for (int step = 1; step <= layout.depth(); step++) {
                int x = layout.x() + layout.dirX() * step;
                int z = layout.z() + layout.dirZ() * step;
                int feet = layout.y() - step;
                for (int y = feet; y <= feet + 2; y++) {
                    assertTrue(dug.contains(BunkerPlan.key(x, y, z)), "step " + step + " y " + y);
                }
                final int floor = feet - 1;
                assertTrue(cells.stream().anyMatch(c -> c.x() == x && c.z() == z && c.y() == floor && c.state().contains("stone_brick_stairs")),
                        "step " + step + " has a stair under it");
            }
        }
    }

    @Test
    void doorsSitAtTheHutFrontAndTheRoomEntrance() {
        for (BunkerPlan.Layout layout : layouts()) {
            List<BunkerPlan.Cell> doors = BunkerPlan.cells(layout, TEMPLATES, "oak").stream()
                    .filter(c -> c.state().contains("_door")).toList();
            assertEquals(2, doors.size(), "two doors");
            Set<Integer> alongs = new HashSet<>();
            for (BunkerPlan.Cell door : doors) {
                assertEquals(0, across(layout, door.x(), door.z()), "door on the axis");
                alongs.add(along(layout, door.x(), door.z()));
            }
            assertEquals(Set.of(-1, layout.depth() + 1), alongs);
            int[] door = BunkerPlan.door(layout, TEMPLATES);
            assertEquals(-1, along(layout, door[0], door[2]));
            int[] outside = BunkerPlan.outside(layout, TEMPLATES);
            assertEquals(-2, along(layout, outside[0], outside[2]));
            assertEquals(layout.y(), outside[1]);
        }
    }

    @Test
    void roomSpotCapacityAndFurniture() {
        for (BunkerPlan.Layout layout : layouts()) {
            int[] spot = BunkerPlan.spot(layout, TEMPLATES);
            assertEquals(layout.floorY(), spot[1]);
            assertTrue(BunkerPlan.inRoom(layout, TEMPLATES, spot[0], spot[1], spot[2]));
            assertFalse(BunkerPlan.inRoom(layout, TEMPLATES, layout.x(), layout.y(), layout.z()));
            List<BunkerPlan.Cell> cells = BunkerPlan.cells(layout, TEMPLATES, "oak");
            assertEquals(0, cells.stream().filter(c -> c.state().contains("_bed")).count(), "beds would count as homes");
            assertTrue(cells.stream().filter(c -> c.state().contains("white_wool")).count() >= 40);
            assertTrue(cells.stream().anyMatch(c -> c.state().contains("crafting_table")));
            assertTrue(cells.stream().anyMatch(c -> c.state().contains("smoker")));
            assertTrue(cells.stream().anyMatch(c -> c.state().contains("water_cauldron")));
            assertTrue(cells.stream().filter(c -> c.state().contains("lantern")).count() >= 20);
            int[] room = BunkerPlan.roomBox(layout, TEMPLATES);
            assertEquals(layout.floorY() - 6, room[1]);
        }
        assertTrue(BunkerPlan.capacity(TEMPLATES) >= 100, "capacity " + BunkerPlan.capacity(TEMPLATES));
    }

    @Test
    void bothFloorsAreDugAndJoinedByStairs() {
        for (BunkerPlan.Layout layout : layouts()) {
            List<BunkerPlan.Cell> cells = BunkerPlan.cells(layout, TEMPLATES, "oak");
            Set<Long> dug = BunkerPlan.digSet(cells);
            int lower = layout.floorY() - 5;
            long lowerFloor = cells.stream().filter(c -> c.kind() == BunkerPlan.Kind.DIG && c.y() == lower
                    && BunkerPlan.inRoom(layout, TEMPLATES, c.x(), c.y(), c.z())).count();
            long upperFloor = cells.stream().filter(c -> c.kind() == BunkerPlan.Kind.DIG && c.y() == layout.floorY()
                    && BunkerPlan.inRoom(layout, TEMPLATES, c.x(), c.y(), c.z())).count();
            assertTrue(lowerFloor >= 200, "lower floor " + lowerFloor);
            assertTrue(upperFloor >= 200, "upper floor " + upperFloor);
            long steps = cells.stream().filter(c -> c.state().contains("stone_brick_stairs") && c.y() < layout.floorY() - 1
                    && BunkerPlan.inRoom(layout, TEMPLATES, c.x(), c.y(), c.z())).count();
            assertEquals(4, steps);
            for (BunkerPlan.Cell cell : cells) {
                if (cell.state().contains("stone_brick_stairs") && cell.y() < layout.floorY() - 1) {
                    for (int y = cell.y() + 1; y <= cell.y() + 3; y++) {
                        assertTrue(dug.contains(BunkerPlan.key(cell.x(), y, cell.z())), "headroom over " + cell + " at " + y);
                    }
                }
            }
        }
    }

    @Test
    void standsAreDugFloorsOrTheOutside() {
        for (BunkerPlan.Layout layout : layouts()) {
            List<BunkerPlan.Cell> cells = BunkerPlan.cells(layout, TEMPLATES, "oak");
            Set<Long> dug = BunkerPlan.digSet(cells);
            int[] outside = BunkerPlan.outside(layout, TEMPLATES);
            for (BunkerPlan.Cell cell : cells) {
                int[] stand = BunkerPlan.stand(layout, TEMPLATES, cell);
                boolean out = stand[0] == outside[0] && stand[1] == outside[1] && stand[2] == outside[2];
                assertTrue(out || dug.contains(BunkerPlan.key(stand[0], stand[1], stand[2])), "stand for " + cell);
                double dx = stand[0] - cell.x();
                double dy = stand[1] + 1.6 - cell.y();
                double dz = stand[2] - cell.z();
                assertTrue(Math.sqrt(dx * dx + dy * dy + dz * dz) <= 7.0, "stand too far for " + cell + " at " + stand[0] + " " + stand[1] + " " + stand[2]);
            }
        }
    }

    @Test
    void structureNeedsBricksButFurnitureIsOptional() {
        assertEquals(BunkerPlan.Kind.BLOCK, BunkerPlan.kind("minecraft:stone_bricks"));
        assertEquals(BunkerPlan.Kind.BLOCK, BunkerPlan.kind("minecraft:oak_door[half=lower]"));
        assertEquals(BunkerPlan.Kind.BLOCK, BunkerPlan.kind("minecraft:stripped_oak_log[axis=y]"));
        assertEquals(BunkerPlan.Kind.FURNISH, BunkerPlan.kind("minecraft:white_bed[part=foot]"));
        assertEquals(BunkerPlan.Kind.FURNISH, BunkerPlan.kind("minecraft:lantern[hanging=true]"));
        assertThrows(IllegalArgumentException.class, () -> BunkerPlan.Layout.standard(0, 0, 0, 1, 1, 10));
    }
}
