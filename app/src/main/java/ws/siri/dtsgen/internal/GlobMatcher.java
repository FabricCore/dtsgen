package ws.siri.dtsgen.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Matches dotted type names against package globs, in which {@code **} spans dots and
 * {@code *} matches within a single segment -- so {@code java.util.**} covers
 * {@code java.util.concurrent.Future} but {@code java.util.*} does not.
 */
public final class GlobMatcher {

    private static final GlobMatcher EMPTY = new GlobMatcher(List.of());

    private final List<Pattern> patterns;

    private GlobMatcher(List<Pattern> patterns) {
        this.patterns = patterns;
    }

    /** A matcher for the given globs; a null or empty collection matches nothing. */
    public static GlobMatcher of(Collection<String> globs) {
        if (globs == null || globs.isEmpty()) return EMPTY;
        List<Pattern> patterns = new ArrayList<>(globs.size());
        for (String glob : globs) patterns.add(compile(glob));
        return new GlobMatcher(patterns);
    }

    /** True when any glob matches the whole name. */
    public boolean matches(String dottedName) {
        for (Pattern p : patterns) {
            if (p.matcher(dottedName).matches()) return true;
        }
        return false;
    }

    private static Pattern compile(String glob) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            if (ch == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                re.append(".*");
                i++;
            } else if (ch == '*') {
                re.append("[^.]*");
            } else if ("\\.[]{}()+-^$|?".indexOf(ch) >= 0) {
                re.append('\\').append(ch);
            } else {
                re.append(ch);
            }
        }
        return Pattern.compile(re.toString());
    }
}
