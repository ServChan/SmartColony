package org.lts.smartcolony.colony;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.lts.smartcolony.SmartColonyConfig;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Role;
import org.lts.smartcolony.core.Schedule;
import org.lts.smartcolony.util.WorldUtil;

public final class Daily {
    private static final List<String> LUNCH_FOOD = List.of("bread", "baked_potato", "cooked_beef", "cooked_porkchop", "cooked_mutton",
            "cooked_chicken", "cooked_cod", "cooked_salmon", "cooked_rabbit", "carrot", "apple", "beetroot", "melon_slice", "potato");

    private final Colony colony;
    private long lunchDay = -1;
    private long fedDay = -1;
    private long restAnnounced = -1;

    public Daily(Colony colony) {
        this.colony = colony;
    }

    public Schedule.Slot slot(ServerLevel level) {
        SmartColonyConfig config = SmartColonyConfig.get();
        return Schedule.slot(level.getDefaultClockTime(), config.workStart, config.workEnd, config.lunchBreak, config.eveningGathering,
                config.restDayEvery);
    }

    public boolean restDay(ServerLevel level) {
        return Schedule.restDay(level.getDefaultClockTime(), SmartColonyConfig.get().restDayEvery);
    }

    public boolean fed(ServerLevel level) {
        return fedDay == Schedule.day(level.getDefaultClockTime());
    }

    public void tick(ServerLevel level, long ticks) {
        if (ticks % 40 != 17) {
            return;
        }
        long time = level.getDefaultClockTime();
        long day = Schedule.day(time);
        Schedule.Slot slot = slot(level);
        if (slot == Schedule.Slot.LUNCH && lunchDay != day) {
            lunchDay = day;
            serveLunch(level, day);
        }
        if ((slot == Schedule.Slot.OFF || slot == Schedule.Slot.REST) && restDay(level) && restAnnounced != day
                && Schedule.hour(time) >= SmartColonyConfig.get().workStart && colony.colonists().size() >= 4) {
            restAnnounced = day;
            colony.journal(level, "day off: the village rests");
            colony.notify(level, Component.translatable("smartcolony.chat.rest_day"));
        }
    }

    private void serveLunch(ServerLevel level, long day) {
        int adults = colony.adultCount(level);
        int eaters = 0;
        for (var entry : colony.colonists().entrySet()) {
            if (!Schedule.skipsBreaks(entry.getValue().role())) {
                eaters++;
            }
        }
        if (eaters == 0) {
            return;
        }
        if (!Schedule.canAffordLunch(colony.foodPoints(level), adults)) {
            colony.journal(level, "no lunch today: food is short, everyone keeps working");
            colony.data().bump("lunch_skipped", 1);
            return;
        }
        int need = Schedule.lunchFood(eaters);
        int eaten = 0;
        for (String food : LUNCH_FOOD) {
            if (eaten >= need) {
                break;
            }
            eaten += colony.warehouse().take(level, Ids.mc(food), need - eaten);
        }
        if (eaten > 0) {
            fedDay = day;
            colony.data().bump("lunches", 1);
            colony.data().bump("lunch_food", eaten);
            colony.markDirty();
        }
    }

    public BlockPos lunchSpot(ServerLevel level, UUID uuid) {
        if (colony.tavern().built()) {
            return colony.tavern().festivalSpot(level, uuid);
        }
        if (colony.hall().restOpen()) {
            BlockPos spot = colony.hall().restSpot(uuid);
            if (spot != null) {
                return spot;
            }
        }
        return ring(level, colony.depot(), uuid, 3.0);
    }

    public BlockPos eveningSpot(ServerLevel level, UUID uuid) {
        BlockPos hub = colony.construction().paths().hub();
        return ring(level, hub != null ? hub : colony.center(), uuid, 3.5);
    }

    public BlockPos eveningLook() {
        BlockPos hub = colony.construction().paths().hub();
        return hub != null ? hub : colony.center();
    }

    public BlockPos restSpot(ServerLevel level, UUID uuid) {
        for (String kind : List.of("chapel", "market", "tavern")) {
            for (ColonyData.Building building : colony.data().buildings) {
                if (kind.equals(building.kind)) {
                    return ring(level, WorldUtil.bp(building.door), uuid, 3.0);
                }
            }
        }
        return eveningSpot(level, uuid);
    }

    static BlockPos ring(ServerLevel level, BlockPos center, UUID uuid, double base) {
        double angle = (uuid.getLeastSignificantBits() & 0xFFFF) / 65536.0 * Math.PI * 2.0;
        double radius = base + (uuid.getMostSignificantBits() & 3);
        return WorldUtil.surface(level, center.getX() + (int) Math.round(Math.cos(angle) * radius),
                center.getZ() + (int) Math.round(Math.sin(angle) * radius));
    }

    public static boolean joins(Role role, boolean baby) {
        return !baby && role != Role.GUARD;
    }
}
