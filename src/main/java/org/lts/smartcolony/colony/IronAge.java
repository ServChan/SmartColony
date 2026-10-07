package org.lts.smartcolony.colony;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.core.Tech;

public final class IronAge {
    private final Colony colony;

    IronAge(Colony colony) {
        this.colony = colony;
    }

    public ColonyData.Building forge() {
        for (ColonyData.Building building : colony.data().buildings) {
            if (Tech.FORGE_BLUEPRINT.equals(building.blueprint)) {
                return building;
            }
        }
        return null;
    }

    public void tick(ServerLevel level) {
        ColonyData data = colony.data();
        ColonyData.Building forge = forge();
        if (forge == null) {
            return;
        }
        Stock stock = colony.warehouse().stock(level);
        int ingots = stock.get(Ids.mc("iron_ingot"));
        if (!Tech.ironAge(data.age)) {
            if (ingots > 0) {
                data.age = Tech.IRON_AGE;
                colony.warehouse().invalidate();
                colony.journal(level, "the iron age begins: the forge has produced iron");
                colony.notify(level, Component.translatable("smartcolony.chat.iron_age"));
                colony.markDirty();
            }
            return;
        }
        if (!data.forgeUpgraded && ingots >= Tech.FORGE_UPGRADE_INGOTS && colony.improvements().queueForgeUpgrade(level, forge)) {
            data.forgeUpgraded = true;
            colony.journal(level, "the forge gets an anvil and a smithing table");
            colony.markDirty();
        }
    }
}
