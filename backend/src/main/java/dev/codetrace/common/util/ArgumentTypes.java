package dev.codetrace.common.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Storage form of the per-argument type hints of a call site: hints joined by the ASCII unit separator, which never
 * occurs in a type name (commas do, inside generics). An empty hint means "type not known from the text".
 */
public final class ArgumentTypes {
    private static final char SEPARATOR = '\u001F';

    private ArgumentTypes() {
    }

    public static String encode(List<String> hints) {
        return String.join(String.valueOf(SEPARATOR), hints);
    }

    /** Always returns exactly {@code argCount} hints; stored data that does not fit counts as all unknown. */
    public static List<String> decode(String stored, int argCount) {
        if (argCount <= 0) return List.of();
        List<String> hints = new ArrayList<>(argCount);
        if (stored != null && !stored.isEmpty() || argCount == 1) {
            int start = 0;
            String s = stored == null ? "" : stored;
            for (int i = s.indexOf(SEPARATOR); i >= 0; i = s.indexOf(SEPARATOR, start)) {
                hints.add(s.substring(start, i));
                start = i + 1;
            }
            hints.add(s.substring(start));
        }
        return hints.size() == argCount ? hints : Collections.nCopies(argCount, "");
    }
}
