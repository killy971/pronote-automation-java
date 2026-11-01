package com.pronote.pipeline;

import com.pronote.auth.PronoteSession;
import com.pronote.auth.SessionStore;
import com.pronote.client.PronoteHttpClient;
import com.pronote.config.AppConfig;
import com.pronote.config.SubjectEnricher;
import com.pronote.domain.Assignment;
import com.pronote.domain.CompetenceEvaluation;
import com.pronote.domain.Grade;
import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.TimetableEntry;
import com.pronote.scraper.AssignmentScraper;
import com.pronote.scraper.AttachmentDownloader;
import com.pronote.scraper.EvaluationScraper;
import com.pronote.scraper.GradeScraper;
import com.pronote.scraper.SchoolLifeScraper;
import com.pronote.scraper.TimetableScraper;

import java.nio.file.Path;
import java.util.List;

/** The production {@link FetchSource}: real scrapers over an established Pronote session. */
public final class PronoteFetchSource implements FetchSource {

    private final AppConfig config;
    private final PronoteSession session;
    private final PronoteHttpClient httpClient;
    private final SessionStore sessionStore;
    private final SubjectEnricher enricher;

    public PronoteFetchSource(AppConfig config, PronoteSession session, PronoteHttpClient httpClient,
                              SessionStore sessionStore, SubjectEnricher enricher) {
        this.config = config;
        this.session = session;
        this.httpClient = httpClient;
        this.sessionStore = sessionStore;
        this.enricher = enricher;
    }

    @Override
    public List<Assignment> fetchAssignments() {
        return new AssignmentScraper(enricher).fetch(session, httpClient, config);
    }

    @Override
    public List<TimetableEntry> fetchTimetable() {
        return new TimetableScraper(enricher).fetch(session, httpClient, config);
    }

    @Override
    public List<Grade> fetchGrades() {
        return new GradeScraper(enricher).fetch(session, httpClient, session.getPeriods());
    }

    @Override
    public List<CompetenceEvaluation> fetchEvaluations() {
        return new EvaluationScraper(enricher).fetch(session, httpClient, session.getPeriods());
    }

    @Override
    public List<SchoolLifeEvent> fetchSchoolLife() {
        return new SchoolLifeScraper(enricher).fetch(session, httpClient, session.getPeriods());
    }

    @Override
    public void downloadAttachments(List<Assignment> assignments, Path attachmentsDir) {
        new AttachmentDownloader(httpClient, session, attachmentsDir).processAttachments(assignments);
    }

    @Override
    public void saveSession() {
        sessionStore.save(session);
    }
}
