package org.lts.smartcolony.work;

import net.minecraft.server.level.ServerLevel;

public interface ProfessionAccess {
    void smartcolony$setProfessionOpen(boolean open);

    void smartcolony$updateTrades(ServerLevel level);
}
