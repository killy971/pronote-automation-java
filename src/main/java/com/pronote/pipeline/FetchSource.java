package com.pronote.pipeline;

import com.pronote.domain.Assignment;
import com.pronote.domain.CompetenceEvaluation;
import com.pronote.domain.Grade;
import com.pronote.domain.SchoolLifeEvent;
import com.pronote.domain.TimetableEntry;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything the fetch pipeline needs from Pronote once a session is established:
 * the five scrapers, the attachment download, and the final session save.
 *
 * <p>This is the only network-facing seam of {@code --mode fetch}. Production uses
 * {@link PronoteFetchSource}; the end-to-end regression tests substitute canned data so the
 * whole post-login pipeline (manual-entry merge, empty-fetch guard, diff, filters, report,
 * notification, snapshot write, views) runs exactly as in production, offline.
 *
 * <p>Each fetch method is called at most once per run, only for enabled data types, in the
 * order assignments → timetable → grades → evaluations → school life.
 */
public interface FetchSource {

    List<Assignment> fetchAssignments();

    List<TimetableEntry> fetchTimetable();

    List<Grade> fetchGrades();

    List<CompetenceEvaluation> fetchEvaluations();

    List<SchoolLifeEvent> fetchSchoolLife();

    /** Downloads the Pronote-hosted (G=1) attachments of {@code assignments} into {@code attachmentsDir}. */
    void downloadAttachments(List<Assignment> assignments, Path attachmentsDir);

    /** Persists the session once the last Pronote request of the run is done. */
    void saveSession();
}
