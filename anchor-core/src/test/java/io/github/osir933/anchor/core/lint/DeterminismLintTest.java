package io.github.osir933.anchor.core.lint;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Fails the build when core code uses something that can make two machines, or two runs, disagree.
 *
 * <p>A line can opt out with a trailing {@code // determinism-ok: <reason>} comment, which reviewers then
 * see in the diff.
 */
class DeterminismLintTest {

    private record Rule(Pattern pattern, String why) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("\\bMath\\.(sin|cos|tan|asin|acos|atan2?|sinh|cosh|tanh|exp|expm1|log|log10"
                    + "|log1p|pow|cbrt|hypot)\\s*\\("),
                    "use StrictMath: Math may use CPU intrinsics that differ in the last bit between machines"),
            new Rule(Pattern.compile("\\b(HashMap|HashSet)\\b"),
                    "iteration order follows hash codes; use TreeMap, LinkedHashMap, EnumMap or a sorted list"),
            new Rule(Pattern.compile("\\b(Set|Map)\\.(of|copyOf|ofEntries)\\s*\\("),
                    "immutable sets and maps iterate in an order randomised on every JVM start"),
            new Rule(Pattern.compile("Collectors\\.(toSet|toMap|groupingBy|partitioningBy|toUnmodifiableSet"
                    + "|toUnmodifiableMap)\\s*\\("),
                    "these collectors build hash-ordered containers"),
            new Rule(Pattern.compile("System\\.(currentTimeMillis|nanoTime)|Instant\\.now|LocalDateTime\\.now"
                    + "|\\bClock\\."),
                    "wall-clock time must never influence the simulation; budgets count work, not time"),
            new Rule(Pattern.compile("new\\s+Random\\b|Math\\.random|ThreadLocalRandom|SplittableRandom"
                    + "|SecureRandom"),
                    "use DeterministicRandom keyed from the world seed"),
            new Rule(Pattern.compile("\\.parallel\\(\\)|parallelStream\\(|ForkJoinPool"),
                    "parallel reductions combine results in an unpredictable order"),
            new Rule(Pattern.compile("identityHashCode"),
                    "identity hash codes differ between runs"));

    @Test
    void coreSourcesAreDeterministic() throws IOException {
        Path root = Path.of(System.getProperty("anchor.core.sourceDir", "src/main/java"));
        assertTrue(Files.isDirectory(root), "source directory not found: " + root.toAbsolutePath());
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String code = line.strip();
                    if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")
                            || line.contains("// determinism-ok:")) {
                        continue;
                    }
                    for (Rule rule : RULES) {
                        if (rule.pattern().matcher(line).find()) {
                            violations.add(root.relativize(file) + ":" + (i + 1) + ": " + code + "\n    -> "
                                    + rule.why());
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> "non-deterministic code in anchor-core:\n"
                + String.join("\n", violations));
    }
}
