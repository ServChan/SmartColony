package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OutpostPersistenceTest {
    @Test
    void outpostsSurviveASaveAndLoad() {
        ColonyData data = new ColonyData();
        data.id = "c1";
        ColonyData.Outpost outpost = new ColonyData.Outpost();
        outpost.kind = PorterPlan.FOREST;
        outpost.door = new Pos(10, 64, 20);
        outpost.center = new Pos(11, 64, 22);
        outpost.containers.add(new Pos(10, 65, 23));
        outpost.received = 120;
        outpost.hauled = 96;
        outpost.lastUse = 5000;
        data.outposts.add(outpost);
        List<ColonyData> back = ColonyCodec.read(ColonyCodec.write(List.of(data)));
        assertEquals(1, back.size());
        assertEquals(1, back.getFirst().outposts.size());
        ColonyData.Outpost loaded = back.getFirst().outposts.getFirst();
        assertEquals(PorterPlan.FOREST, loaded.kind);
        assertEquals(new Pos(10, 64, 20), loaded.door);
        assertEquals(List.of(new Pos(10, 65, 23)), loaded.containers);
        assertEquals(120, loaded.received);
        assertEquals(96, loaded.hauled);
        assertEquals(5000, loaded.lastUse);
    }

    @Test
    void savesWithoutOutpostsLoadWithAnEmptyList() {
        String json = "{\"schemaVersion\":1,\"colonies\":[{\"id\":\"old\",\"name\":\"Old\"}]}";
        List<ColonyData> loaded = ColonyCodec.read(json);
        assertEquals(1, loaded.size());
        assertNotNull(loaded.getFirst().outposts);
        assertTrue(loaded.getFirst().outposts.isEmpty());
    }
}
