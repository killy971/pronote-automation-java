package com.pronote.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Fan-out notifier: sends to all configured channels.
 *
 * <p>Failures from individual channels are logged but do not stop
 * delivery to the remaining channels.
 */
public class CompositeNotifier implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(CompositeNotifier.class);

    private final List<NotificationService> services;

    public CompositeNotifier(List<NotificationService> services) {
        this.services = services;
    }

    @Override
    public void send(NotificationPayload payload) throws NotificationException {
        if (services.isEmpty()) {
            log.debug("No notification channels configured — skipping send.");
            return;
        }

        int failures = 0;
        for (NotificationService service : services) {
            try {
                service.send(payload);
            } catch (NotificationException e) {
                log.error("Notification channel {} failed: {}", service.getClass().getSimpleName(), e.getMessage());
                failures++;
            }
        }

        if (failures == services.size()) {
            throw new NotificationException("All " + failures + " notification channel(s) failed.");
        }
    }
}
