# Backlog

Deferred work, ordered roughly by usefulness. These are things the codebase is already shaped to accept — not speculative redesigns.

---

### 1. Mark scraped (Pronote) assignments as `done`

The strikethrough/badge UI in `AssignmentHtmlGenerator` and the day-card "N devoirs" filter in `TimetableViewRenderer` already react to `Assignment.isDone()`. Today only `manual:` entries can set it (via the YAML's `done: true`); Pronote-fetched assignments cannot.

**Sketch**: introduce an `overrides.yaml` (or extend `manual-entries.yaml` with an `overrides:` block) keyed by Pronote assignment ID:
```yaml
overrides:
  - assignmentId: "12345#abcdef"
    done: true
    hidden: false
    note: "fait en avance"
```
Apply overrides after `AssignmentScraper` returns, before snapshot + diff. The override list itself is snapshot-independent and shouldn't affect diff churn (apply post-diff so an override flip doesn't generate a "modified" notification).

---

### 2. Surface `memo` on upcoming-eval cards in the assignment view

`TimetableEntry.memo` is populated by `TimetableScraper` but only the `lessonLabel` reaches the eval banner/cards in `AssignmentHtmlGenerator`. The teacher's free-text memo is usually the substantive part ("apporter la calculatrice", "réviser chap. 4–6"). Trivial: include `memo` (truncated) in `renderEvalBanner` and `renderDateGroup` when present.

---

### 3. "Nouveau" tag on assignments newly assigned in the last 2 days

`Assignment.assignedDate` is collected but never rendered. Adding a small tag to assignments whose `assignedDate >= today.minusDays(2)` makes the page useful even on runs where nothing was *added* (catches the case where the user missed a few cron firings). Lives in `AssignmentHtmlGenerator.renderAssignmentCard` — needs one extra field on `Assignment` (or just compute at render time) and a CSS rule.

---

### 4. Cross-link the two "evaluation" worlds

From a timetable day-view eval badge / assignment-view eval card, link to the corresponding `CompetenceEvaluation` once it appears in the `evaluations/` snapshot. Match on same date + same subject + similar `name`/`lessonLabel`. Bidirectional: bilan-view cards can also link back to the timetable date page.

---

### 5. Weekly summary view

`AssignmentHtmlGenerator` recently gained weekly separators. Natural next surface: a `weekly.html` per upcoming week showing totals (devoirs count, eval count, cancellations), per-subject breakdown, all evals — the Sunday-evening overview. Lives in a new `WeeklySummaryHtmlGenerator` + renderer; consumes the existing assignment + timetable snapshots.

---

### 6. Trimester selector on the subject-average panel

`EvaluationSummaryHtmlGenerator` currently shows a subject-average badge that aggregates across all periods. Add a pure-CSS `:target`-based or `<details>`-driven T1/T2/T3 selector that filters the computed average to one period — no JS, no extra dependencies. The `CompetenceEvaluation.periodName` data is already available.

---

### General docs hygiene

The subject/teacher reference table baked into `manual-entries.yaml.example` is the user's personal cheat sheet and will go stale as teachers change. Worth treating it as live documentation — re-verify each school year. Same for the example evaluation `periodName` values.
