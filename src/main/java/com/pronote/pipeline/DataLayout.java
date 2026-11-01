package com.pronote.pipeline;

import java.nio.file.Path;

/** Locations inside the data directory that more than one pipeline stage needs. */
final class DataLayout {

    private DataLayout() {}

    /**
     * Where assignment attachments live — Pronote downloads and staged manual files alike, so the
     * assignment view and the GitHub Pages mirror find both in one tree.
     */
    static Path attachmentsDir(Path dataDir) {
        return dataDir.resolve("snapshots").resolve("assignments").resolve("attachments");
    }
}
