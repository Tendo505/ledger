package io.github.tendo505.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Shared test plumbing: a clock tests can move, and a tiny JSON-over-HTTP client. */
public final class TestSupport {
    private TestSupport() {}

    /** A clock that stands still until a test moves it. */
    public static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-02T09:00:00Z"));

        public void advance(Duration d) {
            now.updateAndGet(t -> t.plus(d));
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        public MutableClock testClock() {
            return new MutableClock();
        }
    }

    public record Response(int status, JsonNode body, java.net.http.HttpHeaders headers) {
        public String text(String field) {
            return body.path(field).asText();
        }
    }

    /** JSON requests against the running test server. */
    public static final class Api {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final HttpClient http = HttpClient.newHttpClient();
        private final String base;

        public Api(int port) {
            this.base = "http://localhost:" + port + "/v1";
        }

        public Response get(String path) {
            return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
        }

        public Response post(String path, String json) {
            return post(path, json, null);
        }

        public Response post(String path, String json, String idempotencyKey) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json == null ? "" : json));
            if (idempotencyKey != null) b.header("Idempotency-Key", idempotencyKey);
            return send(b);
        }

        private Response send(HttpRequest.Builder b) {
            try {
                HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                JsonNode body = r.body().isEmpty() ? JSON.nullNode() : JSON.readTree(r.body());
                return new Response(r.statusCode(), body, r.headers());
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        // ---- shortcuts ----

        public String account(String name, String currency, boolean allowNegative) {
            Response r = post("/accounts", "{\"name\":\"" + name + "\",\"currency\":\"" + currency + "\",\"allowNegative\":" + allowNegative + "}");
            if (r.status() != 201) throw new IllegalStateException(r.body().toString());
            return r.text("id");
        }

        public Response transfer(String from, String to, long amount, String currency) {
            return post("/transfers", "{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}",
                    UUID.randomUUID().toString());
        }

        public long available(String account) {
            return get("/accounts/" + account).body().path("available").asLong();
        }

        public long balance(String account) {
            return get("/accounts/" + account).body().path("balance").asLong();
        }
    }
}
