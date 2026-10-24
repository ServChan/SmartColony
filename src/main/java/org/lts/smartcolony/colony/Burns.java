package org.lts.smartcolony.colony;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.FireDamage;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.util.WorldUtil;

public final class Burns {
    private final Colony colony;
    private final Map<Long, String> pending = new LinkedHashMap<>();
    private long lastBurn;

    Burns(Colony colony) {
        this.colony = colony;
    }

    public static void record(ServerLevel level, BlockPos pos, BlockState before) {
        Colony colony = ColonyManager.colonyAt(level, pos);
        if (colony != null) {
            colony.burns().burned(level, pos, before);
        }
    }

    private void burned(ServerLevel level, BlockPos pos, BlockState before) {
        String id = BuiltInRegistries.BLOCK.getKey(before.getBlock()).toString();
        if (!FireDamage.rebuildable(id) || pending.size() >= FireDamage.MAX_PENDING || !insideBuilding(level, pos)) {
            return;
        }
        pending.putIfAbsent(pos.asLong(), BlockStateParser.serialize(before));
        lastBurn = level.getGameTime();
    }

    private boolean insideBuilding(ServerLevel level, BlockPos pos) {
        Pos at = WorldUtil.pos(pos);
        for (ColonyData.Building building : colony.data().buildings) {
            if (building.contains(at, 1)) {
                return true;
            }
        }
        return level.structureManager().getStructureWithPieceAt(pos, StructureTags.VILLAGE).isValid();
    }

    void tick(ServerLevel level, long ticks) {
        if (ticks % 100 != 37 || pending.isEmpty() || !FireDamage.settled(level.getGameTime(), lastBurn)) {
            return;
        }
        int queued = 0;
        for (Map.Entry<Long, String> entry : pending.entrySet()) {
            BlockPos pos = BlockPos.of(entry.getKey());
            if (!WorldUtil.loaded(level, pos)) {
                continue;
            }
            BlockState now = level.getBlockState(pos);
            if (!WorldUtil.isAirLike(now) && !(now.getBlock() instanceof BaseFireBlock)) {
                continue;
            }
            Pos at = WorldUtil.pos(pos);
            if (colony.data().orders.stream().anyMatch(order -> order.pos.equals(at))) {
                continue;
            }
            colony.improvements().queueFirst(pos, entry.getValue(), Improvements.REPAIR);
            queued++;
        }
        pending.clear();
        if (queued > 0) {
            colony.data().bump("fire_repairs", queued);
            colony.journal(level, "fire destroyed " + queued + " blocks of houses, builders will put them back");
            colony.notify(level, Component.translatable("smartcolony.chat.fire_repair", queued));
        }
    }
}
