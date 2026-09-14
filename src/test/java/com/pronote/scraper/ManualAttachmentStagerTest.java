package com.pronote.scraper;

import com.pronote.domain.Assignment;
import com.pronote.domain.AttachmentRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link ManualAttachmentStager}: the copy rule (absent / stale / current), the
 * {@code localPath} contract the assignment view depends on, and the pruning boundary that
 * keeps Pronote-downloaded attachments out of reach.
 */
class ManualAttachmentStagerTest {

    private static Assignment manualAssignment(String id, AttachmentRef... refs) {
        Assignment a = new Assignment();
        a.setId("manual:" + id);
        a.setSubject("SYN_MATHS");
        a.setAttachments(List.of(refs));
        return a;
    }

    private static AttachmentRef fileRef(String fileName, Path source) {
        AttachmentRef ref = new AttachmentRef();
        ref.setStableId("manual:x|" + fileName);
        ref.setFileName(fileName);
        ref.setUploadedFile(true);
        ref.setSourcePath(source.toString());
        return ref;
    }

    private static Path writeFile(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    // -------------------------------------------------------------------------
    // Copying
    // -------------------------------------------------------------------------

    @Test
    void copiesSourceAndSetsLocalPath(@TempDir Path dir) throws IOException {
        Path source = writeFile(dir.resolve("src"), "releve.png", "original");
        Path attachments = dir.resolve("attachments");
        AttachmentRef ref = fileRef("releve.png", source);

        new ManualAttachmentStager(attachments).stage(List.of(manualAssignment("syn-1", ref)));

        Path staged = attachments.resolve("manual_syn-1").resolve("releve.png");
        assertTrue(Files.exists(staged));
        assertEquals("original", Files.readString(staged));
        // The assignment view builds its href by relativizing localPath — without it the
        // attachment is filtered out of the rendered card entirely.
        assertEquals(staged.toAbsolutePath().toString(), ref.getLocalPath());
    }

    @Test
    void secondRunDoesNotRewriteAnUnchangedFile(@TempDir Path dir) throws IOException {
        Path source = writeFile(dir.resolve("src"), "releve.png", "original");
        Path attachments = dir.resolve("attachments");
        ManualAttachmentStager stager = new ManualAttachmentStager(attachments);

        stager.stage(List.of(manualAssignment("syn-1", fileRef("releve.png", source))));
        Path staged = attachments.resolve("manual_syn-1").resolve("releve.png");
        FileTime firstWrite = Files.getLastModifiedTime(staged);

        stager.stage(List.of(manualAssignment("syn-1", fileRef("releve.png", source))));

        assertEquals(firstWrite, Files.getLastModifiedTime(staged));
    }

    @Test
    void reCopiesWhenTheSourceChangedUnderTheSameName(@TempDir Path dir) throws IOException {
        Path source = writeFile(dir.resolve("src"), "releve.png", "original");
        Path attachments = dir.resolve("attachments");
        ManualAttachmentStager stager = new ManualAttachmentStager(attachments);

        stager.stage(List.of(manualAssignment("syn-1", fileRef("releve.png", source))));

        // Re-crop the screenshot in place: same name, new content.
        Files.writeString(source, "edited version");
        stager.stage(List.of(manualAssignment("syn-1", fileRef("releve.png", source))));

        assertEquals("edited version",
                Files.readString(attachments.resolve("manual_syn-1").resolve("releve.png")));
    }

    @Test
    void missingSource_leavesLocalPathNullAndDoesNotThrow(@TempDir Path dir) {
        Path attachments = dir.resolve("attachments");
        AttachmentRef ref = fileRef("absent.png", dir.resolve("nope.png"));

        assertDoesNotThrow(() ->
                new ManualAttachmentStager(attachments)
                        .stage(List.of(manualAssignment("syn-1", ref))));

        assertNull(ref.getLocalPath());
    }

    @Test
    void hyperlinkRefIsIgnored(@TempDir Path dir) {
        AttachmentRef ref = new AttachmentRef();
        ref.setStableId("https://example.invalid/doc");
        ref.setFileName("Corrigé");
        ref.setUploadedFile(false);
        ref.setUrl("https://example.invalid/doc");
        Path attachments = dir.resolve("attachments");

        new ManualAttachmentStager(attachments).stage(List.of(manualAssignment("syn-1", ref)));

        assertNull(ref.getLocalPath());
        assertFalse(Files.exists(attachments.resolve("manual_syn-1")));
    }

    // -------------------------------------------------------------------------
    // Pruning
    // -------------------------------------------------------------------------

    @Test
    void dropsFileNoLongerDeclaredForTheSameEntry(@TempDir Path dir) throws IOException {
        Path srcA = writeFile(dir.resolve("src"), "a.png", "a");
        Path srcB = writeFile(dir.resolve("src"), "b.png", "b");
        Path attachments = dir.resolve("attachments");
        ManualAttachmentStager stager = new ManualAttachmentStager(attachments);

        stager.stage(List.of(manualAssignment("syn-1",
                fileRef("a.png", srcA), fileRef("b.png", srcB))));

        // b.png removed from the YAML
        stager.stage(List.of(manualAssignment("syn-1", fileRef("a.png", srcA))));

        Path stagedDir = attachments.resolve("manual_syn-1");
        assertTrue(Files.exists(stagedDir.resolve("a.png")));
        assertFalse(Files.exists(stagedDir.resolve("b.png")));
    }

    @Test
    void dropsWholeDirectoryOfDeletedEntry(@TempDir Path dir) throws IOException {
        Path source = writeFile(dir.resolve("src"), "releve.png", "a");
        Path attachments = dir.resolve("attachments");
        ManualAttachmentStager stager = new ManualAttachmentStager(attachments);

        stager.stage(List.of(manualAssignment("syn-1", fileRef("releve.png", source))));
        assertTrue(Files.exists(attachments.resolve("manual_syn-1")));

        // Entry removed from manual-entries.yaml altogether
        stager.stage(List.of());

        assertFalse(Files.exists(attachments.resolve("manual_syn-1")));
    }

    @Test
    void neverTouchesPronoteDownloadedAttachments(@TempDir Path dir) throws IOException {
        Path attachments = dir.resolve("attachments");
        Path pronote = writeFile(attachments.resolve("12345"), "cours.pdf", "pdf");

        // No manual entries at all — the most aggressive pruning case.
        new ManualAttachmentStager(attachments).stage(List.of());

        assertTrue(Files.exists(pronote));
    }

    @Test
    void nonManualAssignmentsAreIgnored(@TempDir Path dir) throws IOException {
        Path source = writeFile(dir.resolve("src"), "releve.png", "a");
        Path attachments = dir.resolve("attachments");
        Assignment pronoteAssignment = new Assignment();
        pronoteAssignment.setId("12345");
        pronoteAssignment.setAttachments(List.of(fileRef("releve.png", source)));

        new ManualAttachmentStager(attachments).stage(List.of(pronoteAssignment));

        assertFalse(Files.exists(attachments.resolve("12345")));
    }
}
