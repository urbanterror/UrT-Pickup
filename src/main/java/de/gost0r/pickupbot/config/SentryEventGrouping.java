package de.gost0r.pickupbot.config;

import io.sentry.SentryEvent;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackFrame;

import java.util.List;

/** Stable grouping across changing IDs, exception messages, wrappers and release line numbers. */
public final class SentryEventGrouping {

    private SentryEventGrouping() {
    }

    public static SentryEvent apply(SentryEvent event) {
        if (event.getFingerprints() != null && !event.getFingerprints().isEmpty()) {
            return event;
        }
        List<SentryException> exceptions = event.getExceptions();
        if (exceptions != null && !exceptions.isEmpty()) {
            // Sentry orders the cause chain deepest-first. Suppressed exceptions are secondary.
            SentryException cause = exceptions.stream()
                    .filter(exception -> exception.getMechanism() == null
                            || !"suppressed".equals(exception.getMechanism().getType()))
                    .findFirst().orElse(exceptions.get(0));
            List<SentryStackFrame> frames = cause.getStacktrace() == null
                    ? null : cause.getStacktrace().getFrames();
            String origin = "unknown";
            String appOrigin = "unknown";
            if (frames != null && !frames.isEmpty()) {
                // Protocol frames are oldest-first; the last frame is the throwing method.
                origin = method(frames.get(frames.size() - 1));
                for (int i = frames.size() - 1; i >= 0; i--) {
                    SentryStackFrame frame = frames.get(i);
                    if (frame.getModule() != null
                            && frame.getModule().startsWith("de.gost0r.pickupbot.")) {
                        appOrigin = method(frame);
                        break;
                    }
                }
            }
            event.setFingerprints(List.of("urt-pickup-exception-v1",
                    String.valueOf(cause.getModule()) + "." + cause.getType(), origin, appOrigin));
        } else if (event.getMessage() != null && event.getMessage().getMessage() != null) {
            // Keep placeholders, not formatted values (match IDs, user IDs, URLs, etc.).
            event.setFingerprints(List.of("urt-pickup-log-v1", String.valueOf(event.getLogger()),
                    event.getMessage().getMessage()));
        }
        return event;
    }

    private static String method(SentryStackFrame frame) {
        String function = String.valueOf(frame.getFunction()).replaceAll("(lambda\\$.*)\\$\\d+$", "$1");
        return frame.getModule() + "." + function;
    }
}
