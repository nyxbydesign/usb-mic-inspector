package dev.usbmic.inspector;

import java.time.Instant;
import java.util.ArrayDeque;

/** Bounded process-memory evidence only. It deliberately has no persistence or device access. */
final class ExperimentHistory {
    private static final int MAX_EVENTS = 96;
    private static final int MAX_EVENT_CHARS = 1024;
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();
    private static long discarded;

    private ExperimentHistory() {}

    static synchronized void record(String message) {
        String entry = "[" + Instant.now() + "] " + String.valueOf(message)
                .replace("\r", "\\r").replace("\n", "\\n");
        if (entry.length() > MAX_EVENT_CHARS) {
            String suffix = "... [truncated]";
            entry = entry.substring(0, MAX_EVENT_CHARS - suffix.length()) + suffix;
        }
        if (EVENTS.size() == MAX_EVENTS) {
            EVENTS.removeFirst();
            discarded++;
        }
        EVENTS.addLast(entry);
    }

    static synchronized String snapshot() {
        StringBuilder result = new StringBuilder("Experiment history (process memory only; history resets on process restart).\n"
                + "Retains the latest " + MAX_EVENTS + " events, up to " + MAX_EVENT_CHARS + " characters each. "
                + "Older events discarded: " + discarded + ".\n");
        if (EVENTS.isEmpty()) result.append("No experiment or probe events recorded in this process.\n");
        for (String entry : EVENTS) result.append(entry).append('\n');
        return result.toString();
    }

    static String failure(RuntimeException failure) {
        return failure.getClass().getName() + ": " + failure.getMessage();
    }
}
