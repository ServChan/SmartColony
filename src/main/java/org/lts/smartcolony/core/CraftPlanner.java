package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class CraftPlanner {
    private static final int MAX_DEPTH = 7;

    public record Step(String output, int amount, boolean smelting) {
    }

    public record Plan(List<Step> steps, Map<String, Integer> delta, Stock after) {
        public boolean trivial() {
            return steps.isEmpty();
        }
    }

    private Stock work;
    private final List<Step> steps = new ArrayList<>();
    private final Set<String> visiting = new HashSet<>();

    private CraftPlanner(Stock stock) {
        this.work = stock.copy();
    }

    public static Optional<Plan> plan(Stock stock, String item, int count) {
        return plan(stock, Map.of(item, count));
    }

    public static Optional<Plan> planMore(Stock stock, String item, int extra) {
        return plan(stock, item, stock.get(item) + Math.max(0, extra));
    }

    public static Optional<Plan> plan(Stock stock, Map<String, Integer> wanted) {
        CraftPlanner planner = new CraftPlanner(stock);
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            if (!planner.ensure(entry.getKey(), entry.getValue(), 0)) {
                return Optional.empty();
            }
        }
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            planner.work.remove(entry.getKey(), entry.getValue());
        }
        Map<String, Integer> delta = Stock.difference(stock.view(), planner.work.view());
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            delta.merge(entry.getKey(), entry.getValue(), Integer::sum);
            if (delta.get(entry.getKey()) == 0) {
                delta.remove(entry.getKey());
            }
        }
        Stock after = planner.work.copy();
        wanted.forEach(after::add);
        return Optional.of(new Plan(List.copyOf(planner.steps), delta, after));
    }

    private boolean ensure(String item, int need, int depth) {
        int have = work.get(item);
        if (have >= need) {
            return true;
        }
        if (depth > MAX_DEPTH || visiting.contains(item)) {
            return false;
        }
        List<Recipe> recipes = RecipeBook.recipesFor(item);
        if (recipes.isEmpty()) {
            return false;
        }
        int missing = need - have;
        visiting.add(item);
        try {
            for (Recipe recipe : recipes) {
                if (!work.has(recipe.requires())) {
                    continue;
                }
                Stock snapshot = work.copy();
                int stepCount = steps.size();
                int batches = (missing + recipe.count() - 1) / recipe.count();
                if (apply(recipe, batches, depth)) {
                    return true;
                }
                work = snapshot;
                while (steps.size() > stepCount) {
                    steps.removeLast();
                }
            }
            return false;
        } finally {
            visiting.remove(item);
        }
    }

    private boolean apply(Recipe recipe, int batches, int depth) {
        for (Map.Entry<String, Integer> input : recipe.inputs().entrySet()) {
            int total = input.getValue() * batches;
            if (Recipe.isGroup(input.getKey())) {
                if (!takeGroup(input.getKey(), total, depth)) {
                    return false;
                }
            } else {
                if (!ensure(input.getKey(), total, depth + 1)) {
                    return false;
                }
                work.remove(input.getKey(), total);
            }
        }
        if (recipe.smelting() && !burn(batches * RecipeBook.SMELT_COST)) {
            return false;
        }
        work.add(recipe.output(), recipe.count() * batches);
        steps.add(new Step(recipe.output(), recipe.count() * batches, recipe.smelting()));
        return true;
    }

    private boolean takeGroup(String group, int total, int depth) {
        int remaining = total;
        List<Map.Entry<String, Integer>> members = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : work.view().entrySet()) {
            if (Recipe.inGroup(group, entry.getKey())) {
                members.add(Map.entry(entry.getKey(), entry.getValue()));
            }
        }
        members.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()));
        for (Map.Entry<String, Integer> member : members) {
            remaining -= work.remove(member.getKey(), remaining);
            if (remaining == 0) {
                return true;
            }
        }
        for (String candidate : RecipeBook.groupCraftCandidates(group)) {
            Stock snapshot = work.copy();
            int stepCount = steps.size();
            if (ensure(candidate, remaining, depth + 1)) {
                work.remove(candidate, remaining);
                return true;
            }
            work = snapshot;
            while (steps.size() > stepCount) {
                steps.removeLast();
            }
        }
        return false;
    }

    private boolean burn(int units) {
        Map<String, Integer> fuels = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : work.view().entrySet()) {
            String id = entry.getKey();
            if (RecipeBook.fuelValue(id) > 0) {
                fuels.put(id, entry.getValue());
            }
        }
        List<String> order = new ArrayList<>(fuels.keySet());
        order.sort(Comparator.<String>comparingInt(id -> RecipeBook.fuelValue(id) > 100 ? 0 : RecipeBook.fuelValue(id))
                .reversed().thenComparing(Comparator.naturalOrder()));
        int remaining = units;
        for (String fuel : order) {
            int value = RecipeBook.fuelValue(fuel);
            int wanted = (remaining + value - 1) / value;
            int taken = work.remove(fuel, wanted);
            remaining -= taken * value;
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }
}
