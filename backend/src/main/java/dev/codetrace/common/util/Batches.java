package dev.codetrace.common.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Splits collections into fixed-size batches, e.g. to keep SQL {@code IN (...)} lists bounded. */
public final class Batches {
    /** Default batch size for SQL parameter lists and batched inserts. */
    public static final int SIZE = 1000;

    private Batches() {
    }

    public static <T> List<List<T>> partition(Collection<T> items, int size) {
        List<T> all = new ArrayList<>(items);
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) out.add(all.subList(i, Math.min(all.size(), i + size)));
        return out;
    }

    public static <T> List<List<T>> partition(Collection<T> items) {
        return partition(items, SIZE);
    }
}
