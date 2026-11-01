package com.pronote.pipeline;

import com.pronote.config.AppConfig;
import com.pronote.domain.Assignment;
import com.pronote.domain.CompetenceEvaluation;
import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.TimetableEntry;
import com.pronote.views.AssignmentViewRenderer;
import com.pronote.views.EvaluationViewRenderer;
import com.pronote.views.GitPublisher;
import com.pronote.views.PortalIndexHtmlGenerator;
import com.pronote.views.SchoolLifeViewRenderer;
import com.pronote.views.SubjectColorResolver;
import com.pronote.views.SubjectIconResolver;
import com.pronote.views.TimetableViewRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static-HTML view generation shared by all modes: per-type renderers, the portal index, and
 * the optional GitHub Pages publication.
 *
 * <p>The render methods do not gate themselves — each runner decides which views to render.
 * Subject colours and icons are resolved from the timetable for every view, so an assignment
 * card or an evaluation card is the same colour as the lesson it belongs to.
 */
final class ViewPipeline {

    private static final Logger log = LoggerFactory.getLogger(ViewPipeline.class);

    private final AppConfig config;
    private final AppConfig.FeaturesConfig features;

    ViewPipeline(AppConfig config) {
        this.config = config;
        this.features = config.getFeatures();
    }

    /** True when both the feature flag and the {@code *View.enabled} config are set. */
    boolean timetableEnabled()   { return features.isTimetable()   && config.getTimetableView().isEnabled(); }
    boolean assignmentsEnabled() { return features.isAssignments() && config.getAssignmentView().isEnabled(); }
    boolean evaluationsEnabled() { return features.isEvaluations() && config.getEvaluationView().isEnabled(); }
    boolean schoolLifeEnabled()  { return features.isSchoolLife()  && config.getSchoolLifeView().isEnabled(); }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    void renderTimetable(List<TimetableEntry> timetable, List<Assignment> assignments) {
        TimetableViewRenderer renderer = new TimetableViewRenderer(config.getTimetableView());
        renderer.setColorResolver(colors(timetable));
        renderer.setIconResolver(icons(timetable));
        renderer.render(timetable, assignments);
    }

    void renderAssignments(List<Assignment> assignments, List<TimetableEntry> timetable) {
        AssignmentViewRenderer renderer = new AssignmentViewRenderer(config.getAssignmentView());
        renderer.setColorResolver(colors(timetable));
        renderer.setIconResolver(icons(timetable));
        renderer.render(assignments, timetable);
    }

    void renderEvaluations(List<CompetenceEvaluation> evaluations, List<TimetableEntry> timetable) {
        EvaluationViewRenderer renderer = new EvaluationViewRenderer(config.getEvaluationView());
        renderer.setColorResolver(colors(timetable));
        renderer.setIconResolver(icons(timetable));
        renderer.render(evaluations);
        renderer.renderSummary(evaluations);
    }

    void renderSchoolLife(List<SchoolLifeEvent> schoolLife) {
        new SchoolLifeViewRenderer(config.getSchoolLifeView()).render(schoolLife);
    }

    /**
     * Pronote's own colours ride on the timetable entries, so the timetable is the source for
     * every view.
     */
    private SubjectColorResolver colors(List<TimetableEntry> timetable) {
        return SubjectColorResolver.from(config.getSubjectColors(), timetable);
    }

    /**
     * Like the colours, icons read the timetable so a {@code subjectIcons.icons} key written as
     * either the raw Pronote subject or its enriched name matches in every view.
     */
    private SubjectIconResolver icons(List<TimetableEntry> timetable) {
        return SubjectIconResolver.from(config.getSubjectIcons(), timetable);
    }

    // -------------------------------------------------------------------------
    // Portal index
    // -------------------------------------------------------------------------

    /**
     * Generates the portal index (one card per enabled view), writes it to
     * {@code ./data/views/index.html}, and returns the HTML (the publisher needs it as well).
     */
    String writePortalIndex() {
        String html = new PortalIndexHtmlGenerator().generate(portalSections());
        try {
            Path dir = Path.of("./data/views");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("index.html"), html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write portal index to ./data/views/index.html", e);
        }
        return html;
    }

    private List<PortalIndexHtmlGenerator.Section> portalSections() {
        List<PortalIndexHtmlGenerator.Section> sections = new ArrayList<>();
        if (timetableEnabled()) {
            sections.add(new PortalIndexHtmlGenerator.Section(
                    "📅", "Emploi du temps", "Planning des prochains jours",
                    "timetable/index.html"));
        }
        if (assignmentsEnabled()) {
            sections.add(new PortalIndexHtmlGenerator.Section(
                    "📝", "Devoirs", "Travail à venir",
                    "assignments/index.html"));
        }
        if (evaluationsEnabled()) {
            sections.add(new PortalIndexHtmlGenerator.Section(
                    "📊", "Évaluations", "Compétences évaluées",
                    "evaluations/index.html"));
            sections.add(new PortalIndexHtmlGenerator.Section(
                    "📋", "Bilan", "Par trimestre et par matière",
                    "evaluations/summary.html"));
        }
        if (schoolLifeEnabled()) {
            sections.add(new PortalIndexHtmlGenerator.Section(
                    "🏫", "Vie scolaire", "Absences, retards et observations",
                    "school-life/index.html"));
        }
        return sections;
    }

    // -------------------------------------------------------------------------
    // GitHub Pages publication
    // -------------------------------------------------------------------------

    /** Which view directories a publication mirrors. */
    record Published(boolean timetable, boolean assignments, boolean evaluations, boolean schoolLife) {}

    /** Mirrors the selected view directories into the GitHub Pages repo, if configured. */
    void publish(Path dataDir, String portalHtml, Published views) {
        if (!config.getViewPublish().isEnabled()) return;

        Map<String, Path> viewDirs = new LinkedHashMap<>();
        Map<Path, Path> recursiveMirrors = new LinkedHashMap<>();
        if (views.timetable()) {
            viewDirs.put("timetable", Path.of(config.getTimetableView().getOutputDirectory())
                    .toAbsolutePath().normalize());
        }
        if (views.assignments()) {
            Path assignViewDir = Path.of(config.getAssignmentView().getOutputDirectory())
                    .toAbsolutePath().normalize();
            viewDirs.put("assignments", assignViewDir);
            if (config.getViewPublish().isPublishAttachments()) {
                Path attachSource = DataLayout.attachmentsDir(dataDir).toAbsolutePath().normalize();
                Path relFromView = assignViewDir.relativize(attachSource);
                Path destRelToRepo = Path.of(config.getViewPublish().getTargetSubdir())
                        .resolve("assignments").resolve(relFromView).normalize();
                recursiveMirrors.put(attachSource, destRelToRepo);
            }
        }
        if (views.evaluations()) {
            viewDirs.put("evaluations", Path.of(config.getEvaluationView().getOutputDirectory())
                    .toAbsolutePath().normalize());
        }
        if (views.schoolLife()) {
            viewDirs.put("school-life", Path.of(config.getSchoolLifeView().getOutputDirectory())
                    .toAbsolutePath().normalize());
        }
        log.info("Publishing view files to GitHub Pages repo...");
        new GitPublisher().publish(viewDirs, recursiveMirrors,
                Map.of("index.html", portalHtml), config.getViewPublish());
    }
}
