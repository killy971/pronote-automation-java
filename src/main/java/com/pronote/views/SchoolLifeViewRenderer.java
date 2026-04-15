package com.pronote.views;

import com.pronote.config.AppConfig;
import com.pronote.domain.SchoolLifeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates and writes the static HTML school-life view file.
 *
 * <p>On each invocation, {@link #render} produces a single {@code index.html} in the configured
 * output directory listing the most recent school-life events (newest first).
 * The operation is idempotent — the file is overwritten on every run.
 */
public class SchoolLifeViewRenderer {

    private static final Logger log = LoggerFactory.getLogger(SchoolLifeViewRenderer.class);

    private final AppConfig.SchoolLifeViewConfig viewConfig;
    private final SchoolLifeHtmlGenerator generator = new SchoolLifeHtmlGenerator();

    public SchoolLifeViewRenderer(AppConfig.SchoolLifeViewConfig viewConfig) {
        this.viewConfig = viewConfig;
    }

    /**
     * Generates the school-life HTML view and writes it to the configured output directory.
     *
     * @param events the full school-life snapshot
     */
    public void render(List<SchoolLifeEvent> events) {
        Path outDir = Path.of(viewConfig.getOutputDirectory()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create school-life view output directory: " + outDir, e);
        }

        log.info("Generating school-life view in {}", outDir);
        String html = generator.generate(events, viewConfig.getMaxEntries());
        Path file = outDir.resolve("index.html");
        try {
            Files.writeString(file, html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write school-life view: " + file, e);
        }
        log.info("School-life view written to {}", file);
    }
}
