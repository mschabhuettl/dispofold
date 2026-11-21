package app.dispofold.plugins.sp5.port;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Port of sp5export/demand.py: per-shift staffing into per-area demand windows. Segments and windows are int arrays
 * ({from, to, count} and {from, to, min, max}); a max of null (Python None) is held as {@link #NONE}.
 */
final class Demand {

    /** Python None in a window's max. */
    static final int NONE = Integer.MIN_VALUE;

    /** Tuple order of (from, to, min, max) windows; None (only in max) never meets an int in the same place. */
    static final Comparator<int[]> TUPLE = (a, b) -> Arrays.compare(a, b);

    private Demand() {
    }

    /** Staffing over one calendar day as (from, to, count) segments between shift boundaries. */
    static List<int[]> coverageSegments(List<int[]> today, List<int[]> yesterday) {
        List<int[]> spans = new ArrayList<>(today);
        for (int[] y : yesterday) {
            if (y[1] > TimeUtil.DAY) {
                spans.add(new int[] { y[0] - TimeUtil.DAY, y[1] - TimeUtil.DAY, y[2] });
            }
        }
        TreeSet<Integer> set = new TreeSet<>(List.of(0, TimeUtil.DAY));
        for (int[] s : spans) {
            set.add(Math.min(Math.max(s[0], 0), TimeUtil.DAY));
            set.add(Math.min(Math.max(s[1], 0), TimeUtil.DAY));
        }
        Integer[] points = set.toArray(new Integer[0]);
        List<int[]> segments = new ArrayList<>();
        for (int i = 0; i + 1 < points.length; i++) {
            int a = points[i];
            int b = points[i + 1];
            int c = 0;
            for (int[] s : spans) {
                if (s[0] <= a && b <= s[1]) {
                    c += s[2];
                }
            }
            if (c != 0) {
                segments.add(new int[] { a, b, c });
            }
        }
        return segments;
    }

    /** Median of the counts; halfway between two counts (x.5) is rounded up. */
    static int medianUp(List<Integer> counts) {
        int[] v = counts.stream().mapToInt(Integer::intValue).sorted().toArray();
        int n = v.length;
        return n % 2 == 1 ? v[n / 2] : Math.floorDiv(v[n / 2 - 1] + v[n / 2] + 1, 2);
    }

    /** Nearest-rank percentile of the counts. */
    static int percentileUp(List<Integer> counts, int percent) {
        int[] v = counts.stream().mapToInt(Integer::intValue).sorted().toArray();
        return v[Math.max(1, Math.floorDiv(percent * v.length + 99, 100)) - 1];
    }

    /** Demand windows (from, to, min, max) for the staffing of duties that start on one day (see demand.py). */
    static List<int[]> startDayWindows(List<int[]> profile) {
        List<int[]> head = new ArrayList<>(profile);
        List<int[]> layers = new ArrayList<>();
        if (profile.stream().anyMatch(x -> x[0] >= TimeUtil.DAY)) {
            int p = profile.stream().filter(x -> x[0] < TimeUtil.DAY).mapToInt(x -> x[0]).max()
                    .orElseThrow(() -> new Py.Raised("ValueError"));
            head = new ArrayList<>(profile.stream().filter(x -> x[0] < p).toList());
            List<int[]> steps = profile.stream().filter(x -> x[0] >= p).toList();
            int belowMin = 0;
            int belowMax = 0;
            for (int k = steps.size() - 1; k >= 0; k--) {
                int[] x = steps.get(k);
                int layerMax = x[3] - belowMax;
                int layerMin = Math.min(x[2] - belowMin, layerMax);
                if (layerMax > 0) {
                    layers.add(new int[] { p, x[1], layerMin, layerMax });
                }
                belowMin += layerMin;
                belowMax += layerMax;
            }
            java.util.Collections.reverse(layers);
        }
        List<int[]> windows = new ArrayList<>();
        for (int[] x : head) {
            int[] last = windows.isEmpty() ? null : windows.get(windows.size() - 1);
            if (last != null && last[1] == x[0] && last[2] == x[2] && last[3] == x[3] && x[1] - last[0] <= TimeUtil.DAY) {
                windows.set(windows.size() - 1, new int[] { last[0], x[1], x[2], x[3] });
            } else {
                windows.add(new int[] { x[0], x[1], x[2], x[3] });
            }
        }
        for (int i = layers.size() - 1; i >= 0; i--) {
            int[] x = layers.get(i);
            int[] last = windows.isEmpty() ? null : windows.get(windows.size() - 1);
            if (last != null && last[1] == x[0] && last[2] == x[2] && last[3] == x[3] && x[1] - last[0] <= TimeUtil.DAY) {
                windows.set(windows.size() - 1, new int[] { last[0], x[1], x[2], x[3] });
                layers.remove(i);
                break;
            }
        }
        List<int[]> all = new ArrayList<>(windows);
        all.addAll(layers);
        all.sort(TUPLE);
        return all;
    }
}
