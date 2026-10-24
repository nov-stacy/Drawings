package com.example.drawingsarchive;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Keeps years descending while preserving the manual order within each year. */
final class ArtworkOrder {
    private ArtworkOrder() { }

    static <T> void sortByYear(List<T> items, Function<T, String> year) {
        // List.sort is stable: equal years retain their saved manual order.
        items.sort((left, right) -> Integer.compare(yearNumber(year.apply(right)),
                yearNumber(year.apply(left))));
    }

    private static int yearNumber(String year) {
        try { return Integer.parseInt(year); }
        catch (NumberFormatException error) { return Integer.MIN_VALUE; }
    }

    static <T> void putFirstInYear(List<T> items, T item, Function<T, String> year) {
        items.remove(item);
        items.add(0, item);
        sortByYear(items, year);
    }

    static <T> boolean moveWithinYear(List<T> items, List<T> visible, T item,
                                     int target, Function<T, String> year) {
        int source = visible.indexOf(item);
        if (source < 0 || target < 0 || target >= visible.size() || source == target
                || !year.apply(item).equals(year.apply(visible.get(target)))) return false;

        // Replace only visible slots so hidden works keep their positions under filters/search.
        ArrayList<Integer> slots = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (visible.contains(items.get(i))) slots.add(i);
        }
        visible.remove(source);
        visible.add(target, item);
        for (int i = 0; i < slots.size(); i++) items.set(slots.get(i), visible.get(i));
        return true;
    }
}
