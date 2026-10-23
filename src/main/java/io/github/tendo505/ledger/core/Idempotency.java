package io.github.tendo505.ledger.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes a request safe to retry. A client that sends a transfer and never
 * hears back (timeout, dropped connection) can send it again with the same
 * {@code Idempotency-Key}: if the first attempt went through, it gets the
 * original response instead of moving the money twice.
 *
 * <p>The key is claimed in the same database transaction as the work it
 * protects, so key and effect commit together. Two concurrent requests with
 * the same key can't both run: the second one's insert waits on the first
 * one's row lock and then sees the stored response. Failed requests aren't
 * stored (their transaction rolls back), so they can simply be retried.
 */
@Service
public class Idempotency {

    /** What to send back, and whether it is a replay of an earlier response. */
    public record Response(int status, String body, boolean replayed) {}

    private record Stored(String hash, int status, String body) {}

    private final JdbcClient db;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public Idempotency(JdbcClient db, ObjectMapper json, Clock clock, TransactionTemplate transaction) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.transaction = transaction;
    }

    public Response run(String key, Object request, HttpStatus status, Supplier<Object> action) {
        if (key == null || key.isBlank() || key.length() > 200) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_idempotency_key", "Idempotency-Key must be 1 to 200 characters");
        }
        String hash = sha256(toJson(request));
        return transaction.execute(tx -> {
            int claimed = db.sql("""
                    INSERT INTO idempotency_keys (idem_key, request_hash, response_status, response_body, created_at)
                    VALUES (:key, :hash, 0, '', :now) ON CONFLICT DO NOTHING
                    """)
                    .param("key", key).param("hash", hash).param("now", OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
                    .update();
            if (claimed == 1) {
                String body = toJson(action.get()); // runs inside this same transaction
                db.sql("UPDATE idempotency_keys SET response_status = :status, response_body = :body WHERE idem_key = :key")
                        .param("status", status.value()).param("body", body).param("key", key)
                        .update();
                return new Response(status.value(), body, false);
            }
            Stored stored = db.sql("SELECT request_hash, response_status, response_body FROM idempotency_keys WHERE idem_key = :key")
                    .param("key", key)
                    .query((rs, n) -> new Stored(rs.getString(1), rs.getInt(2), rs.getString(3)))
                    .single();
            if (!stored.hash().equals(hash)) {
                throw ApiException.unprocessable("idempotency_key_reused",
                        "this Idempotency-Key was already used for a different request; use a new key for a new request");
            }
            return new Response(stored.status(), stored.body(), true);
        });
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
