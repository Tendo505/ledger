package io.github.tendo505.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.tendo505.ledger.TestSupport.Api;
import io.github.tendo505.ledger.TestSupport.MutableClock;
import io.github.tendo505.ledger.events.Signatures;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** A real HTTP receiver: events arrive signed, and a failed delivery is retried after the backoff. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSupport.Config.class)
class WebhookTest {
    @LocalServerPort
    int port;

    @Autowired
    MutableClock clock;

    record Received(String signature, String body, String type) {}

    @Test
    void eventsArriveSignedAndFailuresAreRetried() throws Exception {
        List<Received> received = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        receiver.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // Fail the very first delivery, to force a retry.
            int status = calls.getAndIncrement() == 0 ? 500 : 200;
            if (status == 200) {
                received.add(new Received(exchange.getRequestHeaders().getFirst("Ledger-Signature"), body,
                        exchange.getRequestHeaders().getFirst("Ledger-Event-Type")));
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        receiver.start();
        try {
            Api api = new Api(port);
            var hook = api.post("/webhooks", "{\"url\":\"http://localhost:" + receiver.getAddress().getPort() + "/hook\"}");
            assertEquals(201, hook.status());
            String secret = hook.text("secret");
            assertTrue(secret.startsWith("whsec_"));
            assertFalse(api.get("/webhooks").body().toString().contains(secret), "secrets are shown only once");

            String bank = api.account("Bank", "USD", true);
            String alice = api.account("Alice", "USD", false);
            String txn = api.transfer(bank, alice, 1234, "USD").text("id");

            // Three events: account.created (Bank), account.created (Alice), transaction.posted.
            // The first delivery got a 500; the other two arrive; the first is retried once its backoff has passed.
            ApiTest.await(() -> calls.get() >= 3 && received.size() >= 2);
            assertEquals(2, received.size(), "the failed delivery waits for its backoff");
            clock.advance(Duration.ofSeconds(5));
            ApiTest.await(() -> received.size() >= 3);
            assertEquals(List.of("account.created", "account.created", "transaction.posted"),
                    received.stream().map(Received::type).sorted().toList());
            assertTrue(received.stream().anyMatch(r -> r.body().contains(txn)));

            ObjectMapper json = new ObjectMapper();
            for (Received r : received) {
                assertTrue(Signatures.verify(secret, r.signature(), r.body(), clock.instant().getEpochSecond(), 300),
                        "signature verifies: " + r.signature());
                assertFalse(Signatures.verify("whsec_wrong", r.signature(), r.body(), clock.instant().getEpochSecond(), 300));
                assertFalse(Signatures.verify(secret, r.signature(), r.body() + " ", clock.instant().getEpochSecond(), 300), "tampered body");
            }
            JsonNode posted = json.readTree(received.stream().filter(r -> r.body().contains(txn)).findFirst().orElseThrow().body());
            assertEquals("transaction.posted", posted.path("type").asText());
            assertEquals(1234, posted.path("data").path("entries").get(1).path("amount").asLong());
            assertTrue(posted.path("id").asText().startsWith("evt_"));
            // An old signature is rejected later, so a captured delivery can't be replayed.
            Received any = received.getFirst();
            assertFalse(Signatures.verify(secret, any.signature(), any.body(), clock.instant().getEpochSecond() + 3600, 300));
        } finally {
            receiver.stop(0);
        }
    }
}
