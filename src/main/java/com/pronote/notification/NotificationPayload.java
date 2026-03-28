package com.pronote.notification;

import java.util.List;

/**
 * Notification message to be sent via one or more channels.
 */
public class NotificationPayload {

    public enum Priority { LOW, NORMAL, HIGH }

    private final String title;
    private final String body;
    private final Priority priority;
    private final List<String> tags;

    public NotificationPayload(String title, String body, Priority priority, List<String> tags) {
        this.title = title;
        this.body = body;
        this.priority = priority;
        this.tags = tags != null ? tags : List.of();
    }

    public String getTitle()       { return title; }
    public String getBody()        { return body; }
    public Priority getPriority()  { return priority; }
    public List<String> getTags()  { return tags; }
}
