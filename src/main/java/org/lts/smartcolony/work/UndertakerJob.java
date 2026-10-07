package org.lts.smartcolony.work;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import org.lts.smartcolony.colony.Graveyard;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.util.WorldUtil;

public final class UndertakerJob extends Job {
    private String deathId = "";
    private boolean arrived;
    private int stuck;

    public UndertakerJob(Colonist colonist) {
        super(colonist);
        depositThreshold = 8;
    }

    @Override
    public Role role() {
        return Role.UNDERTAKER;
    }

    @Override
    public boolean wantsControl(ServerLevel level, Villager villager) {
        return hasCargo(villager) || colony().workTime(level) && colony().graveyard().hasWork(level);
    }

    @Override
    public void onRelease(ServerLevel level, Villager villager) {
        super.onRelease(level, villager);
        arrived = false;
        stuck = 0;
    }

    @Override
    public void tick(ServerLevel level, Villager villager) {
        if (wait > 0) {
            wait--;
            return;
        }
        switch (phase) {
            case "start" -> start(level, villager);
            case "fetch" -> fetch(level, villager);
            case "deposit" -> deposit(level, villager);
            case "supply" -> supply(level, villager);
            case "bury" -> bury(level, villager);
            default -> phase = "start";
        }
    }

    private void start(ServerLevel level, Villager villager) {
        arrived = false;
        stuck = 0;
        if (hasCargo(villager)) {
            phase = "deposit";
            return;
        }
        if (!colony().workTime(level)) {
            wait = 100;
            return;
        }
        Graveyard graveyard = colony().graveyard();
        ColonyData.Death pending = graveyard.nextPending(level);
        if (pending != null) {
            deathId = pending.id;
            status = "collecting belongings of " + pending.name;
            phase = "fetch";
            return;
        }
        ColonyData.Death next = graveyard.nextToBury();
        Graveyard.Site site = graveyard.nextSite();
        if (next != null && site != null && graveyard.affordable(level, site.building().wood)) {
            deathId = next.id;
            status = "burying " + next.name;
            phase = "supply";
            return;
        }
        status = "nothing to bury";
        idle(level, 600);
    }

    private void fetch(ServerLevel level, Villager villager) {
        Graveyard graveyard = colony().graveyard();
        ColonyData.Death death = graveyard.find(deathId);
        if (death == null || !"pending".equals(death.remains)) {
            phase = "start";
            return;
        }
        if (cargoFull(villager)) {
            phase = "deposit";
            arrived = false;
            return;
        }
        BlockPos spot = WorldUtil.bp(death.pos);
        if (!arrived) {
            Mover.Result result = move(level, villager, spot, 2.5);
            if (result == Mover.Result.FAILED) {
                giveUp(level, death);
            } else if (result == Mover.Result.ARRIVED) {
                arrived = true;
                stuck = 0;
            }
            return;
        }
        ItemEntity item = graveyard.nearestRemains(level, villager, death);
        if (item == null) {
            graveyard.finishRecovery(level, death);
            phase = "deposit";
            return;
        }
        if (villager.distanceToSqr(item) > 2.25) {
            Mover.Result result = move(level, villager, item.blockPosition(), 1.0);
            if (result == Mover.Result.FAILED || ++stuck > 400) {
                giveUp(level, death);
            }
            return;
        }
        int before = item.getItem().getCount();
        boolean whole = pickUp(villager, item);
        death.recovered += whole ? before : before - item.getItem().getCount();
        colony().markDirty();
        if (!whole) {
            phase = "deposit";
            arrived = false;
        }
    }

    private void giveUp(ServerLevel level, ColonyData.Death death) {
        death.tries++;
        arrived = false;
        stuck = 0;
        wait = 60;
        if (death.tries >= 4) {
            if (death.recovered > 0) {
                colony().graveyard().finishRecovery(level, death);
            } else {
                colony().graveyard().markLost(level, death);
            }
            phase = "start";
        }
    }

    private void deposit(ServerLevel level, Villager villager) {
        if (!atDepot(level, villager)) {
            if (goDepot(level, villager) == Mover.Result.FAILED) {
                wait = 100;
            }
            return;
        }
        if (!depositAll(level, villager)) {
            wait = 200;
        }
        phase = "start";
    }

    private void supply(ServerLevel level, Villager villager) {
        Graveyard graveyard = colony().graveyard();
        ColonyData.Death death = graveyard.find(deathId);
        Graveyard.Site site = graveyard.nextSite();
        if (death == null || death.buried || site == null) {
            phase = "start";
            return;
        }
        if (!atDepot(level, villager)) {
            if (goDepot(level, villager) == Mover.Result.FAILED) {
                wait = 100;
                phase = "start";
            }
            return;
        }
        String wood = site.building().wood;
        boolean stone = supply(level, villager, Ids.mc("cobblestone"), 1, 1) >= 1;
        boolean sign = supply(level, villager, Ids.mc(wood + "_sign"), 1, 1) >= 1;
        if (!stone || !sign) {
            if (!sign) {
                colony().request(Ids.mc(wood + "_sign"));
            }
            status = "no materials for a grave";
            idle(level, 1200);
            phase = "start";
            return;
        }
        supply(level, villager, Ids.mc("dirt"), 2, 2);
        var stock = colony().warehouse().stock(level);
        for (String flower : Graveyard.FLOWERS) {
            if (Inv.count(villager, Ids.mc(flower)) > 0) {
                break;
            }
            if (stock.get(Ids.mc(flower)) > 0) {
                supply(level, villager, Ids.mc(flower), 1, 1);
                break;
            }
        }
        phase = "bury";
    }

    private void bury(ServerLevel level, Villager villager) {
        Graveyard graveyard = colony().graveyard();
        ColonyData.Death death = graveyard.find(deathId);
        Graveyard.Site site = graveyard.nextSite();
        if (death == null || death.buried || site == null) {
            phase = "start";
            return;
        }
        Mover.Result result = move(level, villager, site.stand(), 1.5);
        if (result == Mover.Result.FAILED) {
            if (++stuck >= 3) {
                graveyard.blockSite(site);
                phase = "start";
            }
            wait = 40;
            return;
        }
        if (result != Mover.Result.ARRIVED) {
            return;
        }
        if (graveyard.bury(level, villager, site, death)) {
            gainXp(level, villager, 4);
        }
        phase = "start";
    }
}
