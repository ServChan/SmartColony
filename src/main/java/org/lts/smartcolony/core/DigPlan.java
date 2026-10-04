package org.lts.smartcolony.core;

import java.util.List;

public interface DigPlan {
    int size();

    MinePlan.Cell cell(int index);

    Pos standFor(int index);

    List<Pos> routeTo(int index);

    Pos entrance();
}
