package org.lts.smartcolony.colony;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.GuardDrill;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.work.Colonist;

public final class Garrison {
    private Garrison() {
    }

    public static ColonyData.Building find(Colony colony) {
        for (ColonyData.Building building : colony.data().buildings) {
            if (GuardDrill.ID.equals(building.kind)) {
                return building;
            }
        }
        return null;
    }

    public static BlockPos spot(Colony colony, ColonyData.Building barracks, int x, int z) {
        return colony.construction().world(barracks, x, 1, z);
    }

    public static List<UUID> guards(Colony colony) {
        List<UUID> guards = new ArrayList<>();
        for (Map.Entry<UUID, Colonist> entry : colony.colonists().entrySet()) {
            if (entry.getValue().role() == Role.GUARD) {
                guards.add(entry.getKey());
            }
        }
        guards.sort(UUID::compareTo);
        return guards;
    }
}
