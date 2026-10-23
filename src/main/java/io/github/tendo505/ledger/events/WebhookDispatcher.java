package io.github.tendo505.ledger.events;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivers outbox events to webhooks, at least once: a delivery is marked
 * done only after the receiver answers 2xx, and otherwise retried with
 * exponential backoff (1 s, 2 s, 4 s, ... capped at an hour) until it
 * succeeds or runs out of attempts. Receivers should de-duplicate by event
 * id, since a delivery can arrive twice if a response is lost.
 */
@Component
public class WebhookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private record Due(long eventId, String webhookId, int attempts, String payload, String type, String url, String secret) {}

    private final JdbcClient db;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration backoff;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public WebhookDispatcher(JdbcClient db, Clock clock,
                             @Value("${ledger.webhook-max-attempts}") int maxAttempts,
                             @Value("${ledger.webhook-backoff:1s}") Duration backoff) {
        this.db = db;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
    }

    @Scheduled(fixedDelayString = "${ledger.webhook-poll-interval}")
    public void dispatch() {
        Instant now = clock.instant();
        List<Due> due = db.sql("""
                SELECT d.event_id, d.webhook_id, d.attempts, e.payload, e.type, w.url, w.secret
                FROM deliveries d JOIN events e ON e.id = d.event_id JOIN webhooks w ON w.id = d.webhook_id
                WHERE d.delivered_at IS NULL AND d.failed = FALSE AND d.next_attempt_at <= :now
                ORDER BY d.event_id LIMIT 50
                """)
                .param("now", odt(now))
                .query((rs, n) -> new Due(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7)))
                .list();
        for (Due d : due) deliver(d);
    }

    private void deliver(Due d) {
        Instant now = clock.instant();
        String error;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(d.url()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("Ledger-Event-Id", "evt_" + d.eventId())
                    .header("Ledger-Event-Type", d.type())
                    .header("Ledger-Signature", Signatures.header(d.secret(), now.getEpochSecond(), d.payload()))
                    .POST(HttpRequest.BodyPublishers.ofString(d.payload()))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 == 2) {
                db.sql("UPDATE deliveries SET delivered_at = :now, attempts = attempts + 1 WHERE event_id = :e AND webhook_id = :w")
                        .param("now", odt(now)).param("e", d.eventId()).param("w", d.webhookId())
                        .update();
                return;
            }
            error = "HTTP " + response.statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        int attempts = d.attempts() + 1;
        boolean giveUp = attempts >= maxAttempts;
        long delayMillis = Math.min(backoff.toMillis() << Math.min(attempts - 1, 20), Duration.ofHours(1).toMillis());
        db.sql("""
                UPDATE deliveries SET attempts = :attempts, failed = :failed, next_attempt_at = :next, last_error = :error
                WHERE event_id = :e AND webhook_id = :w
                """)
                .param("attempts", attempts).param("failed", giveUp).param("next", odt(now.plusMillis(delayMillis)))
                .param("error", error.length() > 500 ? error.substring(0, 500) : error)
                .param("e", d.eventId()).param("w", d.webhookId())
                .update();
        if (giveUp) log.warn("giving up on event {} for webhook {} after {} attempts: {}", d.eventId(), d.webhookId(), attempts, error);
    }

    private static OffsetDateTime odt(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
