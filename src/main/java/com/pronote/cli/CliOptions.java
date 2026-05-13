package com.pronote.cli;

import com.pronote.config.AppConfig;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Parses command-line arguments for {@link com.pronote.Main} into an immutable view.
 *
 * <p>Recognised flags (all optional):
 * <ul>
 *   <li>{@code --config <path>}    — config file path (default {@code config.yaml})</li>
 *   <li>{@code --mode <mode>}      — fetch / views / diff / validate (default {@code fetch})</li>
 *   <li>{@code --features <list>}  — comma-separated subset of feature names to enable</li>
 *   <li>{@code --dry-run}          — preview notifications without sending</li>
 * </ul>
 *
 * <p>Unknown {@code --features} names cause {@link #applyFeatureOverride} to throw.
 *
 * <p>This class is pure (no logging, no I/O) so it is fully unit-testable.
 */
public final class CliOptions {

    /** Set of feature names accepted by {@code --features}. */
    public static final Set<String> KNOWN_FEATURES =
            Set.of("assignments", "timetable", "grades", "evaluations", "schoolLife");

    private static final Path DEFAULT_CONFIG_PATH = Path.of("config.yaml");
    private static final String DEFAULT_MODE = "fetch";

    private final Path configPath;
    private final String mode;
    private final boolean dryRun;
    private final Optional<Set<String>> featureOverride;

    private CliOptions(Path configPath, String mode, boolean dryRun,
                       Optional<Set<String>> featureOverride) {
        this.configPath = configPath;
        this.mode = mode;
        this.dryRun = dryRun;
        this.featureOverride = featureOverride;
    }

    /** Parses {@code args} into an immutable view. Never returns null. */
    public static CliOptions parse(String[] args) {
        return new CliOptions(
                resolveConfigPath(args),
                resolveMode(args),
                isDryRun(args),
                resolveFeatureOverride(args));
    }

    public Path configPath() { return configPath; }
    public String mode()     { return mode; }
    public boolean dryRun()  { return dryRun; }
    public Optional<Set<String>> featureOverride() { return featureOverride; }

    /**
     * Applies {@code --features} to the given {@link AppConfig.FeaturesConfig}.
     * Pass the result of {@link #featureOverride()}; no-op when empty.
     *
     * @throws IllegalArgumentException if any feature name is unknown
     */
    public static void applyFeatureOverride(AppConfig.FeaturesConfig features, Set<String> enabled) {
        Set<String> unknown = new HashSet<>(enabled);
        unknown.removeAll(KNOWN_FEATURES);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown --features values: " + unknown + ". Valid: " + KNOWN_FEATURES);
        }
        features.setAssignments(enabled.contains("assignments"));
        features.setTimetable(enabled.contains("timetable"));
        features.setGrades(enabled.contains("grades"));
        features.setEvaluations(enabled.contains("evaluations"));
        features.setSchoolLife(enabled.contains("schoolLife"));
    }

    // -------------------------------------------------------------------------

    private static Path resolveConfigPath(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--config".equals(args[i])) return Path.of(args[i + 1]);
        }
        return DEFAULT_CONFIG_PATH;
    }

    private static String resolveMode(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--mode".equals(args[i])) return args[i + 1];
        }
        return DEFAULT_MODE;
    }

    private static boolean isDryRun(String[] args) {
        for (String arg : args) {
            if ("--dry-run".equals(arg)) return true;
        }
        return false;
    }

    private static Optional<Set<String>> resolveFeatureOverride(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--features".equals(args[i])) {
                return Optional.of(new HashSet<>(List.of(args[i + 1].split(","))));
            }
        }
        return Optional.empty();
    }
}
