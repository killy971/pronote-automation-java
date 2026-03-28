package com.pronote.notification;

/**
 * Abstraction for a notification channel (ntfy, email, etc.).
 */
public interface NotificationService {

    /**
     * Sends the given payload.
     *
     * @param payload the notification to send
     * @throws NotificationException if delivery fails
     */
    void send(NotificationPayload payload) throws NotificationException;

    /** Checked exception for notification delivery failures. */
    class NotificationException extends Exception {
        public NotificationException(String message) { super(message); }
        public NotificationException(String message, Throwable cause) { super(message, cause); }
    }
}
