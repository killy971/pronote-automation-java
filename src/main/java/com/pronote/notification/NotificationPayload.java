package com.pronote.notification;

import java.util.List;

/**
 * Notification message to be sent via one or more channels.
 */
public record NotificationPayload(
        String title,
        String body,
        Priority priority,
        List<String> tags) {

    public enum Priority { LOW, NORMAL, HIGH }

    /** Normalises a null {@code tags} list to an empty list. */
    public NotificationPayload {
        tags = tags != null ? tags : List.of();
    }
}
