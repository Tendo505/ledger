package io.github.tendo505.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.tendo505.ledger.TestSupport.Api;
import io.github.tendo505.ledger.TestSupport.MutableClock;
import io.github.tendo505.ledger.TestSupport.Response;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSupport.Config.class)
class ApiTest {
    @LocalServerPort
    int port;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient db;

    Api api;
    String bank;
    String alice;
    String bob;

    @BeforeEach
    void setUp() {
        api = new Api(port);
        bank = api.account("Bank", "USD", true);
        alice = api.account("Alice", "USD", false);
        bob = api.account("Bob", "USD", false);
        assertEquals(201, api.transfer(bank, alice, 10_000, "USD").status());
    }

    @Test
    void transfersPostBalancedEntries() {
        Response r = api.transfer(alice, bob, 2_500, "USD");
        assertEquals(201, r.status());
        JsonNode entries = r.body().path("entries");
        assertEquals(2, entries.size());
        assertEquals(-2_500, entries.get(0).path("amount").asLong());
        assertEquals(2_500, entries.get(1).path("amount").asLong());
        assertEquals(7_500, entries.get(0).path("balanceAfter").asLong());
        assertEquals(7_500, api.balance(alice));
        assertEquals(2_500, api.balance(bob));
        assertEquals(r.text("id"), api.get("/transactions/" + r.text("id")).text("id"));
    }

    @Test
    void refusalsCarryAStableCode() {
        assertEquals("insufficient_funds", api.transfer(alice, bob, 10_001, "USD").text("code"));
        String euro = api.account("Euro", "EUR", false);
        assertEquals("currency_mismatch", api.transfer(alice, euro, 1, "USD").text("code"));
        assertEquals("same_account", api.transfer(alice, alice, 1, "USD").text("code"));
        Response missing = api.transfer(alice, "acct_nope", 1, "USD");
        assertEquals(404, missing.status());
        assertEquals("not_found", missing.text("code"));
        assertEquals("invalid_currency", api.post("/accounts", "{\"name\":\"X\",\"currency\":\"ZZZ\"}").text("code"));
        // Nothing moved.
        assertEquals(10_000, api.balance(alice));
    }

    @Test
    void validationErrorsNameEachField() {
        Response r = api.post("/transfers", "{\"from\":\"\",\"to\":\"x\",\"amount\":-5,\"currency\":\"usd\"}", "k-" + System.nanoTime());
        assertEquals(400, r.status());
        assertEquals("invalid_request", r.text("code"));
        Set<String> fields = new HashSet<>();
        r.body().path("errors").forEach(e -> fields.add(e.path("field").asText()));
        assertEquals(Set.of("from", "amount", "currency"), fields);
        Response noKey = api.post("/transfers", "{}", null);
        assertEquals(400, noKey.status());
        assertTrue(noKey.text("detail").contains("Idempotency-Key"));
    }

    @Test
    void aRetryWithTheSameKeyReplaysInsteadOfPayingTwice() {
        String body = "{\"from\":\"" + alice + "\",\"to\":\"" + bob + "\",\"amount\":1000,\"currency\":\"USD\"}";
        Response first = api.post("/transfers", body, "order-123");
        Response retry = api.post("/transfers", body, "order-123");
        assertEquals(201, retry.status());
        assertEquals("false", first.headers().firstValue("Idempotent-Replayed").orElseThrow());
        assertEquals("true", retry.headers().firstValue("Idempotent-Replayed").orElseThrow());
        assertEquals(first.body(), retry.body(), "the very same response, byte for byte");
        assertEquals(1_000, api.balance(bob), "paid once");

        Response different = api.post("/transfers", body.replace("1000", "999"), "order-123");
        assertEquals(422, different.status());
        assertEquals("idempotency_key_reused", different.text("code"));

        // A refused request isn't remembered: after topping up, the same key can succeed.
        String big = "{\"from\":\"" + alice + "\",\"to\":\"" + bob + "\",\"amount\":50000,\"currency\":\"USD\"}";
        assertEquals(422, api.post("/transfers", big, "big-1").status());
        api.transfer(bank, alice, 50_000, "USD");
        assertEquals(201, api.post("/transfers", big, "big-1").status());
    }

    @Test
    void statementsPageNewestFirst() {
        for (int i = 1; i <= 7; i++) api.transfer(alice, bob, i, "USD");
        Response page1 = api.get("/accounts/" + bob + "/entries?limit=3");
        assertEquals(3, page1.body().path("data").size());
        assertEquals(7, page1.body().path("data").get(0).path("amount").asLong());
        long cursor = page1.body().path("nextBefore").asLong();
        Response page2 = api.get("/accounts/" + bob + "/entries?limit=3&before=" + cursor);
        assertEquals(4, page2.body().path("data").get(0).path("amount").asLong());
        Response page3 = api.get("/accounts/" + bob + "/entries?limit=3&before=" + page2.body().path("nextBefore").asLong());
        assertEquals(1, page3.body().path("data").size());
        assertTrue(page3.body().path("nextBefore").isMissingNode() || page3.body().path("nextBefore").isNull());
        assertEquals(28, api.balance(bob));
    }

    @Test
    void holdsReserveThenCaptureOrVoidOrExpire() {
        Response hold = api.post("/holds", "{\"account\":\"" + alice + "\",\"destination\":\"" + bob + "\",\"amount\":3000,\"currency\":\"USD\",\"ttlSeconds\":60}", "h-" + System.nanoTime());
        assertEquals(201, hold.status());
        assertEquals(10_000, api.balance(alice), "a hold doesn't move money...");
        assertEquals(7_000, api.available(alice), "...it reserves it");
        assertEquals("insufficient_funds", api.transfer(alice, bob, 8_000, "USD").text("code"), "reserved money can't be spent twice");

        Response captured = api.post("/holds/" + hold.text("id") + "/capture", "{\"amount\":1200}");
        assertEquals("CAPTURED", captured.text("status"));
        assertEquals(1_200, captured.body().path("capturedAmount").asLong());
        assertEquals(8_800, api.available(alice), "the uncaptured 1,800 is released");
        assertEquals(1_200, api.balance(bob));
        assertEquals("hold_not_pending", api.post("/holds/" + hold.text("id") + "/capture", null).text("code"));

        String h2 = api.post("/holds", "{\"account\":\"" + alice + "\",\"destination\":\"" + bob + "\",\"amount\":500,\"currency\":\"USD\"}", "h-" + System.nanoTime()).text("id");
        assertEquals("VOIDED", api.post("/holds/" + h2 + "/void", null).text("status"));
        assertEquals(8_800, api.available(alice));

        String h3 = api.post("/holds", "{\"account\":\"" + alice + "\",\"destination\":\"" + bob + "\",\"amount\":700,\"currency\":\"USD\",\"ttlSeconds\":30}", "h-" + System.nanoTime()).text("id");
        assertEquals(8_100, api.available(alice));
        clock.advance(Duration.ofSeconds(31));
        assertEquals("hold_expired", api.post("/holds/" + h3 + "/capture", null).text("code"), "too late to capture");
        await(() -> "EXPIRED".equals(api.get("/holds/" + h3).text("status")));
        assertEquals(8_800, api.available(alice), "the sweeper released it");
        assertTrue(api.get("/audit").body().path("ok").asBoolean());
    }

    @Test
    void theAuditNoticesTampering() {
        assertTrue(api.get("/audit").body().path("ok").asBoolean());
        db.sql("UPDATE accounts SET balance = balance + 1 WHERE id = :id").param("id", alice).update();
        try {
            JsonNode report = api.get("/audit").body();
            assertFalse(report.path("ok").asBoolean());
            String problems = report.path("problems").toString();
            assertTrue(problems.contains(alice + " says balance 10001 but its entries sum to 10000"), problems);
            assertTrue(problems.contains("USD balances net to 1"), problems);
        } finally {
            db.sql("UPDATE accounts SET balance = balance - 1 WHERE id = :id").param("id", alice).update();
        }
        assertTrue(api.get("/audit").body().path("ok").asBoolean());
    }

    static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting");
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    void accountsShowWhatIsAvailable() {
        JsonNode a = api.get("/accounts/" + alice).body();
        assertEquals(10_000, a.path("balance").asLong());
        assertEquals(0, a.path("pending").asLong());
        assertEquals(10_000, a.path("available").asLong());
    }
}
