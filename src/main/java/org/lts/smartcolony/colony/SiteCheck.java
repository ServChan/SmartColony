package org.lts.smartcolony.colony;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import org.lts.smartcolony.core.Blueprint;
import org.lts.smartcolony.core.Blueprints;
import org.lts.smartcolony.core.ColonyData;
import org.lts.smartcolony.core.Ids;
import org.lts.smartcolony.core.Pos;
import org.lts.smartcolony.core.PreBuildCheck;
import org.lts.smartcolony.core.SiteEvaluator;
import org.lts.smartcolony.core.TerrainMap;

final class SiteCheck {
    enum Status {
        OK,
        BAD,
        UNKNOWN
    }

    enum Outcome {
        PROCEED,
        CHANGED,
        WAIT
    }

    interface Source {
        TerrainMap.Column at(int x, int z);
    }

    record Sample(Status status, int[][] ground, boolean[][] blocked, int paths, TerrainMap.Column[][] columns) {
        static Sample of(Status status) {
            return new Sample(status, null, null, 0, null);
        }
    }

    record Report(boolean deferred, PreBuildCheck.Verdict verdict, String reason, SiteEvaluator.Result result,
                  ColonyData.Project fresh, int compared, int changed) {
    }

    private static final long RECHECK_TICKS = 6000;
    private static final long RETRY_TICKS = 400;
    private static final int LOCKED_EXTRA_FILL = 2;

    private final Colony colony;
    private final Construction construction;
    private final Map<ColonyData.Project, Long> next = new IdentityHashMap<>();
    private final Map<ColonyData.Project, Long> periodic = new IdentityHashMap<>();

    SiteCheck(Colony colony, Construction construction) {
        this.colony = colony;
        this.construction = construction;
    }

    Sample fromMap(int ox, int oz, int sx, int sz, int ring) {
        TerrainMap map = colony.survey().map();
        if (map == null) {
            return Sample.of(Status.UNKNOWN);
        }
        return sample(map::column, ox, oz, sx, sz, ring);
    }

    Sample fromWorld(ServerLevel level, int ox, int oz, int sx, int sz, int ring, ColonyData.Project ignore) {
        return sample((x, z) -> colony.survey().sample(level, x, z, ignore), ox, oz, sx, sz, ring);
    }

    private Sample sample(Source source, int ox, int oz, int sx, int sz, int ring) {
        int[][] ground = new int[sx][sz];
        boolean[][] blocked = new boolean[sx][sz];
        TerrainMap.Column[][] columns = new TerrainMap.Column[sx][sz];
        int paths = 0;
        for (int x = -ring; x < sx + ring; x++) {
            for (int z = -ring; z < sz + ring; z++) {
                TerrainMap.Column column = source.at(ox + x, oz + z);
                if (column == null || column.surface() == TerrainMap.Surface.UNKNOWN) {
                    return Sample.of(Status.UNKNOWN);
                }
                boolean inside = x >= 0 && z >= 0 && x < sx && z < sz;
                if (inside) {
                    ground[x][z] = column.ground();
                    blocked[x][z] = !column.surface().buildable();
                    columns[x][z] = column;
                    if (column.surface() == TerrainMap.Surface.PATH) {
                        paths++;
                    }
                } else if (column.surface().foreign()) {
                    return Sample.of(Status.BAD);
                }
            }
        }
        return new Sample(Status.OK, ground, blocked, paths, columns);
    }

    private static int[] limits(Blueprint blueprint, boolean relaxed) {
        if (relaxed) {
            return new int[] {4, Math.min(7, blueprint.sizeY() + 1)};
        }
        return new int[] {SiteEvaluator.MAX_FILL, SiteEvaluator.MAX_CUT};
    }

    Report assess(ServerLevel level, ColonyData.Project project, boolean lockBase) {
        ColonyData.Building building = project.building;
        Blueprint blueprint = Blueprints.get(building.blueprint);
        int sx = blueprint.rotatedSizeX(building.rotation);
        int sz = blueprint.rotatedSizeZ(building.rotation);
        int ox = building.origin.x();
        int oz = building.origin.z();
        Sample sample = fromWorld(level, ox, oz, sx, sz, 0, project);
        if (sample.status() != Status.OK) {
            return new Report(true, null, "footprint is not loaded", null, null, 0, 0);
        }
        int compared = 0;
        int changed = 0;
        TerrainMap map = colony.survey().map();
        if (map != null) {
            TerrainMap.Diff diff = map.diff(ox, oz, ox + sx - 1, oz + sz - 1, (x, z) -> sample.columns()[x - ox][z - oz]);
            compared = diff.compared();
            changed = diff.changes().size();
        }
        SiteEvaluator.Result result;
        if (lockBase) {
            int[] easy = limits(blueprint, false);
            result = SiteEvaluator.evaluateAt(sample.ground(), sample.blocked(), building.origin.y(),
                    easy[0] + LOCKED_EXTRA_FILL, Integer.MAX_VALUE);
        } else {
            result = SiteEvaluator.evaluate(sample.ground(), sample.blocked(), limits(blueprint, false)[0], limits(blueprint, false)[1]);
            if (!result.ok() && "house".equals(blueprint.kind())) {
                result = SiteEvaluator.evaluate(sample.ground(), sample.blocked(), limits(blueprint, true)[0], limits(blueprint, true)[1]);
            }
        }
        boolean feasible = result.ok() && (lockBase || construction.accessible(level, blueprint, building.rotation, ox, oz, result.baseY()));
        ColonyData.Project fresh = result.ok()
                ? construction.project(blueprint, building.wood, ox, oz, building.rotation, result, sample.ground()) : null;
        PreBuildCheck.Verdict verdict = PreBuildCheck.decide(feasible, building.origin.y(), project.fill,
                fresh == null ? building.origin.y() : result.baseY(), fresh == null ? project.fill : fresh.fill);
        return new Report(false, verdict, reason(sample, result, feasible, building, project, fresh, changed, compared), result, fresh,
                compared, changed);
    }

    private static String reason(Sample sample, SiteEvaluator.Result result, boolean feasible, ColonyData.Building building,
                                 ColonyData.Project project, ColonyData.Project fresh, int changed, int compared) {
        StringBuilder out = new StringBuilder();
        int[] counts = new int[TerrainMap.Surface.values().length];
        for (TerrainMap.Column[] row : sample.columns()) {
            for (TerrainMap.Column column : row) {
                if (!column.surface().buildable()) {
                    counts[column.surface().ordinal()]++;
                }
            }
        }
        for (TerrainMap.Surface surface : TerrainMap.Surface.values()) {
            if (counts[surface.ordinal()] > 0) {
                out.append(counts[surface.ordinal()]).append(' ').append(surface.name().toLowerCase(java.util.Locale.ROOT)).append(" columns; ");
            }
        }
        if (result.ok()) {
            out.append("base ").append(building.origin.y()).append(" -> ").append(result.baseY()).append(", fill ")
                    .append(project.fill.size()).append(" -> ").append(fresh == null ? project.fill.size() : fresh.fill.size());
        } else {
            out.append("max fill ").append(result.maxFill()).append(", max cut ").append(result.maxCut());
        }
        if (!feasible && result.ok()) {
            out.append("; the entrance is not reachable");
        }
        out.append("; ").append(changed).append(" of ").append(compared).append(" columns differ from the survey");
        return out.toString();
    }

    Outcome enforce(ServerLevel level, ColonyData.Project project, boolean lockBase) {
        long now = level.getGameTime();
        Long hold = next.get(project);
        if (hold != null && now < hold) {
            return Outcome.WAIT;
        }
        Report report = assess(level, project, lockBase);
        if (report.deferred()) {
            next.put(project, now + RETRY_TICKS);
            return Outcome.WAIT;
        }
        next.remove(project);
        return switch (report.verdict()) {
            case KEEP -> Outcome.PROCEED;
            case REFIT -> refit(level, project, report, lockBase);
            case RELOCATE -> relocate(level, project, report, now);
        };
    }

    void periodic(ServerLevel level, ColonyData.Project project) {
        long now = level.getGameTime();
        Long at = periodic.get(project);
        if (at == null) {
            periodic.put(project, now + RECHECK_TICKS);
            return;
        }
        if (now < at) {
            return;
        }
        periodic.put(project, now + RECHECK_TICKS);
        enforce(level, project, false);
    }

    void forget(ColonyData.Project project) {
        next.remove(project);
        periodic.remove(project);
    }

    private Outcome refit(ServerLevel level, ColonyData.Project project, Report report, boolean lockBase) {
        ColonyData.Project fresh = report.fresh();
        Pos oldOrigin = project.building.origin;
        Pos oldMin = project.building.min;
        Pos oldMax = project.building.max;
        Pos oldDoor = project.building.door;
        java.util.List<Pos> oldFill = project.fill;
        Map<String, Integer> oldRequired = project.required;
        project.building.origin = fresh.building.origin;
        project.building.min = fresh.building.min;
        project.building.max = fresh.building.max;
        project.building.door = fresh.building.door;
        project.fill = fresh.fill;
        construction.recomputeRequired(level, project);
        if (!project.reserved.isEmpty() && !settleReserve(level, project)) {
            project.building.origin = oldOrigin;
            project.building.min = oldMin;
            project.building.max = oldMax;
            project.building.door = oldDoor;
            project.fill = oldFill;
            project.required = oldRequired;
            colony.request(Ids.mc("cobblestone"));
            next.put(project, level.getGameTime() + RETRY_TICKS);
            return Outcome.WAIT;
        }
        project.cursor = 0;
        construction.resetPlan(project);
        reserveFootprint(level, project);
        colony.journal(level, "re-checked the " + project.building.blueprint + " site before building: plan refitted (" + report.reason() + ")");
        colony.markDirty();
        return Outcome.CHANGED;
    }

    private boolean settleReserve(ServerLevel level, ColonyData.Project project) {
        Map<String, Integer> deficit = PreBuildCheck.deficit(project.required, project.reserved);
        if (!deficit.isEmpty() && !colony.warehouse().takeAll(level, deficit)) {
            return false;
        }
        Map<String, Integer> surplus = PreBuildCheck.surplus(project.required, project.reserved);
        surplus.forEach((id, count) -> colony.warehouse().give(level, id, count));
        project.reserved = new LinkedHashMap<>(project.required);
        return true;
    }

    private Outcome relocate(ServerLevel level, ColonyData.Project project, Report report, long now) {
        Blueprint blueprint = Blueprints.get(project.building.blueprint);
        String name = project.building.blueprint;
        Pos from = project.building.origin;
        ColonyData.Project moved = construction.findSite(level, blueprint, project.building.wood);
        if (moved == null) {
            if (project.reserved.isEmpty()) {
                colony.journal(level, "the " + name + " site at " + from + " is no longer usable (" + report.reason() + ") and no other site was found; project dropped");
                construction.abandon(level, project);
                return Outcome.CHANGED;
            }
            next.put(project, now + RETRY_TICKS * 3);
            colony.journal(level, "the " + name + " site at " + from + " is no longer usable (" + report.reason() + "); looking for a new site");
            return Outcome.WAIT;
        }
        construction.lowerCrane(level, project);
        project.building = moved.building;
        project.fill = moved.fill;
        project.phase = "reserve";
        project.cursor = 0;
        construction.recomputeRequired(level, project);
        construction.resetPlan(project);
        reserveFootprint(level, project);
        colony.journal(level, "moved " + name + " from " + from + " to " + project.building.origin + " because the land changed (" + report.reason() + ")");
        colony.markDirty();
        return Outcome.CHANGED;
    }

    private void reserveFootprint(ServerLevel level, ColonyData.Project project) {
        colony.survey().reserve(project.building.min.x(), project.building.min.z(), project.building.max.x(), project.building.max.z(),
                level.getGameTime());
    }

    JsonArray describe(ServerLevel level) {
        JsonArray array = new JsonArray();
        for (ColonyData.Project project : colony.data().projects) {
            JsonObject entry = new JsonObject();
            entry.addProperty("blueprint", project.building.blueprint);
            entry.addProperty("phase", project.phase);
            entry.addProperty("origin", project.building.origin.toString());
            if (project.phase.equals("foundation") || project.phase.equals("build")) {
                entry.addProperty("verdict", "UNDERWAY");
                array.add(entry);
                continue;
            }
            Report report = assess(level, project, !project.phase.equals("reserve"));
            entry.addProperty("verdict", report.deferred() ? "DEFER" : report.verdict().name());
            entry.addProperty("detail", report.reason());
            array.add(entry);
        }
        return array;
    }
}
