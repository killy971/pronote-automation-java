package com.pronote.views;

import com.pronote.config.AppConfig;
import com.pronote.domain.Assignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates and writes the static HTML assignment view file.
 *
 * <p>On each invocation, {@link #render} produces a single {@code index.html} in the configured
 * output directory. The file lists all upcoming assignments (dueDate &ge; today), grouped by date
 * then subject. The operation is idempotent — the file is overwritten on every run.
 */
public class AssignmentViewRenderer {

    private static final Logger log = LoggerFactory.getLogger(AssignmentViewRenderer.class);

    private final AppConfig.AssignmentViewConfig viewConfig;
    private final AssignmentHtmlGenerator generator = new AssignmentHtmlGenerator();

    public AssignmentViewRenderer(AppConfig.AssignmentViewConfig viewConfig) {
        this.viewConfig = viewConfig;
    }

    /**
     * Generates the assignment HTML view and writes it to the configured output directory.
     *
     * @param assignments the full assignment snapshot (upcoming ones are filtered internally)
     */
    public void render(List<Assignment> assignments) {
        Path outDir = Path.of(viewConfig.getOutputDirectory()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create assignment view output directory: " + outDir, e);
        }

        log.info("Generating assignment view in {}", outDir);
        String html = generator.generate(assignments, outDir);
        Path file = outDir.resolve("index.html");
        try {
            Files.writeString(file, html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write assignment view: " + file, e);
        }
        log.info("Assignment view written to {}", file);
    }
}
