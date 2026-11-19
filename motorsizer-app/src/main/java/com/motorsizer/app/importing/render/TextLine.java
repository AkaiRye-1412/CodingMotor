package com.motorsizer.app.importing.render;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 按基线聚类成的一行文字；{@code runs} 按 x 升序。 */
public record TextLine(double oy, List<TextRun> runs) {

    public Set<String> tokens() {
        Set<String> out = new LinkedHashSet<>();
        for (TextRun r : runs) {
            out.add(r.token());
        }
        return out;
    }

    public TextRun first() {
        return runs.get(0);
    }

    public double minX() {
        double v = Double.MAX_VALUE;
        for (TextRun r : runs) {
            v = Math.min(v, r.x0());
        }
        return v;
    }
}
