package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ColonyData {
    public String id = "";
    public String name = "";
    public String dimension = "minecraft:overworld";
    public Pos center = new Pos(0, 64, 0);
    public Pos depot = new Pos(0, 64, 0);
    public int radius = 32;
    public long founded;
    public int peakMembers;
    public Map<Category, List<Pos>> storage = new EnumMap<>(Category.class);
    public Map<String, Member> members = new LinkedHashMap<>();
    public List<Building> buildings = new ArrayList<>();
    public Project project;
    public List<Project> projects = new ArrayList<>();
    public Mine mine;
    public List<Pos> farms = new ArrayList<>();
    public Pen pen;
    public Map<String, Integer> reputation = new LinkedHashMap<>();
    public double[] threat = new double[ThreatMap.SECTORS];
    public List<String> journal = new ArrayList<>();
    public Map<String, Integer> stats = new LinkedHashMap<>();
    public List<Pos> torches = new ArrayList<>();
    public List<Pos> roads = new ArrayList<>();
    public List<Pos> pendingRoad = new ArrayList<>();
    public List<Pos> pendingTorches = new ArrayList<>();
    public List<Pos> plantedTrees = new ArrayList<>();
    public List<Order> orders = new ArrayList<>();
    public Map<String, Integer> renovated = new LinkedHashMap<>();
    public boolean aiOff;
    public Wall wall;
    public boolean villageSurveyed;
    public int surveyVersion;
    public int wallVersion;
    public long lastMarket;
    public long lastGolem;
    public long lastFeeding;
    public String traderId = "";
    public long traderLost;

    public static final class Member {
        public String role = Role.IDLE.key();
        public boolean locked;
        public boolean named;
        public long since;
        public int delivered;
        public Map<String, Integer> xp = new LinkedHashMap<>();

        public Role role() {
            return Role.byKey(role);
        }
    }

    public static final class Order {
        public Pos pos;
        public String state;
        public String tag = "";

        public Order() {
        }

        public Order(Pos pos, String state, String tag) {
            this.pos = pos;
            this.state = state;
            this.tag = tag;
        }
    }

    public static final class Wall {
        public String material = "stone";
        public List<Pos> columns = new ArrayList<>();
        public int total;
        public int built;
        public long planned;
        public boolean announced;
        public List<Pos> gates = new ArrayList<>();
        public List<Integer> heights = new ArrayList<>();
    }

    public static final class Building {
        public String blueprint = "";
        public String kind = "";
        public Pos origin = new Pos(0, 0, 0);
        public int rotation;
        public String wood = "oak";
        public Pos door = new Pos(0, 0, 0);
        public Pos min = new Pos(0, 0, 0);
        public Pos max = new Pos(0, 0, 0);
        public int beds;
        public List<Pos> containers = new ArrayList<>();
        public Category storageFor;
        public boolean connected;
        public Map<String, String> swaps = new LinkedHashMap<>();

        public boolean contains(Pos pos, int margin) {
            return pos.x() >= min.x() - margin && pos.x() <= max.x() + margin
                    && pos.z() >= min.z() - margin && pos.z() <= max.z() + margin
                    && pos.y() >= min.y() - margin - 2 && pos.y() <= max.y() + margin;
        }
    }

    public static final class Project {
        public Building building = new Building();
        public String phase = "reserve";
        public int cursor;
        public Map<String, Integer> required = new LinkedHashMap<>();
        public Map<String, Integer> reserved = new LinkedHashMap<>();
        public List<Pos> fill = new ArrayList<>();
        public List<Pos> crane = new ArrayList<>();
        public Map<String, String> swaps = new LinkedHashMap<>();
        public long started;
        public long waitingSince;
    }

    public static final class Mine {
        public Pos entrance = new Pos(0, 0, 0);
        public int dirX = 1;
        public int dirZ;
        public int depth = 1;
        public int progress;
        public int generation;
        public String type;
        public Pos corner;
        public int width;
        public boolean decorated;
        public int decorVersion;
        public Pos shed;

        public boolean quarry() {
            return "quarry".equals(type) && corner != null;
        }

        public DigPlan plan() {
            if (quarry()) {
                return new QuarryPlan(corner, width, depth);
            }
            return new MinePlan(entrance, dirX, dirZ, depth);
        }
    }

    public static final class Pen {
        public Pos min = new Pos(0, 0, 0);
        public Pos max = new Pos(0, 0, 0);
        public Pos gate = new Pos(0, 0, 0);
        public Pos inside = new Pos(0, 0, 0);
        public Pos outside = new Pos(0, 0, 0);
    }

    public int stat(String key) {
        return stats.getOrDefault(key, 0);
    }

    public void bump(String key, int amount) {
        stats.merge(key, amount, Integer::sum);
    }

    public void log(long gameTime, String line) {
        journal.add(gameTime + " " + line);
        while (journal.size() > 60) {
            journal.removeFirst();
        }
    }

    public int beds() {
        int total = 0;
        for (Building building : buildings) {
            total += building.beds;
        }
        return total;
    }
}
