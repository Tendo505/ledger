package io.github.tendo505.ledger.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional outbox. {@link #publish} must run inside the transaction
 * that makes the change, so the event is committed exactly when the change
 * is (never an event for a rolled-back transfer, never a transfer without
 * its event). One delivery row per registered webhook is created alongside;
 * {@link WebhookDispatcher} sends them afterwards, at least once.
 */
@Component
public class Outbox {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final Clock clock;

    public Outbox(JdbcClient db, ObjectMapper json, Clock clock) {
        this.db = db;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long publish(String type, Object data) {
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        KeyHolder key = new GeneratedKeyHolder();
        // The id isn't known until the insert, so the payload's "id" is filled in by a second statement.
        db.sql("INSERT INTO events (type, payload, created_at) VALUES (:type, '', :now)")
                .param("type", type)
                .param("now", now)
                .update(key, "id");
        long id = key.getKeyAs(Number.class).longValue();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", "evt_" + id);
        envelope.put("type", type);
        envelope.put("created", now.toInstant().toString());
        envelope.put("data", data);
        db.sql("UPDATE events SET payload = :payload WHERE id = :id").param("payload", toJson(envelope)).param("id", id).update();
        db.sql("""
                INSERT INTO deliveries (event_id, webhook_id, next_attempt_at)
                SELECT :event, id, :now FROM webhooks
                """)
                .param("event", id)
                .param("now", now)
                .update();
        return id;
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("event payload is not serialisable", e);
        }
    }
}
