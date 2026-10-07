package org.lts.smartcolony.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TradePlannerTest {
    private static final String EMERALD = Ids.mc("emerald");
    private static final String LOG = Ids.mc("oak_log");
    private static final String COBBLE = Ids.mc("cobblestone");
    private static final String FLINT = Ids.mc("flint");
    private static final String COAL = Ids.mc("coal");

    private static int stack(String id) {
        return 64;
    }

    private static Stock stock(Object... pairs) {
        Stock stock = new Stock();
        for (int i = 0; i < pairs.length; i += 2) {
            stock.add((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return stock;
    }

    private static TradePlanner.Offer buyOf(TradePlanner.Plan plan, String item) {
        return plan.buys().stream().filter(o -> o.item().equals(item)).findFirst().orElseThrow();
    }

    @Test
    void enemyGetsNothing() {
        TradePlanner.Plan plan = TradePlanner.plan(stock(EMERALD, 50, COBBLE, 900),
                List.of(new TradePlanner.Need(FLINT, 10, 0)), Map.of(COBBLE, 500), Reputation.Status.ENEMY, TradePlannerTest::stack);
        assertTrue(plan.isEmpty());
    }

    @Test
    void buysNeededItemForEmeraldsAtUrgencyMultiplier() {
        Stock stock = stock(EMERALD, 100);
        TradePlanner.Plan construction = TradePlanner.plan(stock, List.of(new TradePlanner.Need(FLINT, 30, 0)), Map.of(),
                Reputation.Status.GUEST, TradePlannerTest::stack);
        TradePlanner.Plan essential = TradePlanner.plan(stock, List.of(new TradePlanner.Need(FLINT, 30, 3)), Map.of(),
                Reputation.Status.GUEST, TradePlannerTest::stack);
        TradePlanner.Offer high = buyOf(construction, FLINT);
        TradePlanner.Offer low = buyOf(essential, FLINT);
        assertEquals(EMERALD, high.payItem());
        assertEquals(10, high.itemCount());
        assertEquals(2, high.payCount());
        assertEquals(1, low.payCount());
        assertEquals(3, high.maxUses());
    }

    @Test
    void emeraldBudgetIsSharedAcrossBuyOffers() {
        Stock stock = stock(EMERALD, 3);
        List<TradePlanner.Need> needs = List.of(new TradePlanner.Need(FLINT, 100, 0), new TradePlanner.Need(COAL, 200, 1));
        TradePlanner.Plan plan = TradePlanner.plan(stock, needs, Map.of(), Reputation.Status.GUEST, TradePlannerTest::stack);
        int committed = 0;
        for (TradePlanner.Offer offer : plan.buys()) {
            if (offer.payItem().equals(EMERALD)) {
                committed += offer.payCount() * offer.maxUses();
            }
        }
        assertTrue(committed <= 3, "committed emeralds " + committed);
    }

    @Test
    void paysWithSurplusItemWhenNoEmeralds() {
        Stock stock = stock(COBBLE, 900);
        TradePlanner.Plan plan = TradePlanner.plan(stock, List.of(new TradePlanner.Need(FLINT, 10, 0)), Map.of(COBBLE, 500),
                Reputation.Status.GUEST, TradePlannerTest::stack);
        TradePlanner.Offer offer = buyOf(plan, FLINT);
        assertEquals(COBBLE, offer.payItem());
        assertTrue((long) offer.payCount() * PriceTable.base(COBBLE) >= 2000 * 0.99);
        assertTrue(offer.payCount() * offer.maxUses() <= 500);
    }

    @Test
    void noOfferWhenNothingCanPay() {
        TradePlanner.Plan plan = TradePlanner.plan(stock(), List.of(new TradePlanner.Need(FLINT, 10, 0)), Map.of(),
                Reputation.Status.GUEST, TradePlannerTest::stack);
        assertTrue(plan.buys().isEmpty());
    }

    @Test
    void sellsLargestSurplusForEmeraldsLimitedByStock() {
        Map<String, Integer> surplus = Map.of(LOG, 200, COBBLE, 600, Ids.mc("dirt"), 3);
        TradePlanner.Plan plan = TradePlanner.plan(stock(LOG, 400, COBBLE, 900), List.of(), surplus, Reputation.Status.GUEST,
                TradePlannerTest::stack);
        assertEquals(2, plan.sells().size());
        TradePlanner.Offer first = plan.sells().getFirst();
        assertEquals(LOG, first.item());
        assertEquals(EMERALD, first.payItem());
        assertEquals(Math.min(TradePlanner.MAX_USES, 200 / first.itemCount()), first.maxUses());
        for (TradePlanner.Offer sell : plan.sells()) {
            assertTrue(sell.maxUses() * sell.itemCount() <= surplus.get(sell.item()));
        }
    }

    @Test
    void neededItemIsNeverAlsoSold() {
        TradePlanner.Plan plan = TradePlanner.plan(stock(EMERALD, 20, FLINT, 50), List.of(new TradePlanner.Need(FLINT, 10, 1)),
                Map.of(FLINT, 30), Reputation.Status.GUEST, TradePlannerTest::stack);
        assertTrue(plan.sells().stream().noneMatch(o -> o.item().equals(FLINT)));
    }

    @Test
    void sellPriceAlwaysBeatsBuyPriceForSameValue() {
        for (Reputation.Status status : List.of(Reputation.Status.GUEST, Reputation.Status.ALLY, Reputation.Status.SUSPECT)) {
            TradePlanner.Plan plan = TradePlanner.plan(stock(EMERALD, 500, COBBLE, 900, COAL, 5),
                    List.of(new TradePlanner.Need(COAL, 40, 3)), Map.of(COBBLE, 600), status, TradePlannerTest::stack);
            TradePlanner.Offer sell = plan.sells().stream().filter(o -> o.item().equals(COBBLE)).findFirst().orElseThrow();
            TradePlanner.Offer buy = buyOf(plan, COAL);
            double sellUnit = (double) sell.payCount() * PriceTable.EMERALD / sell.itemCount() / PriceTable.base(COBBLE);
            double buyUnit = (double) buy.payCount() * PriceTable.EMERALD / buy.itemCount() / PriceTable.base(COAL);
            assertTrue(sellUnit >= buyUnit * 0.99, status + " sell " + sellUnit + " buy " + buyUnit);
        }
    }

    @Test
    void friendsPayLessAndEarnMoreThanDislikedPlayers() {
        List<TradePlanner.Need> needs = List.of(new TradePlanner.Need(Ids.mc("diamond"), 10, 0));
        Stock stock = stock(EMERALD, 500, COBBLE, 900);
        Map<String, Integer> surplus = Map.of(COBBLE, 600);
        TradePlanner.Plan ally = TradePlanner.plan(stock, needs, surplus, Reputation.Status.ALLY, TradePlannerTest::stack);
        TradePlanner.Plan suspect = TradePlanner.plan(stock, needs, surplus, Reputation.Status.SUSPECT, TradePlannerTest::stack);
        assertTrue(buyOf(ally, Ids.mc("diamond")).payCount() > buyOf(suspect, Ids.mc("diamond")).payCount());
        TradePlanner.Offer allySell = ally.sells().getFirst();
        TradePlanner.Offer suspectSell = suspect.sells().getFirst();
        assertTrue((double) allySell.payCount() / allySell.itemCount() < (double) suspectSell.payCount() / suspectSell.itemCount());
    }

    @Test
    void offersAreCappedAtEightBuysAndSixSells() {
        Stock stock = stock(EMERALD, 1000);
        List<TradePlanner.Need> needs = new java.util.ArrayList<>();
        Map<String, Integer> surplus = new java.util.HashMap<>();
        for (int i = 0; i < 20; i++) {
            needs.add(new TradePlanner.Need(Ids.mc("need_" + i), 20, 1));
            surplus.put(Ids.mc("spare_" + i), 200);
        }
        TradePlanner.Plan plan = TradePlanner.plan(stock, needs, surplus, Reputation.Status.GUEST, TradePlannerTest::stack);
        assertEquals(TradePlanner.MAX_BUY, plan.buys().size());
        assertEquals(TradePlanner.MAX_SELL, plan.sells().size());
    }

    @Test
    void mergeKeepsHighestPriorityAndLargestCount() {
        List<TradePlanner.Need> merged = TradePlanner.merge(List.of(new TradePlanner.Need(FLINT, 4, 3), new TradePlanner.Need(FLINT, 9, 1),
                new TradePlanner.Need(EMERALD, 5, 0), new TradePlanner.Need(COAL, 0, 0)));
        assertEquals(1, merged.size());
        assertEquals(new TradePlanner.Need(FLINT, 9, 1), merged.getFirst());
    }

    @Test
    void priorityOrdersOffers() {
        TradePlanner.Plan plan = TradePlanner.plan(stock(EMERALD, 100), List.of(new TradePlanner.Need(COAL, 30, 3),
                new TradePlanner.Need(FLINT, 30, 0)), Map.of(), Reputation.Status.GUEST, TradePlannerTest::stack);
        assertEquals(FLINT, plan.buys().getFirst().item());
    }

    @Test
    void crafterNeedsFollowProductInputs() {
        List<TradePlanner.Need> needs = TradePlanner.crafterNeeds(stock(FLINT, 6), List.of(Ids.mc("arrow"), Ids.mc("iron_sword")));
        Map<String, Integer> byId = new java.util.HashMap<>();
        needs.forEach(n -> byId.put(n.id(), n.count()));
        assertEquals(10, byId.get(FLINT));
        assertEquals(16, byId.get(Ids.mc("feather")));
        assertEquals(24, byId.get(Ids.mc("iron_ingot")));
        assertTrue(needs.stream().allMatch(n -> n.priority() == TradePlanner.PRIORITY_CRAFTER));
    }

    @Test
    void essentialsOnlyForLowStock() {
        List<TradePlanner.Need> low = TradePlanner.essentials(stock(COAL, 10));
        assertTrue(low.stream().anyMatch(n -> n.id().equals(COAL) && n.count() == 22));
        List<TradePlanner.Need> full = TradePlanner.essentials(stock(COAL, 40));
        assertFalse(full.stream().anyMatch(n -> n.id().equals(COAL)));
    }

    @Test
    void coverageFollowsResultItemOfEitherDirection() {
        Stock stock = stock(EMERALD, 2, COBBLE, 10);
        assertTrue(TradePlanner.covers(EMERALD, 2, stock));
        assertFalse(TradePlanner.covers(EMERALD, 3, stock));
        assertTrue(TradePlanner.covers(COBBLE, 10, stock));
        assertFalse(TradePlanner.covers(LOG, 1, stock));
    }

    @Test
    void offerAccessorsMapDirection() {
        TradePlanner.Offer buy = new TradePlanner.Offer(true, FLINT, 10, EMERALD, 2, 3);
        assertEquals(FLINT, buy.give());
        assertEquals(EMERALD, buy.result());
        assertEquals(2, buy.resultCount());
        TradePlanner.Offer sell = new TradePlanner.Offer(false, COBBLE, 16, EMERALD, 1, 5);
        assertEquals(EMERALD, sell.give());
        assertEquals(COBBLE, sell.result());
        assertEquals(16, sell.resultCount());
        assertNotEquals(buy.give(), sell.give());
    }

    @Test
    void reputationPointsGrowWithTradedValue() {
        assertEquals(0, TradePlanner.reputationPoints(3999));
        assertEquals(1, TradePlanner.reputationPoints(4000));
        assertEquals(3, TradePlanner.reputationPoints(1_000_000));
    }
}
