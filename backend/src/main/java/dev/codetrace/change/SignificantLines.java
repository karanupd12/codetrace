package dev.codetrace.change;

/**
 * Finds the lines of a Java source file that contain code, as opposed to only whitespace or comments. A small lexer
 * tracks block comments, line comments, string and character literals and text blocks across the whole file, so a
 * {@code *} at the start of a wrapped expression is still code and a {@code /*} inside a string is not a comment.
 * Used to ignore edits that cannot change behaviour, such as added Javadoc or blank lines.
 */
public final class SignificantLines {
    private SignificantLines() {
    }

    private enum State { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR, TEXT_BLOCK }

    /** {@code result[i]} is true when line {@code i + 1} holds any code (1-based line numbers map to index - 1). */
    public static boolean[] of(String source) {
        String[] lines = source.split("\n", -1);
        boolean[] significant = new boolean[lines.length];
        State state = State.CODE;
        for (int ln = 0; ln < lines.length; ln++) {
            String line = lines[ln];
            if (state == State.LINE_COMMENT) state = State.CODE;
            if (state == State.STRING || state == State.CHAR) state = State.CODE; // unterminated literal: recover
            int i = 0;
            while (i < line.length()) {
                char c = line.charAt(i);
                char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
                switch (state) {
                    case CODE -> {
                        if (c == '/' && next == '/') {
                            state = State.LINE_COMMENT;
                            i = line.length();
                            continue;
                        }
                        if (c == '/' && next == '*') {
                            state = State.BLOCK_COMMENT;
                            i += 2;
                            continue;
                        }
                        if (!Character.isWhitespace(c)) significant[ln] = true;
                        if (c == '"' && line.startsWith("\"\"\"", i)) {
                            state = State.TEXT_BLOCK;
                            i += 3;
                            continue;
                        }
                        if (c == '"') state = State.STRING;
                        else if (c == '\'') state = State.CHAR;
                        i++;
                    }
                    case BLOCK_COMMENT -> {
                        if (c == '*' && next == '/') {
                            state = State.CODE;
                            i += 2;
                        } else {
                            i++;
                        }
                    }
                    case STRING, CHAR -> {
                        significant[ln] = true;
                        if (c == '\\') {
                            i += 2;
                            continue;
                        }
                        if ((state == State.STRING && c == '"') || (state == State.CHAR && c == '\'')) state = State.CODE;
                        i++;
                    }
                    case TEXT_BLOCK -> {
                        significant[ln] = true;
                        if (c == '\\') {
                            i += 2;
                            continue;
                        }
                        if (line.startsWith("\"\"\"", i)) {
                            state = State.CODE;
                            i += 3;
                            continue;
                        }
                        i++;
                    }
                    case LINE_COMMENT -> i = line.length();
                }
            }
            if (state == State.TEXT_BLOCK) significant[ln] = true;
        }
        return significant;
    }
}
