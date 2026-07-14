package io.github.intisy.ai.claude;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small hand-rolled helpers reproducing the JS runtime coercions the ported TS relies on
 * implicitly ({@code Number(...)}, {@code parseFloat(...)}, {@code parseInt(str, 10)}, and truthy
 * checks) -- kept package-private and shared by {@link AnthropicRequestTranslator} and
 * {@link ClaudeQuotaParser} rather than duplicated. Intentionally simplified vs the full
 * ECMA-262 ToNumber/parseFloat/parseInt grammars (no numeric separators, no arbitrary
 * Unicode whitespace beyond {@link String#trim()} / {@code \s}, no BigInt) -- sufficient for the
 * realistic inputs these two ports ever see (HTTP header values, JSON-parsed numbers/strings).
 * No java.net/nio/reflection/threads/System.getenv -- TeaVM-transpilable.
 */
final class JsCoercion {

    private JsCoercion() {
    }

    // Matches JS's `if (x)` truthiness: false for null/undefined, false, "", 0/-0/NaN; every
    // other value (including empty objects/arrays, which this port represents as Map/List) is
    // truthy.
    static boolean isTruthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return !((String) v).isEmpty();
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return d != 0.0 && !Double.isNaN(d);
        }
        return true;
    }

    // JS falsy-string check used for `existing || default` / `header || default` patterns: only
    // null or "" is falsy for a string (matches isTruthy(String) above, spelled out for callers
    // that already know they have a String or null).
    static boolean isFalsyString(String s) {
        return s == null || s.isEmpty();
    }

    private static final Pattern JS_NUMBER = Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");

    /**
     * Approximates {@code Number(raw)} for a non-null string: trims, empty -> 0, "Infinity"/
     * "-Infinity" -> +-Infinity, a JS hex-integer literal ("0x..") -> that integer, a decimal
     * literal -> its value, anything else -> NaN. Callers only ever pass this a non-null header
     * value already guarded by a truthy/empty check, matching the TS call sites
     * ({@code if (unified) { const secs = Number(unified); ... } }).
     */
    static double jsNumber(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) return 0.0;
        if (s.equals("Infinity") || s.equals("+Infinity")) return Double.POSITIVE_INFINITY;
        if (s.equals("-Infinity")) return Double.NEGATIVE_INFINITY;
        boolean neg = s.startsWith("-");
        String unsigned = (s.startsWith("+") || s.startsWith("-")) ? s.substring(1) : s;
        if (unsigned.length() > 2 && (unsigned.startsWith("0x") || unsigned.startsWith("0X"))) {
            try {
                long v = Long.parseLong(unsigned.substring(2), 16);
                return neg ? -v : v;
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        if (!JS_NUMBER.matcher(s).matches()) return Double.NaN;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static final Pattern PARSE_FLOAT_PREFIX = Pattern.compile(
            "^[ \\t\\n\\r\\f\\u000B]*([+-]?(?:Infinity|\\d+\\.?\\d*(?:[eE][+-]?\\d+)?|\\.\\d+(?:[eE][+-]?\\d+)?))");

    /**
     * Approximates JS {@code parseFloat(raw)}: skips leading whitespace, then takes the LONGEST
     * leading substring that forms a valid number (trailing garbage after a valid number is
     * ignored, unlike {@link #jsNumber}); no leading valid number -> NaN.
     */
    static double jsParseFloat(String raw) {
        if (raw == null) return Double.NaN;
        Matcher m = PARSE_FLOAT_PREFIX.matcher(raw);
        if (!m.lookingAt()) return Double.NaN;
        String token = m.group(1);
        if (token.equals("Infinity") || token.equals("+Infinity")) return Double.POSITIVE_INFINITY;
        if (token.equals("-Infinity")) return Double.NEGATIVE_INFINITY;
        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static final Pattern PARSE_INT_PREFIX_10 = Pattern.compile("^[ \\t\\n\\r\\f\\u000B]*([+-]?\\d+)");

    /**
     * Approximates JS {@code parseInt(raw, 10)} (radix always 10 for this port's call sites --
     * with an explicit radix of 10, JS's parseInt does NOT special-case a "0x" prefix as hex,
     * unlike the no-radix/radix-0 form). No leading digits -> NaN.
     */
    static double jsParseInt10(String raw) {
        if (raw == null) return Double.NaN;
        Matcher m = PARSE_INT_PREFIX_10.matcher(raw);
        if (!m.lookingAt()) return Double.NaN;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
