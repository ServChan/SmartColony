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
    public List<Outpost> outposts = new ArrayList<>();
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
    public Map<String, String> roadStyle = new LinkedHashMap<>();
    public List<Target> targets = new ArrayList<>();
    public List<Lamp> lamps = new ArrayList<>();
    public int pathVersion;
    public int scanIndex;
    public int scanRadius;
    public boolean scanDone;
    public List<Pos> plantedTrees = new ArrayList<>();
    public List<Order> orders = new ArrayList<>();
    public Map<String, Integer> renovated = new LinkedHashMap<>();
    public String age = Tech.STONE_AGE;
    public boolean forgeUpgraded;
    public boolean aiOff;
    public Wall wall;
    public List<Mine> mines = new ArrayList<>();
    public boolean villageSurveyed;
    public int surveyVersion;
    public int wallVersion;
    public int penVersion;
    public int innVersion;
    public boolean terrainSurveyed;
    public long lastMarket;
    public long lastGolem;
    public long lastFeeding;
    public String traderId = "";
    public long traderLost;
    public List<Death> deaths = new ArrayList<>();
    public List<Pos> graves = new ArrayList<>();
    public String head = "";
    public boolean headHoused;
    public List<Pos> treasury = new ArrayList<>();
    public String focus = Decree.Focus.BALANCED.key();
    public List<Decree.Zone> zones = new ArrayList<>();
    public List<Contract> contracts = new ArrayList<>();
    public List<Discovery> discoveries = new ArrayList<>();
    public List<Caravan> caravans = new ArrayList<>();
    public long lastCaravan;
    public int scoutTrips;
    public List<String> packAnimals = new ArrayList<>();
    public int knowledge;
    public long chronicleDay = -1;
    public long lastRaid;
    public int walkwayVersion;
    public String temper = "";
    public String policy = Diplomacy.Policy.AUTO.key();
    public Map<String, Relation> relations = new LinkedHashMap<>();
    public List<Mission> missions = new ArrayList<>();
    public long diplomacyDay = -1;
    public List<Highway> highways = new ArrayList<>();

    public static final class Highway {
        public String partner = "";
        public String phase = "route";
        public Pos start = new Pos(0, 0, 0);
        public Pos goal = new Pos(0, 0, 0);
        public List<Pos> cells = new ArrayList<>();
        public int built;
        public int length;
        public List<Integer> camps = new ArrayList<>();
        public int campsPlanned;
        public int segment;
        public int failures;
        public long since;
        public long retryAt;
        public boolean lead;
        public boolean signed;
    }

    public static final class Relation {
        public int standing;
        public String treaty = Diplomacy.Treaty.NONE.key();
        public long since;
        public long met;
        public long lastEnvoy;
        public long lastRaid;
        public long lastRaided;
        public long lastAid;
        public int gifts;
        public int trades;
        public int raidsMade;
        public int raidsSuffered;
        public int killed;
        public int lost;
        public int visits;
        public int aid;
        public List<String> history = new ArrayList<>();

        public Diplomacy.Treaty treaty() {
            return Diplomacy.Treaty.byKey(treaty);
        }

        public void remember(long day, String event) {
            history.add(day + " " + event);
            while (history.size() > 12) {
                history.removeFirst();
            }
        }
    }

    public static final class Mission {
        public String member = "";
        public String kind = "";
        public String target = "";
        public String proposal = "";
        public String phase = "out";
        public Map<String, Integer> goods = new LinkedHashMap<>();
        public long started;
        public long until;
        public int failures;
        public boolean done;
    }

    public static final class Contract {
        public String item = "";
        public int count;
        public int delivered;
        public String player = "";
        public long created;
        public int parcel;
        public boolean ready;
    }

    public static final class Discovery {
        public String kind = "";
        public String what = "";
        public Pos pos = new Pos(0, 0, 0);
        public int value;
        public long time;
        public long visited;
        public boolean used;
        public long worked;
        public int dry;
    }

    public static final class Caravan {
        public String from = "";
        public String to = "";
        public Map<String, Integer> goods = new LinkedHashMap<>();
        public long departed;
        public long arrives;
    }

    public static final class Death {
        public String id = "";
        public String name = "";
        public String role = "idle";
        public boolean child;
        public String cause = "other";
        public Pos pos = new Pos(0, 0, 0);
        public long time;
        public long day;
        public String remains = "none";
        public int items;
        public int recovered;
        public int tries;
        public boolean buried;
        public Pos grave;
    }

    public static final class Member {
        public String role = Role.IDLE.key();
        public boolean locked;
        public boolean named;
        public long since;
        public int delivered;
        public Map<String, Integer> xp = new LinkedHashMap<>();
        public Map<String, Integer> study = new LinkedHashMap<>();
        public String subject = "";
        public String graduate = "";
        public String affinity = "";

        public Role role() {
            return Role.byKey(role);
        }

        public Role affinityRole() {
            return affinity == null || affinity.isEmpty() ? null : Role.byKey(affinity);
        }

        public Role graduateRole() {
            return graduate == null || graduate.isEmpty() ? null : Role.byKey(graduate);
        }
    }

    public static final class Order {
        public Pos pos;
        public String state;
        public String tag = "";
        public boolean attached;

        public Order() {
        }

        public Order(Pos pos, String state, String tag) {
            this.pos = pos;
            this.state = state;
            this.tag = tag;
        }
    }

    public static final class Target {
        public Pos pos = new Pos(0, 0, 0);
        public String kind = "";
        public int dx;
        public int dz;
        public boolean main;
        public int state;
        public int tries;
        public long retryAt;
    }

    public static final class Lamp {
        public Pos pos = new Pos(0, 0, 0);
        public int tier = 1;
        public int armX;
        public int armZ;
        public String base = "minecraft:cobblestone";
        public String wood = "oak";
        public String phase = "build";
        public Pos replaces;
        public int replacesTier;
        public boolean inPlace;
        public long since;
        public int style;
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
        public boolean onWater;
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
        public List<Pos> stilts = new ArrayList<>();
        public Map<String, String> swaps = new LinkedHashMap<>();
        public long started;
        public long waitingSince;
        public boolean upgrade;
        public Map<String, Integer> deferred = new LinkedHashMap<>();
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
        public String wood;
        public boolean headDone;
        public Pos anchor;
        public List<Integer> ahead = new ArrayList<>();

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

    public static final class Outpost {
        public String kind = "mine";
        public Pos door = new Pos(0, 0, 0);
        public Pos center = new Pos(0, 0, 0);
        public List<Pos> containers = new ArrayList<>();
        public long since;
        public long lastUse;
        public long blockedUntil;
        public int strikes;
        public int received;
        public int hauled;
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

    public long pendingRemains() {
        return deaths.stream().filter(d -> "pending".equals(d.remains)).count();
    }

    public long unburied() {
        return deaths.stream().filter(d -> !d.buried).count();
    }

    public int beds() {
        int total = 0;
        for (Building building : buildings) {
            total += building.beds;
        }
        return total;
    }
}
