package dev.codetrace.common.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsing of method signatures as stored for graph nodes, e.g. {@code put(Map<String,List<Integer>>,int...)}.
 * Commas inside generic type arguments do not separate parameters.
 */
public final class JavaSignatures {
    private JavaSignatures() {
    }

    /** The name before the parameter list, e.g. {@code put}. */
    public static String methodName(String signature) {
        int p = signature.indexOf('(');
        return p < 0 ? signature : signature.substring(0, p);
    }

    /** Parameter types as written, split at top-level commas. */
    public static List<String> parameters(String signature) {
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close < open || signature.substring(open + 1, close).isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = open + 1;
        for (int i = open + 1; i < close; i++) {
            char c = signature.charAt(i);
            if (c == '<' || c == '(') depth++;
            else if (c == '>' || c == ')') depth--;
            else if (c == ',' && depth == 0) {
                out.add(signature.substring(start, i));
                start = i + 1;
            }
        }
        out.add(signature.substring(start, close));
        return out;
    }

    /** Minimum and maximum number of arguments the signature accepts; varargs make the maximum unbounded. */
    public static int[] arityRange(String signature) {
        List<String> params = parameters(signature);
        if (params.isEmpty()) return new int[] {0, 0};
        int n = params.size();
        return params.get(n - 1).trim().endsWith("...") ? new int[] {n - 1, Integer.MAX_VALUE} : new int[] {n, n};
    }

    public static boolean accepts(String signature, int argCount) {
        int[] range = arityRange(signature);
        return argCount >= range[0] && argCount <= range[1];
    }
}
