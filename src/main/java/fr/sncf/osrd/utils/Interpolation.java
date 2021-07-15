package fr.sncf.osrd.utils;

import java.util.NavigableMap;

public class Interpolation {
    public static Double interpolate(NavigableMap<Double, Double> map, double x) {
        // last pair of (position, speed) before the given position
        var entryBefore = map.floorEntry(x);

        // first pair of (position, speed) after the given position
        var entryAfter = map.ceilingEntry(x);

        if (entryAfter == null)
            entryAfter = map.lastEntry();
        if (entryBefore == null)
            entryBefore = map.firstEntry();

        var speedBefore = entryBefore.getValue();
        var positionBefore = entryBefore.getKey();
        var speedAfter = entryAfter.getValue();
        var positionAfter = entryAfter.getKey();

        // If the position is the same as the entry before, avoids dividing by 0
        if (Math.abs(positionAfter - positionBefore) < 1e-5)
            return speedBefore;
        else {
            // slope of the curve between points before and after
            var slope = (speedAfter - speedBefore) / (positionAfter - positionBefore);
            return speedBefore + (x - positionBefore) * slope;
        }
    }
}
