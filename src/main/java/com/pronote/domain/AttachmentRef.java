package com.pronote.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Reference to a single attachment on a Pronote assignment.
 *
 * <p>Two attachment types exist in Pronote's {@code ListePieceJointe} array:
 * <ul>
 *   <li>G=0 (hyperlink): an external URL — not downloaded; {@code localPath} stays null.</li>
 *   <li>G=1 (uploaded file): a Pronote-hosted file — downloaded locally; {@code localPath}
 *       is set after a successful download.</li>
 * </ul>
 *
 * <p>Idempotency key: {@code stableId} is the Pronote {@code N} field, which is stable
 * across sessions (unlike the constructed download URL for G=1, which is AES-encrypted
 * with the current session key and therefore session-scoped).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AttachmentRef {

    /**
     * Stable, session-independent identifier for this attachment.
     * <ul>
     *   <li>G=1 (uploaded file): {@code assignmentId + "|" + fileName} — the Pronote {@code N}
     *       field is intentionally NOT used here because it is session-scoped (it contains a
     *       per-session token after the {@code #} separator and changes on every login).</li>
     *   <li>G=0 (hyperlink): the URL itself, which is externally hosted and stable.</li>
     * </ul>
     * Used as the idempotency key: same stable ID ⇒ same local file ⇒ skip re-download.
     */
    private String stableId;

    /**
     * Original filename or hyperlink label ({@code L} field in Pronote response).
     */
    private String fileName;

    /**
     * For G=0 (hyperlink) only: the stable, externally-hosted destination URL.
     * Always {@code null} for G=1 uploaded files — their download URL is session-scoped
     * and is carried in the transient {@link #downloadUrl} field instead.
     */
    private String url;

    /**
     * Session-scoped authenticated download URL for G=1 uploaded files.
     *
     * <p>This field is transient and {@code @JsonIgnore}: it is never persisted to disk
     * and never compared by the diff engine. It is populated by {@code AssignmentScraper}
     * for the current run and used only by {@code AttachmentDownloader}.
     */
    @JsonIgnore
    private transient String downloadUrl;

    /**
     * True if this is a Pronote-hosted uploaded file (G=1) that can be downloaded.
     * False if this is a hyperlink (G=0).
     */
    private boolean uploadedFile;

    /**
     * Absolute path to the locally downloaded file.
     * Null if not yet downloaded or if {@code uploadedFile} is false.
     */
    private String localPath;

    /**
     * MIME type from the Content-Type response header, populated after download.
     * Null if not downloaded or if the server did not return a Content-Type.
     */
    private String mimeType;

    public AttachmentRef() {}

    public String getStableId()               { return stableId; }
    public void setStableId(String stableId)  { this.stableId = stableId; }

    public String getFileName()               { return fileName; }
    public void setFileName(String fileName)  { this.fileName = fileName; }

    public String getUrl()                    { return url; }
    public void setUrl(String url)            { this.url = url; }

    public String getDownloadUrl()                        { return downloadUrl; }
    public void setDownloadUrl(String downloadUrl)        { this.downloadUrl = downloadUrl; }

    public boolean isUploadedFile()                   { return uploadedFile; }
    public void setUploadedFile(boolean uploadedFile) { this.uploadedFile = uploadedFile; }

    public String getLocalPath()              { return localPath; }
    public void setLocalPath(String path)     { this.localPath = path; }

    public String getMimeType()               { return mimeType; }
    public void setMimeType(String mimeType)  { this.mimeType = mimeType; }

    @Override
    public String toString() {
        return "AttachmentRef{stableId='" + stableId + "', fileName='" + fileName
                + "', uploadedFile=" + uploadedFile + ", localPath='" + localPath + "'}";
    }
}
