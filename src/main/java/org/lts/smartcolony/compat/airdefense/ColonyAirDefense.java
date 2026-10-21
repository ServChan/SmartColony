package org.lts.smartcolony.compat.airdefense;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.lts.smartcolony.colony.Colony;
import org.lts.smartcolony.core.Recipe;
import org.lts.smartcolony.core.RecipeBook;
import org.lts.smartcolony.core.Stock;
import org.lts.smartcolony.util.ItemIds;

public final class ColonyAirDefense {
    private static final int SCAN_INTERVAL = 40;
    private static final int PRESENCE_INTERVAL = 200;
    private static final int SUPPLY_INTERVAL = 200;
    private static final long AIR_RAID_HOLD = 300L;
    private static final long ANNOUNCE_GAP = 6000L;
    private static final int THREAT_MARGIN = 32;
    private static final int INSTALLATION_MARGIN = 24;
    private static final int MAX_BATCH = 32;
    private static final Map<String, Integer> RESERVE = Map.of(
            "minecraft:iron_ingot", 48,
            "minecraft:gunpowder", 16,
            "minecraft:copper_ingot", 16,
            "minecraft:redstone", 8,
            "minecraft:glowstone_dust", 4,
            "minecraft:white_wool", 4);

    private static final class State {
        private boolean present;
        private long presenceCheckedAt = Long.MIN_VALUE / 2;
        private long airRaidUntil;
        private long lastAnnounce = Long.MIN_VALUE / 2;
        private int threats;
    }

    private static final Map<String, State> STATES = new HashMap<>();

    private ColonyAirDefense() {
    }

    public static void init() {
        if (!AirDefenseLink.available()) {
            return;
        }
        for (Map<String, Integer> entry : AirDefenseLink.recipes()) {
            Map<String, Integer> inputs = new LinkedHashMap<>(entry);
            String output = null;
            int count = 1;
            for (Map.Entry<String, Integer> item : entry.entrySet()) {
                if (item.getKey().startsWith("=")) {
                    output = item.getKey().substring(1);
                    count = item.getValue();
                    inputs.remove(item.getKey());
                }
            }
            if (output != null) {
                RecipeBook.register(new Recipe(output, count, inputs, false));
            }
        }
    }

    public static void tick(Colony colony, ServerLevel level, long ticks) {
        if (!AirDefenseLink.available()) {
            return;
        }
        State state = STATES.computeIfAbsent(colony.id(), id -> new State());
        long now = level.getGameTime();
        BlockPos center = colony.center();
        int reach = colony.data().radius + INSTALLATION_MARGIN;
        if (now - state.presenceCheckedAt >= PRESENCE_INTERVAL) {
            state.presenceCheckedAt = now;
            state.present = AirDefenseLink.present(level, center, reach);
        }
        if (!state.present) {
            state.threats = 0;
            return;
        }
        long phase = Math.floorMod(colony.id().hashCode(), SCAN_INTERVAL);
        if (ticks % SCAN_INTERVAL == phase) {
            watchTheSky(colony, level, state, center, reach, now);
        }
        if (ticks % SUPPLY_INTERVAL == phase) {
            supply(colony, level, center, reach);
        }
    }

    public static boolean airRaid(Colony colony, ServerLevel level) {
        State state = STATES.get(colony.id());
        return state != null && level.getGameTime() < state.airRaidUntil;
    }

    public static int threats(Colony colony) {
        State state = STATES.get(colony.id());
        return state == null ? 0 : state.threats;
    }

    public static boolean installed(Colony colony) {
        State state = STATES.get(colony.id());
        return state != null && state.present;
    }

    public static void forget(String colonyId) {
        STATES.remove(colonyId);
    }

    public static void clear() {
        STATES.clear();
    }

    private static void watchTheSky(Colony colony, ServerLevel level, State state, BlockPos center, int reach, long now) {
        int flying = AirDefenseLink.airThreats(level, center, colony.data().radius + THREAT_MARGIN);
        state.threats = AirDefenseLink.dangerousAirThreats(level, center, colony.data().radius);
        if (flying > 0 && state.threats == 0) {
            AirDefenseLink.soundSirens(level, center, reach);
        }
        if (state.threats > 0) {
            state.airRaidUntil = now + AIR_RAID_HOLD;
            if (now - state.lastAnnounce > ANNOUNCE_GAP) {
                state.lastAnnounce = now;
                colony.journal(level, "air raid: radar tracks " + state.threats + " flying targets over the village");
                colony.notify(level, Component.translatable("smartcolony.chat.air_alarm", state.threats));
                colony.data().bump("air_raids", 1);
            }
        }
        if (now < state.airRaidUntil || colony.defense().severe(level)) {
            AirDefenseLink.soundSirens(level, center, reach);
        }
    }

    static boolean canSpare(Stock stock, String output, int amount) {
        List<Recipe> recipes = RecipeBook.recipesFor(output);
        if (recipes.isEmpty()) {
            return false;
        }
        Recipe recipe = recipes.getFirst();
        int batches = (amount + recipe.count() - 1) / recipe.count();
        for (Map.Entry<String, Integer> input : recipe.inputs().entrySet()) {
            String id = input.getKey().equals("minecraft:iron_nugget") ? "minecraft:iron_ingot" : input.getKey();
            int need = input.getKey().equals("minecraft:iron_nugget") ? (input.getValue() * batches + 8) / 9 : input.getValue() * batches;
            if (RESERVE.containsKey(id) && stock.get(id) < need + RESERVE.get(id)) {
                return false;
            }
        }
        return true;
    }

    private static void supply(Colony colony, ServerLevel level, BlockPos center, int reach) {
        Map<String, Integer> needs = AirDefenseLink.ammoNeeds(level, center, reach);
        if (needs.isEmpty()) {
            return;
        }
        StringBuilder delivered = new StringBuilder();
        int total = 0;
        for (Map.Entry<String, Integer> need : needs.entrySet()) {
            String id = need.getKey();
            int batch = Math.min(MAX_BATCH, need.getValue());
            if (colony.warehouse().stock(level).get(id) < batch && canSpare(colony.warehouse().stock(level), id, batch)) {
                colony.craft(level, id, batch);
            }
            List<ItemStack> taken = colony.warehouse().extract(level, stack -> id.equals(ItemIds.id(stack)), batch);
            int moved = 0;
            for (ItemStack stack : taken) {
                moved += AirDefenseLink.deliver(level, center, reach, stack);
                if (!stack.isEmpty()) {
                    ItemStack rest = colony.warehouse().insert(level, stack);
                    if (!rest.isEmpty()) {
                        Block.popResource(level, center.above(), rest);
                    }
                }
            }
            if (moved > 0) {
                total += moved;
                if (!delivered.isEmpty()) {
                    delivered.append(", ");
                }
                delivered.append(moved).append(' ').append(id.substring(id.indexOf(':') + 1));
            }
        }
        if (total > 0) {
            colony.data().bump("air_defense_supplied", total);
            colony.journal(level, "air defense: carried " + delivered + " from the storehouse to the batteries");
            colony.markDirty();
        }
    }
}
