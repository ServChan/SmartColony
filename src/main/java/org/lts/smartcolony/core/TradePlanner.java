package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

public final class TradePlanner {
    public static final String EMERALD_ID = Ids.mc("emerald");
    public static final int MAX_BUY = 8;
    public static final int MAX_SELL = 6;
    public static final int MAX_USES = 64;
    public static final int PRIORITY_CONSTRUCTION = 0;
    public static final int PRIORITY_REQUEST = 1;
    public static final int PRIORITY_CRAFTER = 2;
    public static final int PRIORITY_ESSENTIAL = 3;
    public static final long REPUTATION_STEP = 4000;
    private static final double[] URGENCY = {2.0, 1.7, 1.4, 1.1};
    private static final double SELL_PREMIUM = 1.2;
    private static final double MAX_ITEM_OVERPAY = 1.4;
    private static final Map<String, Integer> ESSENTIAL = Map.of(
            Ids.mc("coal"), 32,
            Ids.mc("wheat"), 64,
            Ids.mc("iron_ingot"), 20,
            Ids.mc("string"), 16,
            Ids.mc("flint"), 16,
            Ids.mc("leather"), 8);

    public record Need(String id, int count, int priority) {
    }

    public record Offer(boolean buy, String item, int itemCount, String payItem, int payCount, int maxUses) {
        public String give() {
            return buy ? item : payItem;
        }

        public int giveCount() {
            return buy ? itemCount : payCount;
        }

        public String result() {
            return buy ? payItem : item;
        }

        public int resultCount() {
            return buy ? payCount : itemCount;
        }
    }

    public record Plan(List<Offer> buys, List<Offer> sells) {
        public static final Plan EMPTY = new Plan(List.of(), List.of());

        public List<Offer> all() {
            List<Offer> all = new ArrayList<>(buys);
            all.addAll(sells);
            return all;
        }

        public boolean isEmpty() {
            return buys.isEmpty() && sells.isEmpty();
        }
    }

    private TradePlanner() {
    }

    public static double buyFactor(Reputation.Status status) {
        return switch (status) {
            case ALLY -> 1.15;
            case GUEST -> 1.0;
            case SUSPECT -> 0.8;
            case ENEMY -> 0.0;
        };
    }

    public static double sellFactor(Reputation.Status status) {
        return switch (status) {
            case ALLY -> 0.85;
            case GUEST -> 1.0;
            case SUSPECT -> 1.3;
            case ENEMY -> 0.0;
        };
    }

    public static double urgency(int priority) {
        return URGENCY[Math.max(0, Math.min(URGENCY.length - 1, priority))];
    }

    public static int reputationPoints(long accumulatedValue) {
        return (int) Math.min(3, Math.max(0, accumulatedValue / REPUTATION_STEP));
    }

    public static boolean covers(String resultId, int resultCount, Stock stock) {
        return stock.get(resultId) >= resultCount;
    }

    public static List<Need> merge(List<Need> needs) {
        Map<String, Need> merged = new LinkedHashMap<>();
        for (Need need : needs) {
            if (need.count() <= 0 || need.id().equals(EMERALD_ID)) {
                continue;
            }
            merged.merge(need.id(), need, (a, b) -> new Need(a.id(), Math.max(a.count(), b.count()), Math.min(a.priority(), b.priority())));
        }
        List<Need> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparingInt(Need::priority)
                .thenComparing((Need n) -> -(long) PriceTable.base(n.id()) * n.count())
                .thenComparing(Need::id));
        return result;
    }

    public static List<Need> essentials(Stock stock) {
        List<Need> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : ESSENTIAL.entrySet()) {
            int missing = entry.getValue() - stock.get(entry.getKey());
            if (missing > 0) {
                result.add(new Need(entry.getKey(), missing, PRIORITY_ESSENTIAL));
            }
        }
        return result;
    }

    public static Map<String, Integer> inputsFor(String product) {
        String path = Ids.path(product);
        Map<String, Integer> inputs = new LinkedHashMap<>();
        if (path.equals("arrow")) {
            inputs.put(Ids.mc("flint"), 16);
            inputs.put(Ids.mc("feather"), 16);
            inputs.put(Ids.mc("stick"), 16);
        } else if (path.equals("bow")) {
            inputs.put(Ids.mc("string"), 3);
            inputs.put(Ids.mc("stick"), 3);
        } else if (path.equals("bucket") || path.equals("shears") || path.startsWith("iron_")) {
            inputs.put(Ids.mc("iron_ingot"), 24);
        } else if (path.startsWith("cooked_")) {
            inputs.put(Ids.mc("coal"), 8);
        } else if (path.startsWith("stone_")) {
            inputs.put(Ids.mc("cobblestone"), 16);
            inputs.put(Ids.mc("stick"), 8);
        }
        return inputs;
    }

    public static List<Need> crafterNeeds(Stock stock, List<String> products) {
        List<Need> result = new ArrayList<>();
        for (String product : products) {
            for (Map.Entry<String, Integer> input : inputsFor(product).entrySet()) {
                int missing = input.getValue() - stock.get(input.getKey());
                if (missing > 0) {
                    result.add(new Need(input.getKey(), missing, PRIORITY_CRAFTER));
                }
            }
        }
        return result;
    }

    public static Plan plan(Stock stock, List<Need> needs, Map<String, Integer> surplus, Reputation.Status status,
                            ToIntFunction<String> stackSize) {
        if (status == Reputation.Status.ENEMY) {
            return Plan.EMPTY;
        }
        List<Need> ordered = merge(needs);
        Map<String, Integer> pool = new HashMap<>();
        surplus.forEach((id, count) -> {
            if (count > 0 && !id.equals(EMERALD_ID)) {
                pool.put(id, count);
            }
        });
        for (Need need : ordered) {
            pool.remove(need.id());
        }
        int emeralds = stock.get(EMERALD_ID);
        List<Offer> buys = new ArrayList<>();
        for (Need need : ordered) {
            if (buys.size() >= MAX_BUY) {
                break;
            }
            int base = PriceTable.base(need.id());
            int stack = Math.max(1, stackSize.applyAsInt(need.id()));
            int batch = Math.max(1, Math.min(stack, (int) Math.ceil((double) PriceTable.EMERALD / base)));
            double value = batch * (double) base * urgency(need.priority()) * buyFactor(status);
            int uses = Math.min(MAX_USES, (int) Math.ceil((double) need.count() / batch));
            int emeraldPay = Math.max(1, (int) Math.round(value / PriceTable.EMERALD));
            if (emeraldPay <= 64 && emeraldPay <= emeralds) {
                int affordable = Math.min(uses, emeralds / emeraldPay);
                buys.add(new Offer(true, need.id(), batch, EMERALD_ID, emeraldPay, affordable));
                emeralds -= affordable * emeraldPay;
                continue;
            }
            Offer withItems = payWithItem(need.id(), batch, value, uses, pool, stackSize);
            if (withItems != null) {
                buys.add(withItems);
                pool.merge(withItems.payItem(), -withItems.payCount() * withItems.maxUses(), Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> candidates = new ArrayList<>(pool.entrySet());
        candidates.removeIf(entry -> entry.getValue() <= 0);
        candidates.sort(Comparator.comparingLong((Map.Entry<String, Integer> e) -> -(long) PriceTable.base(e.getKey()) * e.getValue())
                .thenComparing(Map.Entry::getKey));
        List<Offer> sells = new ArrayList<>();
        double factor = SELL_PREMIUM * sellFactor(status);
        for (Map.Entry<String, Integer> entry : candidates) {
            if (sells.size() >= MAX_SELL) {
                break;
            }
            String id = entry.getKey();
            double unit = PriceTable.base(id) * factor;
            int stack = Math.max(1, stackSize.applyAsInt(id));
            int batch = Math.max(1, Math.min(stack, (int) Math.floor(PriceTable.EMERALD / unit)));
            int price = Math.max(1, (int) Math.ceil(batch * unit / PriceTable.EMERALD));
            int uses = Math.min(MAX_USES, entry.getValue() / batch);
            if (uses < 1 || price > 64) {
                continue;
            }
            sells.add(new Offer(false, id, batch, EMERALD_ID, price, uses));
        }
        return new Plan(buys, sells);
    }

    private static Offer payWithItem(String needId, int batch, double value, int uses, Map<String, Integer> pool,
                                     ToIntFunction<String> stackSize) {
        Offer best = null;
        double bestRatio = Double.MAX_VALUE;
        List<String> ids = new ArrayList<>(pool.keySet());
        Collections.sort(ids);
        for (String id : ids) {
            int available = pool.get(id);
            int base = PriceTable.base(id);
            if (id.equals(needId) || available <= 0 || base <= 0) {
                continue;
            }
            int count = (int) Math.ceil(value / base);
            int stack = Math.max(1, stackSize.applyAsInt(id));
            if (count < 1 || count > stack || count > available) {
                continue;
            }
            double ratio = count * (double) base / value;
            if (ratio > MAX_ITEM_OVERPAY || ratio >= bestRatio) {
                continue;
            }
            bestRatio = ratio;
            best = new Offer(true, needId, batch, id, count, Math.min(uses, available / count));
        }
        return best;
    }
}
