package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CoreLogicTest {
    private static String mc(String path) {
        return Ids.mc(path);
    }

    @Test
    void classifiesItemsIntoFiveStorages() {
        assertEquals(Category.WOOD, ItemClassifier.classify(mc("oak_log")));
        assertEquals(Category.WOOD, ItemClassifier.classify(mc("birch_planks")));
        assertEquals(Category.WOOD, ItemClassifier.classify(mc("stick")));
        assertEquals(Category.WOOD, ItemClassifier.classify(mc("cherry_sapling")));
        assertEquals(Category.MINING, ItemClassifier.classify(mc("cobblestone")));
        assertEquals(Category.MINING, ItemClassifier.classify(mc("raw_iron")));
        assertEquals(Category.MINING, ItemClassifier.classify(mc("iron_ingot")));
        assertEquals(Category.MINING, ItemClassifier.classify(mc("cobblestone_stairs")));
        assertEquals(Category.FOOD, ItemClassifier.classify(mc("wheat")));
        assertEquals(Category.FOOD, ItemClassifier.classify(mc("cooked_cod")));
        assertEquals(Category.FOOD, ItemClassifier.classify(mc("beef")));
        assertEquals(Category.ANIMAL, ItemClassifier.classify(mc("leather")));
        assertEquals(Category.ANIMAL, ItemClassifier.classify(mc("red_wool")));
        assertEquals(Category.ANIMAL, ItemClassifier.classify(mc("egg")));
        assertEquals(Category.ARSENAL, ItemClassifier.classify(mc("iron_sword")));
        assertEquals(Category.ARSENAL, ItemClassifier.classify(mc("stone_axe")));
        assertEquals(Category.ARSENAL, ItemClassifier.classify(mc("bow")));
        assertEquals(Category.ARSENAL, ItemClassifier.classify(mc("iron_chestplate")));
        assertEquals(Category.ARSENAL, ItemClassifier.classify(mc("shield")));
    }

    @Test
    void woodHelpersResolveTypes() {
        assertEquals("dark_oak", Ids.woodOf(mc("dark_oak_log")));
        assertEquals("oak", Ids.woodOf(mc("stripped_oak_wood")));
        assertEquals("pale_oak", Ids.woodOf(mc("pale_oak_planks")));
        assertEquals(mc("crimson_planks"), Ids.planksForLog(mc("crimson_stem")));
    }

    @Test
    void planksAndSticksComeFromLogs() {
        Stock stock = new Stock(Map.of(mc("birch_log"), 2));
        Optional<CraftPlanner.Plan> plan = CraftPlanner.plan(stock, mc("wooden_pickaxe"), 1);
        assertTrue(plan.isPresent());
        Map<String, Integer> delta = plan.get().delta();
        assertEquals(-2, delta.get(mc("birch_log")));
        assertEquals(1, delta.get(mc("wooden_pickaxe")));
        assertEquals(5, plan.get().after().get(mc("birch_planks")) + plan.get().after().get(mc("stick")));
    }

    @Test
    void stonePickaxeNeedsCobbleAndSticks() {
        Stock stock = new Stock(Map.of(mc("cobblestone"), 3, mc("oak_planks"), 2));
        Optional<CraftPlanner.Plan> plan = CraftPlanner.plan(stock, mc("stone_pickaxe"), 1);
        assertTrue(plan.isPresent());
        assertEquals(0, plan.get().after().get(mc("cobblestone")));
        assertEquals(2, plan.get().after().get(mc("stick")));
        assertFalse(CraftPlanner.plan(new Stock(Map.of(mc("cobblestone"), 2, mc("oak_planks"), 2)), mc("stone_pickaxe"), 1).isPresent());
    }

    @Test
    void smeltingNeedsFuel() {
        Stock noFuel = new Stock(Map.of(mc("raw_iron"), 3)).grant(Set.of(Tech.FORGE));
        assertFalse(CraftPlanner.plan(noFuel, mc("iron_ingot"), 3).isPresent());
        Stock withCoal = new Stock(Map.of(mc("raw_iron"), 3, mc("coal"), 1)).grant(Set.of(Tech.FORGE));
        CraftPlanner.Plan plan = CraftPlanner.plan(withCoal, mc("iron_ingot"), 3).orElseThrow();
        assertEquals(-1, plan.delta().get(mc("coal")));
        assertEquals(-3, plan.delta().get(mc("raw_iron")));
    }

    @Test
    void rawMetalNeedsAForge() {
        Map<String, Integer> raw = Map.of(mc("raw_iron"), 3, mc("raw_copper"), 3, mc("raw_gold"), 3, mc("coal"), 5);
        Stock noForge = new Stock(raw);
        assertFalse(CraftPlanner.plan(noForge, mc("iron_ingot"), 1).isPresent());
        assertFalse(CraftPlanner.plan(noForge, mc("copper_ingot"), 1).isPresent());
        assertFalse(CraftPlanner.plan(noForge, mc("gold_ingot"), 1).isPresent());
        assertFalse(CraftPlanner.plan(noForge, mc("iron_pickaxe"), 1).isPresent());
        Stock forged = new Stock(raw).grant(Set.of(Tech.FORGE));
        assertTrue(CraftPlanner.plan(forged, mc("iron_ingot"), 3).isPresent());
        assertTrue(CraftPlanner.plan(forged, mc("copper_ingot"), 1).isPresent());
        assertTrue(CraftPlanner.plan(forged, mc("gold_ingot"), 1).isPresent());
        assertTrue(CraftPlanner.plan(forged.copy(), mc("iron_ingot"), 1).isPresent());
        assertTrue(forged.copy().has(Tech.FORGE));
    }

    @Test
    void otherSmeltingAndIronBlocksNeedNoForge() {
        Stock stock = new Stock(Map.of(mc("sand"), 2, mc("coal"), 1, mc("iron_block"), 1));
        assertTrue(CraftPlanner.plan(stock, mc("glass"), 2).isPresent());
        assertTrue(CraftPlanner.plan(stock, mc("iron_ingot"), 5).isPresent());
    }

    @Test
    void forgeCapabilityComesFromTheBlacksmithBlueprint() {
        assertTrue(Tech.capabilities(List.of("house_small")).isEmpty());
        assertTrue(Tech.capabilities(List.of("house_small", "blacksmith")).contains(Tech.FORGE));
    }

    @Test
    void smithSmeltsRawIronOnlyWithAForge() {
        EnumMap<Role, Integer> roles = new EnumMap<>(Role.class);
        roles.put(Role.GUARD, 2);
        Stock stock = new Stock(Map.of(mc("raw_iron"), 12, mc("coal"), 4, mc("stick"), 6));
        assertTrue(CrafterNeeds.wants(Role.SMITH, new CrafterNeeds.Context(stock, roles, 12, 2)).stream()
                .noneMatch(w -> w.smelt()));
        stock.grant(Set.of(Tech.FORGE));
        var wants = CrafterNeeds.wants(Role.SMITH, new CrafterNeeds.Context(stock, roles, 12, 2));
        assertEquals(mc("iron_ingot"), wants.get(0).item());
        assertTrue(wants.get(0).smelt());
        assertEquals(8, wants.get(0).count());
    }

    @Test
    void fletchingTableAndSmokerCanBeCrafted() {
        Stock stock = new Stock(Map.of(mc("flint"), 2, mc("oak_planks"), 4, mc("oak_log"), 4, mc("cobblestone"), 8));
        assertTrue(CraftPlanner.plan(stock, mc("fletching_table"), 1).isPresent());
        assertTrue(CraftPlanner.plan(stock, mc("smoker"), 1).isPresent());
        assertFalse(CraftPlanner.plan(new Stock(Map.of(mc("oak_planks"), 4)), mc("fletching_table"), 1).isPresent());
    }

    @Test
    void torchesUseCharcoalFromLogsWhenNoCoal() {
        Stock stock = new Stock(Map.of(mc("oak_log"), 4));
        CraftPlanner.Plan plan = CraftPlanner.plan(stock, mc("torch"), 4).orElseThrow();
        assertTrue(plan.steps().stream().anyMatch(step -> step.output().equals(mc("charcoal"))));
        assertEquals(4, plan.after().get(mc("torch")));
    }

    @Test
    void bedTakesMatchingWool() {
        Stock stock = new Stock(Map.of(mc("white_wool"), 3, mc("spruce_planks"), 3));
        assertTrue(CraftPlanner.plan(stock, mc("white_bed"), 1).isPresent());
        assertFalse(CraftPlanner.plan(stock, mc("red_bed"), 1).isPresent());
    }

    @Test
    void failedPlanLeavesStockUntouched() {
        Stock stock = new Stock(Map.of(mc("oak_log"), 1));
        assertFalse(CraftPlanner.plan(stock, mc("iron_pickaxe"), 1).isPresent());
        assertEquals(1, stock.get(mc("oak_log")));
    }

    @Test
    void theftIsValuedAgainstPayment() {
        Map<String, Integer> before = Map.of(mc("iron_ingot"), 10, mc("oak_log"), 5);
        Map<String, Integer> after = Map.of(mc("iron_ingot"), 2, mc("oak_log"), 5);
        Reputation.Exchange theft = Reputation.exchange(before, after);
        assertTrue(theft.isTheft());
        assertEquals(2000, theft.takenValue());
        Map<String, Integer> paid = new LinkedHashMap<>(after);
        paid.put(mc("emerald"), 2);
        Reputation.Exchange purchase = Reputation.exchange(before, paid);
        assertFalse(purchase.isTheft());
        assertEquals(20, Reputation.theftPenalty(theft.net()));
        assertEquals(Reputation.Status.ENEMY, Reputation.status(-60));
        assertEquals(Reputation.Status.GUEST, Reputation.status(0));
        assertEquals(Reputation.Status.ALLY, Reputation.status(45));
    }

    @Test
    void minePlanDescendsThenBranches() {
        MinePlan plan = new MinePlan(new Pos(0, 70, 0), 1, 0, MinePlan.depthFor(70, 12));
        assertEquals(58, plan.depth());
        assertEquals(new Pos(1, 69, 0), plan.cell(0).feet());
        assertEquals(MinePlan.Kind.STAIR, plan.cell(0).kind());
        assertEquals(new Pos(58, 12, 0), plan.bottom());
        MinePlan.Cell firstMain = plan.cell(58);
        assertEquals(MinePlan.Kind.MAIN, firstMain.kind());
        assertEquals(new Pos(59, 12, 0), firstMain.feet());
        MinePlan.Cell firstBranch = plan.cell(58 + MinePlan.SPACING);
        assertEquals(MinePlan.Kind.BRANCH, firstBranch.kind());
        assertEquals(new Pos(62, 12, -1), firstBranch.feet());
        assertEquals(plan.main(4), plan.standFor(58 + MinePlan.SPACING));
        MinePlan.Cell otherSide = plan.cell(58 + MinePlan.SPACING + MinePlan.BRANCH_LENGTH);
        assertEquals(new Pos(62, 12, 1), otherSide.feet());
        Set<Pos> seen = new HashSet<>();
        for (int i = 0; i < plan.size(); i++) {
            assertTrue(seen.add(plan.cell(i).feet()), "cell " + i + " repeats");
        }
    }

    @Test
    void mineRouteStartsAtEntranceAndEndsAtStand() {
        MinePlan plan = new MinePlan(new Pos(10, 64, 10), 0, -1, 30);
        for (int index : new int[]{0, 5, 29, 30, 40, 100, plan.size() - 1}) {
            List<Pos> route = plan.routeTo(index);
            assertEquals(plan.entrance(), route.getFirst());
            assertEquals(plan.standFor(index), route.getLast());
            for (int i = 1; i < route.size(); i++) {
                assertTrue(route.get(i - 1).distManhattan(route.get(i)) <= 14, "gap at " + index);
            }
        }
    }

    @Test
    void blueprintRotationAndMaterials() {
        String json = """
                {"id":"t","kind":"house","beds":1,"door":[1,0],
                 "palette":{"C":"minecraft:cobblestone","P":"minecraft:$wood_planks","D":"minecraft:$wood_door[half=lower,facing=north]",
                            "U":"minecraft:$wood_door[half=upper,facing=north]","B":"minecraft:red_bed[part=foot,facing=south]",
                            "H":"minecraft:red_bed[part=head,facing=south]","T":"minecraft:wall_torch[facing=north]"},
                 "layers":[["CCC","CCC"],["PDP","BHT"],["PUP","..."]]}
                """;
        Blueprint blueprint = Blueprint.parse(json);
        Map<String, Integer> materials = blueprint.materials("spruce");
        assertEquals(6, materials.get(mc("cobblestone")));
        assertEquals(4, materials.get(mc("spruce_planks")));
        assertEquals(1, materials.get(mc("spruce_door")));
        assertEquals(1, materials.get(mc("red_bed")));
        assertEquals(1, materials.get(mc("torch")));
        List<Blueprint.Cell> cells = blueprint.cells("oak");
        assertEquals(mc("cobblestone"), cells.getFirst().state());
        assertTrue(Blueprint.isAttached(Blueprint.blockId(cells.getLast().state())));
        assertEquals(3, blueprint.airCells().size());
        int[] r1 = Blueprint.rotate(0, 0, 3, 2, 1);
        assertEquals(1, r1[0]);
        assertEquals(0, r1[1]);
        int[] r2 = Blueprint.rotate(0, 0, 3, 2, 2);
        assertEquals(2, r2[0]);
        assertEquals(1, r2[1]);
        assertEquals(1, Blueprint.rotationFacing(5, 1));
        assertEquals(2, Blueprint.rotationFacing(0, 4));
    }

    @Test
    void rolesBootstrapThenScale() {
        RoleAllocator.Needs few = new RoleAllocator.Needs(2, 0, 0, 0, 0, 0, 0, false, Set.of(), 0, false, false, false);
        EnumMap<Role, Integer> two = RoleAllocator.desired(few);
        assertEquals(1, two.get(Role.LUMBERJACK));
        assertEquals(1, two.get(Role.FARMER));
        assertEquals(0, two.get(Role.GUARD));
        RoleAllocator.Needs many = new RoleAllocator.Needs(10, 300, 0, 0, 500, 0, 1, true,
                Set.of(Role.MINER, Role.FISHERMAN, Role.GUARD), 0, false, false, false);
        EnumMap<Role, Integer> ten = RoleAllocator.desired(many);
        assertEquals(10, ten.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(2, ten.get(Role.GUARD));
        assertEquals(1, ten.get(Role.BUILDER));
        assertTrue(ten.get(Role.MINER) >= 1);
        assertTrue(ten.get(Role.HERDER) <= 1);
    }

    @Test
    void craftersAreAllocatedOnlyWhenReadyAndLargeEnough() {
        Set<Role> gear = Set.of(Role.MINER, Role.FISHERMAN, Role.GUARD);
        RoleAllocator.Needs none = new RoleAllocator.Needs(30, 300, 300, 10, 500, 0, 0, true, gear, 0, false, false, false);
        assertEquals(0, RoleAllocator.desired(none).get(Role.FLETCHER));
        RoleAllocator.Needs all = new RoleAllocator.Needs(30, 300, 300, 10, 500, 0, 0, true, gear, 0, true, true, true);
        EnumMap<Role, Integer> crew = RoleAllocator.desired(all);
        assertEquals(1, crew.get(Role.FLETCHER));
        assertEquals(2, crew.get(Role.SMITH));
        assertEquals(1, crew.get(Role.COOK));
        assertEquals(30, crew.values().stream().mapToInt(Integer::intValue).sum());
        RoleAllocator.Needs small = new RoleAllocator.Needs(8, 300, 300, 10, 500, 0, 0, true, gear, 0, true, true, true);
        EnumMap<Role, Integer> hamlet = RoleAllocator.desired(small);
        assertEquals(1, hamlet.get(Role.FLETCHER));
        assertEquals(0, hamlet.get(Role.SMITH));
        assertEquals(0, hamlet.get(Role.COOK));
        assertEquals(8, hamlet.values().stream().mapToInt(Integer::intValue).sum());
        RoleAllocator.Needs army = new RoleAllocator.Needs(60, 300, 300, 10, 500, 0, 0, true, gear, 3.0, true, false, false);
        assertEquals(2, RoleAllocator.desired(army).get(Role.FLETCHER));
    }

    @Test
    void crafterWantsFollowColonyNeeds() {
        Stock stock = new Stock();
        stock.add(Ids.mc("stick"), 20);
        stock.add(Ids.mc("string"), 6);
        stock.add(Ids.mc("flint"), 4);
        stock.add(Ids.mc("feather"), 4);
        EnumMap<Role, Integer> roles = new EnumMap<>(Role.class);
        roles.put(Role.GUARD, 5);
        CrafterNeeds.Context ctx = new CrafterNeeds.Context(stock, roles, 20, 0);
        var wants = CrafterNeeds.wants(Role.FLETCHER, ctx);
        assertEquals(Ids.mc("bow"), wants.get(0).item());
        assertEquals(3, wants.get(0).count());
        assertEquals(Ids.mc("arrow"), wants.get(1).item());
        assertTrue(CrafterNeeds.pending(Role.FLETCHER, ctx));
        assertTrue(CrafterNeeds.wants(Role.COOK, ctx).isEmpty());
        Stock cooking = new Stock();
        cooking.add(Ids.mc("beef"), 3);
        cooking.add(Ids.mc("wheat"), 9);
        cooking.add(Ids.mc("coal"), 5);
        var cookWants = CrafterNeeds.wants(Role.COOK, new CrafterNeeds.Context(cooking, roles, 10, 0));
        assertEquals(Ids.mc("cooked_beef"), cookWants.get(0).item());
        assertTrue(cookWants.get(0).smelt());
        assertEquals(Ids.mc("bread"), cookWants.get(1).item());
        assertEquals(3, cookWants.get(1).count());
        Stock smithing = new Stock();
        smithing.add(Ids.mc("cobblestone"), 20);
        smithing.add(Ids.mc("stick"), 10);
        roles.put(Role.MINER, 2);
        roles.put(Role.LUMBERJACK, 2);
        var tools = CrafterNeeds.wants(Role.SMITH, new CrafterNeeds.Context(smithing, roles, 20, 2));
        assertEquals(Ids.mc("stone_sword"), tools.get(0).item());
        assertEquals(2, tools.get(0).count());
        assertTrue(tools.stream().anyMatch(w -> w.item().equals(Ids.mc("stone_pickaxe"))));
        assertTrue(tools.stream().anyMatch(w -> w.item().equals(Ids.mc("stone_axe"))));
    }

    @Test
    void vanillaProfessionsMapToCrafters() {
        assertEquals(Role.FLETCHER, Role.affinity("fletcher"));
        assertEquals(Role.SMITH, Role.affinity("toolsmith"));
        assertEquals(Role.COOK, Role.affinity("butcher"));
    }

    @Test
    void advisorTargetsKeepTheHeadcount() {
        EnumMap<Role, Integer> base = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            base.put(role, 0);
        }
        base.put(Role.FARMER, 10);
        base.put(Role.LUMBERJACK, 6);
        base.put(Role.GUARD, 4);
        EnumMap<Role, Integer> steered = RoleAllocator.withTargets(base, Map.of(Role.BUILDER, 5, Role.MINER, 3), 20);
        assertEquals(20, steered.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(5, steered.get(Role.BUILDER));
        assertEquals(3, steered.get(Role.MINER));
        assertTrue(steered.get(Role.FARMER) > steered.get(Role.GUARD));
        EnumMap<Role, Integer> greedy = RoleAllocator.withTargets(base, Map.of(Role.BUILDER, 50), 20);
        assertEquals(10, greedy.get(Role.BUILDER));
        assertEquals(20, greedy.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void longBuildQueuesCallMoreBuilders() {
        RoleAllocator.Needs small = new RoleAllocator.Needs(40, 300, 300, 10, 500, 0, 20, true, Set.of(Role.MINER), 0, false, false, false);
        assertEquals(2, RoleAllocator.desired(small).get(Role.BUILDER));
        RoleAllocator.Needs wall = new RoleAllocator.Needs(40, 300, 300, 10, 500, 0, 2000, true, Set.of(Role.MINER), 0, false, false, false);
        assertEquals(10, RoleAllocator.desired(wall).get(Role.BUILDER));
        assertEquals(40, RoleAllocator.desired(wall).values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(1, RoleAllocator.desired(wall).get(Role.GUARD));
        RoleAllocator.Needs site = new RoleAllocator.Needs(40, 300, 300, 10, 500, 0, 250, true, Set.of(Role.MINER, Role.GUARD), 0, false, false, false);
        assertEquals(4, RoleAllocator.desired(site).get(Role.BUILDER));
        assertEquals(10, RoleAllocator.desired(site).get(Role.FARMER));
        assertEquals(8, RoleAllocator.desired(site).get(Role.GUARD));
    }

    @Test
    void reassignmentPrefersIdleAndAffinity() {
        Map<String, Role> current = new LinkedHashMap<>();
        current.put("a", Role.IDLE);
        current.put("b", Role.LUMBERJACK);
        current.put("c", Role.LUMBERJACK);
        Map<String, Role> affinity = Map.of("a", Role.MINER, "b", Role.FARMER, "c", Role.LUMBERJACK);
        Map<Role, Integer> desired = new EnumMap<>(Role.class);
        desired.put(Role.LUMBERJACK, 1);
        desired.put(Role.FARMER, 1);
        desired.put(Role.MINER, 1);
        Map<String, Role> changes = RoleAllocator.reassign(current, affinity, Set.of(), desired, 1);
        assertEquals(2, changes.size());
        assertEquals(Role.FARMER, changes.get("b"));
        assertNotEquals(null, changes.get("a"));
        Map<String, Role> lockedChanges = RoleAllocator.reassign(current, affinity, Set.of("a", "b", "c"), desired, 1);
        assertTrue(lockedChanges.isEmpty());
    }

    @Test
    void friendlyFireSegmentCheck() {
        CombatMath.Box villager = new CombatMath.Box(4.7, 0, -0.3, 5.3, 1.95, 0.3);
        assertTrue(CombatMath.segmentHits(0, 1.5, 0, 10, 1.5, 0, villager));
        assertFalse(CombatMath.segmentHits(0, 1.5, 2, 10, 1.5, 2, villager));
        assertFalse(CombatMath.segmentHits(0, 1.5, 0, 4, 1.5, 0, villager));
        double[] away = CombatMath.retreatPoint(0, 0, 3, 0, 5);
        assertEquals(-5.0, away[0], 1.0E-9);
        assertEquals(CombatMath.Stance.RETREAT, CombatMath.stance(true, 10, 0.2f, 0.35));
        assertEquals(CombatMath.Stance.KITE, CombatMath.stance(true, 3, 1f, 0.35));
        assertEquals(CombatMath.Stance.SHOOT, CombatMath.stance(true, 10, 1f, 0.35));
        assertEquals(CombatMath.Stance.MELEE, CombatMath.stance(false, 2, 1f, 0.35));
    }

    @Test
    void threatSectorsAccumulateAndDecay() {
        double[] weights = new double[ThreatMap.SECTORS];
        ThreatMap.add(weights, 10, 0, 3);
        ThreatMap.add(weights, -10, 0, 1);
        int east = ThreatMap.sector(10, 0);
        assertEquals(east, ThreatMap.hottest(weights, 1).getFirst());
        double[] dir = ThreatMap.direction(east);
        assertTrue(dir[0] > 0.5);
        ThreatMap.decay(weights, 0.5);
        assertEquals(1.5, weights[east], 1.0E-9);
    }

    @Test
    void siteEvaluationRejectsCliffs() {
        int[][] flat = {{64, 64, 64}, {64, 65, 64}, {64, 64, 63}};
        boolean[][] none = new boolean[3][3];
        SiteEvaluator.Result ok = SiteEvaluator.evaluate(flat, none);
        assertTrue(ok.ok());
        assertEquals(64, ok.baseY());
        int[][] cliff = {{64, 64, 64}, {64, 64, 64}, {58, 58, 58}};
        assertFalse(SiteEvaluator.evaluate(cliff, none).ok());
        boolean[][] water = new boolean[3][3];
        water[1][1] = true;
        assertFalse(SiteEvaluator.evaluate(flat, water).ok());
    }

    @Test
    void colonyDataRoundTrips() {
        ColonyData data = new ColonyData();
        data.id = "c1";
        data.center = new Pos(5, 70, -3);
        data.storage.put(Category.FOOD, List.of(new Pos(1, 2, 3)));
        ColonyData.Member member = new ColonyData.Member();
        member.role = Role.MINER.key();
        data.members.put("uuid", member);
        data.mine = new ColonyData.Mine();
        data.mine.depth = 40;
        data.reputation.put("player", -20);
        data.threat[3] = 2.5;
        List<ColonyData> back = ColonyCodec.read(ColonyCodec.write(List.of(data)));
        assertEquals(1, back.size());
        ColonyData copy = back.getFirst();
        assertEquals(new Pos(5, 70, -3), copy.center);
        assertEquals(List.of(new Pos(1, 2, 3)), copy.storage.get(Category.FOOD));
        assertEquals(Role.MINER, copy.members.get("uuid").role());
        assertEquals(40, copy.mine.depth);
        assertEquals(-20, copy.reputation.get("player"));
        assertEquals(2.5, copy.threat[3], 1.0E-9);
    }

    @Test
    void pricesReactToScarcity() {
        int base = PriceTable.base(mc("iron_ingot"));
        assertTrue(PriceTable.value(mc("iron_ingot"), 1, 32) > base);
        assertTrue(PriceTable.value(mc("iron_ingot"), 500, 32) < base);
        assertEquals(240, PriceTable.base(mc("acacia_log")));
    }
}
