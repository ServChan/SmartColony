package org.lts.smartcolony.compat.airdefense;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lts.smartcolony.core.Recipe;
import org.lts.smartcolony.core.RecipeBook;
import org.lts.smartcolony.core.Stock;

class AmmoReserveTest {
    private static final String INTERCEPTOR = "airdefense:interceptor";
    private static final String SHELLS = "airdefense:flak_shells";

    @BeforeAll
    static void registerRecipes() {
        RecipeBook.register(new Recipe(INTERCEPTOR, 2, Map.of("minecraft:iron_ingot", 3, "minecraft:gunpowder", 2,
                "minecraft:redstone", 1, "minecraft:firework_rocket", 1), false));
        RecipeBook.register(new Recipe(SHELLS, 16, Map.of("minecraft:copper_ingot", 1, "minecraft:gunpowder", 1,
                "minecraft:iron_nugget", 1), false));
    }

    @Test
    void keepsIronAndGunpowderForTheColony() {
        Stock poor = new Stock(Map.of("minecraft:iron_ingot", 50, "minecraft:gunpowder", 20, "minecraft:redstone", 20));
        assertFalse(ColonyAirDefense.canSpare(poor, INTERCEPTOR, 16));
    }

    @Test
    void craftsWhenThereIsASurplus() {
        Stock rich = new Stock(Map.of("minecraft:iron_ingot", 120, "minecraft:gunpowder", 40, "minecraft:redstone", 30));
        assertTrue(ColonyAirDefense.canSpare(rich, INTERCEPTOR, 16));
    }

    @Test
    void nuggetsCountAgainstTheIronReserve() {
        Stock stock = new Stock(Map.of("minecraft:iron_ingot", 48, "minecraft:gunpowder", 40, "minecraft:copper_ingot", 40));
        assertFalse(ColonyAirDefense.canSpare(stock, SHELLS, 64));
        Stock more = new Stock(Map.of("minecraft:iron_ingot", 50, "minecraft:gunpowder", 40, "minecraft:copper_ingot", 40));
        assertTrue(ColonyAirDefense.canSpare(more, SHELLS, 64));
    }
}
