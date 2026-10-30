package flowscope.implementation;

import java.util.regex.Pattern;

/** Turns a statement into its shape: whitespace collapsed and every value replaced by ?. */
final class SqlText {
    private static final int MAX_LENGTH = 4000;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w$.\"])-?\\d+(?:\\.\\d+)?(?![\\w$\"])");
    private static final Pattern PLACEHOLDER_LIST = Pattern.compile("\\(\\s*\\?(?:\\s*,\\s*\\?)+\\s*\\)");

    private SqlText() {
    }

    static String normalize(String sql) {
        if (sql == null) {
            return "";
        }
        String s = WHITESPACE.matcher(sql).replaceAll(" ").trim();
        s = STRING_LITERAL.matcher(s).replaceAll("?");
        s = NUMBER.matcher(s).replaceAll("?");
        s = PLACEHOLDER_LIST.matcher(s).replaceAll("(?)");
        return s.length() > MAX_LENGTH ? s.substring(0, MAX_LENGTH) : s;
    }

    static boolean isSelect(String sql) {
        if (sql == null) {
            return false;
        }
        String s = sql.stripLeading();
        return s.regionMatches(true, 0, "SELECT", 0, 6) || s.regionMatches(true, 0, "WITH", 0, 4);
    }
}
