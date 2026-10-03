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
    public Map<Category, List<Pos>> storage = new EnumMap<>(Category.class);
    public Map<String, Member> members = new LinkedHashMap<>();
    public List<Building> buildings = new ArrayList<>();
    public Project project;
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
    public long lastMarket;
    public long lastGolem;
    public long lastFeeding;

    public static final class Member {
        public String role = Role.IDLE.key();
        public boolean locked;
        public boolean named;
        public long since;
        public int delivered;

        public Role role() {
            return Role.byKey(role);
        }
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

        public MinePlan plan() {
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
