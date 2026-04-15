package com.pronote.views;

import com.pronote.config.AppConfig;
import com.pronote.domain.CompetenceEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates and writes the static HTML evaluation view file.
 *
 * <p>On each invocation, {@link #render} produces a single {@code index.html} in the configured
 * output directory, listing the most recent competence evaluations (newest first).
 * The operation is idempotent — the file is overwritten on every run.
 */
public class EvaluationViewRenderer {

    private static final Logger log = LoggerFactory.getLogger(EvaluationViewRenderer.class);

    private final AppConfig.EvaluationViewConfig viewConfig;
    private final EvaluationHtmlGenerator generator = new EvaluationHtmlGenerator();

    public EvaluationViewRenderer(AppConfig.EvaluationViewConfig viewConfig) {
        this.viewConfig = viewConfig;
    }

    /**
     * Generates the evaluation HTML view and writes it to the configured output directory.
     *
     * @param evaluations the full evaluation snapshot
     */
    public void render(List<CompetenceEvaluation> evaluations) {
        Path outDir = resolveOutDir();

        log.info("Generating evaluation view in {}", outDir);
        String html = generator.generate(evaluations, viewConfig.getMaxEntries());
        Path file = outDir.resolve("index.html");
        try {
            Files.writeString(file, html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write evaluation view: " + file, e);
        }
        log.info("Evaluation view written to {}", file);
    }

    /**
     * Generates the evaluation summary view (by trimester / subject) and writes
     * {@code summary.html} to the same output directory as {@link #render}.
     *
     * @param evaluations the full evaluation snapshot
     */
    public void renderSummary(List<CompetenceEvaluation> evaluations) {
        Path outDir = resolveOutDir();

        log.info("Generating evaluation summary view in {}", outDir);
        String html = new EvaluationSummaryHtmlGenerator().generate(evaluations);
        Path file = outDir.resolve("summary.html");
        try {
            Files.writeString(file, html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write evaluation summary view: " + file, e);
        }
        log.info("Evaluation summary view written to {}", file);
    }

    // -------------------------------------------------------------------------

    private Path resolveOutDir() {
        Path outDir = Path.of(viewConfig.getOutputDirectory()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create evaluation view output directory: " + outDir, e);
        }
        return outDir;
    }
}
