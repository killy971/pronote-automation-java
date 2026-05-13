package com.pronote.cli;

import com.pronote.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CliOptionsTest {

    @Test
    void parse_noArgs_returnsAllDefaults() {
        CliOptions opts = CliOptions.parse(new String[0]);
        assertEquals(Path.of("config.yaml"), opts.configPath());
        assertEquals("fetch", opts.mode());
        assertFalse(opts.dryRun());
        assertTrue(opts.featureOverride().isEmpty());
    }

    @Test
    void parse_configFlag_picksUpValue() {
        CliOptions opts = CliOptions.parse(new String[]{"--config", "/tmp/other.yaml"});
        assertEquals(Path.of("/tmp/other.yaml"), opts.configPath());
    }

    @Test
    void parse_modeFlag_picksUpValue() {
        for (String mode : new String[]{"fetch", "views", "diff", "validate"}) {
            CliOptions opts = CliOptions.parse(new String[]{"--mode", mode});
            assertEquals(mode, opts.mode());
        }
    }

    @Test
    void parse_dryRunFlag_isPositional() {
        // --dry-run takes no value, so it works at any position
        assertTrue(CliOptions.parse(new String[]{"--dry-run"}).dryRun());
        assertTrue(CliOptions.parse(new String[]{"--mode", "diff", "--dry-run"}).dryRun());
        assertFalse(CliOptions.parse(new String[]{"--mode", "fetch"}).dryRun());
    }

    @Test
    void parse_featuresFlag_returnsParsedSet() {
        CliOptions opts = CliOptions.parse(
                new String[]{"--features", "assignments,timetable,grades"});
        assertTrue(opts.featureOverride().isPresent());
        Set<String> enabled = opts.featureOverride().get();
        assertEquals(Set.of("assignments", "timetable", "grades"), enabled);
    }

    @Test
    void parse_combinedFlags_allHonored() {
        CliOptions opts = CliOptions.parse(new String[]{
                "--config", "/tmp/c.yaml",
                "--mode", "views",
                "--features", "timetable",
                "--dry-run"
        });
        assertEquals(Path.of("/tmp/c.yaml"), opts.configPath());
        assertEquals("views", opts.mode());
        assertTrue(opts.dryRun());
        assertEquals(Set.of("timetable"), opts.featureOverride().orElseThrow());
    }

    @Test
    void parse_trailingFlagWithoutValue_isIgnored() {
        // `--config` at the end with no following arg should not crash; defaults stand
        CliOptions opts = CliOptions.parse(new String[]{"--dry-run", "--config"});
        assertEquals(Path.of("config.yaml"), opts.configPath());
        assertTrue(opts.dryRun());
    }

    @Test
    void applyFeatureOverride_enablesOnlyListedFeatures() {
        AppConfig.FeaturesConfig features = new AppConfig.FeaturesConfig();
        // pre-populate with everything enabled
        features.setAssignments(true);
        features.setTimetable(true);
        features.setGrades(true);
        features.setEvaluations(true);
        features.setSchoolLife(true);

        CliOptions.applyFeatureOverride(features, Set.of("timetable", "grades"));

        assertFalse(features.isAssignments());
        assertTrue(features.isTimetable());
        assertTrue(features.isGrades());
        assertFalse(features.isEvaluations());
        assertFalse(features.isSchoolLife());
    }

    @Test
    void applyFeatureOverride_unknownName_throws() {
        AppConfig.FeaturesConfig features = new AppConfig.FeaturesConfig();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> CliOptions.applyFeatureOverride(features, Set.of("typo", "timetable")));
        assertTrue(ex.getMessage().contains("typo"));
    }

    @Test
    void applyFeatureOverride_emptySet_disablesEverything() {
        AppConfig.FeaturesConfig features = new AppConfig.FeaturesConfig();
        features.setAssignments(true);
        features.setTimetable(true);

        CliOptions.applyFeatureOverride(features, Set.of());

        assertFalse(features.isAssignments());
        assertFalse(features.isTimetable());
        assertFalse(features.isGrades());
        assertFalse(features.isEvaluations());
        assertFalse(features.isSchoolLife());
    }
}
