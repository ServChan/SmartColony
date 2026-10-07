package org.lts.smartcolony.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MineFrames {
    public static final int SPACING = 3;
    public static final int LAG = 3;

    private MineFrames() {
    }

    public static List<Fit> frames(MinePlan plan, int progress) {
        int dug = Math.min(progress - LAG, plan.size());
        List<Fit> result = new ArrayList<>();
        if (dug <= plan.depth()) {
            return result;
        }
        Set<Pos> reserved = reserved(plan);
        Set<Pos> seen = new HashSet<>();
        for (int index = plan.depth(); index < dug; index++) {
            MinePlan.Cell cell = plan.cell(index);
            Pos previous = plan.standFor(index);
            int run = run(plan, index);
            if (run % SPACING != 0 || cell.kind() == MinePlan.Kind.MAIN && run % MinePlan.SPACING == 0) {
                continue;
            }
            Pos feet = cell.feet();
            int ax = Integer.signum(feet.x() - previous.x());
            int az = Integer.signum(feet.z() - previous.z());
            if (Math.abs(ax) + Math.abs(az) != 1) {
                continue;
            }
            int px = -az;
            int pz = ax;
            Fit.Part beam = px != 0 ? Fit.Part.LOG_X : Fit.Part.LOG_Z;
            List<Fit> frame = new ArrayList<>();
            for (int side : new int[]{-1, 1}) {
                frame.add(new Fit(feet.offset(px * side, 0, pz * side), Fit.Part.LOG_Y));
                frame.add(new Fit(feet.offset(px * side, 1, pz * side), Fit.Part.LOG_Y));
                frame.add(new Fit(feet.offset(px * side, 2, pz * side), beam));
            }
            frame.add(new Fit(feet.offset(0, 2, 0), beam));
            boolean clear = true;
            for (Fit fit : frame) {
                if (reserved.contains(fit.pos()) || seen.contains(fit.pos())) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                for (Fit fit : frame) {
                    seen.add(fit.pos());
                }
                result.addAll(frame);
            }
        }
        return result;
    }

    public static Set<Pos> reserved(MinePlan plan) {
        Set<Pos> cells = new HashSet<>();
        for (int index = 0; index < plan.size(); index++) {
            MinePlan.Cell cell = plan.cell(index);
            for (int h = 0; h < cell.height(); h++) {
                cells.add(cell.feet().above(h));
            }
            Pos stand = plan.standFor(index);
            cells.add(stand);
            cells.add(stand.above(1));
        }
        cells.add(plan.entrance());
        cells.add(plan.entrance().above(1));
        return cells;
    }

    private static int run(MinePlan plan, int index) {
        int r = index - plan.depth();
        int segmentSize = MinePlan.SPACING + 2 * MinePlan.BRANCH_LENGTH;
        int offset = r % segmentSize;
        if (offset < MinePlan.SPACING) {
            return (r / segmentSize) * MinePlan.SPACING + offset + 1;
        }
        return offset < MinePlan.SPACING + MinePlan.BRANCH_LENGTH ? offset - MinePlan.SPACING + 1
                : offset - MinePlan.SPACING - MinePlan.BRANCH_LENGTH + 1;
    }
}
