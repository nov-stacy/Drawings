package com.example.drawingsarchive;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ArtworkOrderTest {
    public static void main(String[] args) {
        Work a = new Work("A", "2025"), b = new Work("B", "2025");
        Work c = new Work("C", "2024"), d = new Work("D", "2024");
        Work e = new Work("E", "2023");
        ArrayList<Work> all = list(c, a, e, d, b);
        ArtworkOrder.sortByYear(all, work -> work.year);
        expect(all, a, b, c, d, e);
        // Sorting a restored list must keep both the old and the new manual order.
        ArtworkOrder.sortByYear(all, work -> work.year);
        expect(all, a, b, c, d, e);

        ArrayList<Work> visible = new ArrayList<>(all);
        check(ArtworkOrder.moveWithinYear(all, visible, a, 1, work -> work.year), "Move forward within year");
        expect(all, b, a, c, d, e);
        expect(visible, b, a, c, d, e);
        check(ArtworkOrder.moveWithinYear(all, visible, a, 0, work -> work.year), "Move backward within year");
        expect(all, a, b, c, d, e);
        check(!ArtworkOrder.moveWithinYear(all, visible, c, 1, work -> work.year), "Cannot enter a newer year");
        check(!ArtworkOrder.moveWithinYear(all, visible, a, 2, work -> work.year), "Cannot enter an older year");
        check(!ArtworkOrder.moveWithinYear(all, visible, e, 3, work -> work.year), "Single work cannot leave its year");
        check(!ArtworkOrder.moveWithinYear(all, visible, a, -1, work -> work.year), "Ignore empty grid area");
        check(!ArtworkOrder.moveWithinYear(all, visible, a, all.size(), work -> work.year), "Ignore past last card");
        expect(all, a, b, c, d, e);
        expect(visible, a, b, c, d, e);

        Work hidden = new Work("hidden", "2024");
        all = list(a, b, c, hidden, d, e);
        visible = list(c, d, e);
        check(ArtworkOrder.moveWithinYear(all, visible, c, 1, work -> work.year), "Move with active search/filter");
        expect(all, a, b, d, hidden, c, e);
        expect(visible, d, c, e);
        check(!ArtworkOrder.moveWithinYear(all, visible, d, 2, work -> work.year), "Filtered move respects year boundary");
        ArtworkOrder.sortByYear(all, work -> work.year);
        expect(all, a, b, d, hidden, c, e);
        visible = new ArrayList<>(all);
        check(ArtworkOrder.moveWithinYear(all, visible, c, 2, work -> work.year), "Move across several cards within year");
        expect(all, a, b, c, d, hidden, e);

        Work added = new Work("new", "2024");
        ArtworkOrder.putFirstInYear(all, added, work -> work.year);
        expect(all, a, b, added, c, d, hidden, e);
        // Moving an older work to an existing newer year puts it first there.
        e.year = "2025";
        ArtworkOrder.putFirstInYear(all, e, work -> work.year);
        expect(all, e, a, b, added, c, d, hidden);
        // Moving a newer work to an older year also puts it first there.
        a.year = "2024";
        ArtworkOrder.putFirstInYear(all, a, work -> work.year);
        expect(all, e, b, a, added, c, d, hidden);
        b.year = "2022";
        ArtworkOrder.putFirstInYear(all, b, work -> work.year);
        expect(all, e, a, added, c, d, hidden, b);
        all = new ArrayList<>(all);
        ArtworkOrder.sortByYear(all, work -> work.year);
        expect(all, e, a, added, c, d, hidden, b);

        Work unknown = new Work("unknown", "");
        all.add(0, unknown);
        ArtworkOrder.sortByYear(all, work -> work.year);
        expect(all, e, a, added, c, d, hidden, b, unknown);
        all.clear();
        ArtworkOrder.sortByYear(all, work -> work.year);
        check(!ArtworkOrder.moveWithinYear(all, all, a, 0, work -> work.year), "Empty collection");
        System.out.println("ArtworkOrder: all checks passed");
    }

    private static ArrayList<Work> list(Work... works) { return new ArrayList<>(Arrays.asList(works)); }
    private static void expect(List<Work> actual, Work... expected) {
        check(actual.equals(Arrays.asList(expected)), actual + " != " + Arrays.toString(expected));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class Work {
        final String name;
        String year;
        Work(String name, String year) { this.name = name; this.year = year; }
        @Override public String toString() { return name + ":" + year; }
    }
}
