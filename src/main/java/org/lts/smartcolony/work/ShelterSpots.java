package org.lts.smartcolony.work;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Shelter;
import org.lts.smartcolony.util.WorldUtil;

final class ShelterSpots {
    private static final int REFRESH = 6000;
    private static final int CLAIM_TICKS = 600;
    private static final int PER_BUILDING = 24;
    private static final int MAX_SPAN = 24;

    private record Entry(long computedAt, List<BlockPos> spots) {
    }

    private record Claim(long spot, long until) {
    }

    private final Map<Long, Entry> buildings = new HashMap<>();
    private final Map<UUID, Claim> claims = new HashMap<>();
    private long computedTick = Long.MIN_VALUE;

    List<Shelter.Spot> candidates(ServerLevel level, Colony colony, long now) {
        List<Shelter.Spot> out = new ArrayList<>();
        Map<Long, Integer> load = new HashMap<>();
        claims.values().removeIf(claim -> claim.until() < now);
        for (Claim claim : claims.values()) {
            load.merge(claim.spot(), 1, Integer::sum);
        }
        for (ColonyData.Building building : colony.data().buildings) {
            long key = BlockPos.asLong(building.min.x(), building.min.y(), building.min.z());
            Entry entry = buildings.get(key);
            if ((entry == null || now - entry.computedAt() > REFRESH) && computedTick != now) {
                computedTick = now;
                entry = new Entry(now, scan(level, building));
                buildings.put(key, entry);
            }
            if (entry == null) {
                continue;
            }
            for (BlockPos pos : entry.spots()) {
                out.add(new Shelter.Spot(pos.getX(), pos.getY(), pos.getZ(), load.getOrDefault(pos.asLong(), 0)));
            }
        }
        return out;
    }

    void claim(UUID uuid, BlockPos spot, long now) {
        claims.put(uuid, new Claim(spot.asLong(), now + CLAIM_TICKS));
    }

    void release(UUID uuid) {
        claims.remove(uuid);
    }

    int load(BlockPos spot, long now) {
        int count = 0;
        for (Claim claim : claims.values()) {
            if (claim.spot() == spot.asLong() && claim.until() >= now) {
                count++;
            }
        }
        return count;
    }

    static boolean covered(ServerLevel level, BlockPos feet) {
        return WorldUtil.standable(level, feet) && !level.canSeeSky(feet) && !level.canSeeSky(feet.above());
    }

    static BlockPos nearbyCovered(ServerLevel level, BlockPos from, int radius, java.util.function.Predicate<BlockPos> skip) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos pos = from.offset(dx, dy, dz);
                    double distance = pos.distSqr(from);
                    if (distance >= bestDistance || skip.test(pos) || !covered(level, pos)) {
                        continue;
                    }
                    bestDistance = distance;
                    best = pos;
                }
            }
        }
        return best;
    }

    private static List<BlockPos> scan(ServerLevel level, ColonyData.Building building) {
        List<BlockPos> spots = new ArrayList<>();
        int minX = building.min.x();
        int minZ = building.min.z();
        int maxX = Math.min(building.max.x(), minX + MAX_SPAN);
        int maxZ = Math.min(building.max.z(), minZ + MAX_SPAN);
        for (int x = minX; x <= maxX; x += 2) {
            for (int z = minZ; z <= maxZ; z += 2) {
                for (int y = building.min.y(); y <= building.max.y() + 1; y++) {
                    BlockPos feet = new BlockPos(x, y, z);
                    if (WorldUtil.loaded(level, feet) && covered(level, feet) && Hazards.kind(level.getBlockState(feet.below())) == org.lts.smartcolony.core.Hazard.Kind.NONE) {
                        spots.add(feet);
                        break;
                    }
                }
                if (spots.size() >= PER_BUILDING) {
                    return spots;
                }
            }
        }
        return spots;
    }
}
