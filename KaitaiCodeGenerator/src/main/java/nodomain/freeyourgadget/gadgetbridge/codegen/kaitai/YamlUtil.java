package nodomain.freeyourgadget.gadgetbridge.codegen.kaitai;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Small type-checked accessors over a raw SnakeYAML document tree (plain
 * {@code Map<String,Object>}/{@code List<Object>}, the shape
 * {@code org.yaml.snakeyaml.Yaml#load} returns).
 */
public final class YamlUtil {
    private YamlUtil() {
    }

    public static void requireKeys(
            final String file,
            final String where,
            final Map<String, Object> map,
            final Set<String> allowed,
            final Set<String> required
    ) {
        for (final String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw new KaitaiLintException(file, where, "unsupported key \"" + key + "\" (allowed: " + allowed + ")");
            }
        }
        for (final String key : required) {
            if (!map.containsKey(key)) {
                throw new KaitaiLintException(file, where, "missing required key \"" + key + "\"");
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(final String file, final String where, final Object o) {
        if (!(o instanceof Map)) {
            throw new KaitaiLintException(file, where, "expected a mapping, got " + describe(o));
        }
        return (Map<String, Object>) o;
    }

    /**
     * Like {@link #asMap}, but for mappings whose keys are not strings (e.g. enum value tables keyed by int).
     */
    @SuppressWarnings("unchecked")
    public static Map<Object, Object> asRawMap(final String file, final String where, final Object o) {
        if (!(o instanceof Map)) {
            throw new KaitaiLintException(file, where, "expected a mapping, got " + describe(o));
        }
        return (Map<Object, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(final String file, final String where, final Object o) {
        if (!(o instanceof List)) {
            throw new KaitaiLintException(file, where, "expected a sequence, got " + describe(o));
        }
        return (List<Object>) o;
    }

    public static String asString(final String file, final String where, final Object o) {
        if (!(o instanceof String)) {
            throw new KaitaiLintException(file, where, "expected a string, got " + describe(o));
        }
        return (String) o;
    }

    public static long toLong(final String file, final String where, final Object o) {
        if (!(o instanceof Number)) {
            throw new KaitaiLintException(file, where, "expected an integer, got " + describe(o));
        }
        return ((Number) o).longValue();
    }

    public static String describe(final Object o) {
        return o == null ? "nothing" : o.getClass().getSimpleName() + " (" + o + ")";
    }
}
