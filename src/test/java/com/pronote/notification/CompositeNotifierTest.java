package com.pronote.notification;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CompositeNotifierTest {

    private static NotificationPayload samplePayload() {
        return new NotificationPayload(
                "synthetic title",
                "synthetic body",
                NotificationPayload.Priority.NORMAL,
                List.of("test"));
    }

    /** Captures every payload it receives so tests can assert fan-out behaviour. */
    private static class RecordingNotifier implements NotificationService {
        final List<NotificationPayload> received = new ArrayList<>();
        @Override
        public void send(NotificationPayload payload) {
            received.add(payload);
        }
    }

    private static class FailingNotifier implements NotificationService {
        final AtomicInteger calls = new AtomicInteger();
        @Override
        public void send(NotificationPayload payload) throws NotificationException {
            calls.incrementAndGet();
            throw new NotificationException("synthetic failure");
        }
    }

    @Test
    void send_emptyServiceList_isNoOp() {
        CompositeNotifier composite = new CompositeNotifier(List.of());
        // Should not throw — emptiness is normal in tests/dry-run paths
        assertDoesNotThrow(() -> composite.send(samplePayload()));
    }

    @Test
    void send_fansOutToAllChannels() throws Exception {
        RecordingNotifier a = new RecordingNotifier();
        RecordingNotifier b = new RecordingNotifier();
        CompositeNotifier composite = new CompositeNotifier(List.of(a, b));

        NotificationPayload payload = samplePayload();
        composite.send(payload);

        assertEquals(1, a.received.size());
        assertEquals(1, b.received.size());
        assertSame(payload, a.received.get(0));
        assertSame(payload, b.received.get(0));
    }

    @Test
    void send_partialFailure_otherChannelsStillCalled() throws Exception {
        FailingNotifier failing = new FailingNotifier();
        RecordingNotifier ok = new RecordingNotifier();
        CompositeNotifier composite = new CompositeNotifier(List.of(failing, ok));

        // Per-channel failure must NOT propagate when at least one channel succeeded
        assertDoesNotThrow(() -> composite.send(samplePayload()));

        assertEquals(1, failing.calls.get());
        assertEquals(1, ok.received.size());
    }

    @Test
    void send_allFail_throwsNotificationException() {
        FailingNotifier a = new FailingNotifier();
        FailingNotifier b = new FailingNotifier();
        CompositeNotifier composite = new CompositeNotifier(List.of(a, b));

        NotificationService.NotificationException ex = assertThrows(
                NotificationService.NotificationException.class,
                () -> composite.send(samplePayload()));
        assertTrue(ex.getMessage().contains("2"));
        assertEquals(1, a.calls.get());
        assertEquals(1, b.calls.get());
    }

    @Test
    void payload_nullTags_normalisedToEmptyList() {
        NotificationPayload p = new NotificationPayload("t", "b",
                NotificationPayload.Priority.LOW, null);
        assertNotNull(p.tags());
        assertTrue(p.tags().isEmpty());
    }
}
