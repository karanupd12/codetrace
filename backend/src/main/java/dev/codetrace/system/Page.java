package dev.codetrace.system;

import java.util.List;

/** Bounded page of results. */
public record Page<T>(List<T> items, int limit, int offset, long total) {
}
