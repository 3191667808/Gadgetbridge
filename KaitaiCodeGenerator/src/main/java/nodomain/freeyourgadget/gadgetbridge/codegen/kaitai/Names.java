package nodomain.freeyourgadget.gadgetbridge.codegen.kaitai;

import java.util.regex.Pattern;

/**
 * Conversion helpers between snake_case (.ksy convention) and camelCase/PascalCase (Kotlin convention).
 */
public final class Names {
    private static final Pattern SNAKE_CASE = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)*");
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    private Names() {
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean isSnakeCase(final String s) {
        return s != null && SNAKE_CASE.matcher(s).matches();
    }

    public static String toCamelCase(final String snake) {
        final StringBuilder sb = new StringBuilder();
        final var m = WORD.matcher(snake);
        boolean first = true;
        while (m.find()) {
            final String w = m.group();
            if (first) {
                sb.append(w);
                first = false;
            } else {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w, 1, w.length());
            }
        }
        return sb.toString();
    }

    public static String toPascalCase(final String snake) {
        final String camel = toCamelCase(snake);
        if (camel.isEmpty()) {
            return camel;
        }
        return Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
    }
}
