package io.github.tendo505.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.tendo505.ledger.TestSupport.Api;
import io.github.tendo505.ledger.TestSupport.Response;
import io.github.tendo505.ledger.core.ApiException;
import io.github.tendo505.ledger.core.Audit;
import io.github.tendo505.ledger.core.Hold;
import io.github.tendo505.ledger.core.LedgerService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Many threads move money between a few accounts at once, in both
 * directions, with holds being placed, captured and voided in between. Row
 * locks must serialise them: nobody overdrawn, no deadlocks, no money
 * created or lost, and the audit clean. CI runs this against PostgreSQL too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSupport.Config.class)
class ConcurrencyTest {
    @Autowired
    LedgerService ledger;

    @Autowired
    Audit audit;

    @LocalServerPort
    int port;

    @Test
    void concurrentTransfersAndHoldsKeepTheBooksBalanced() throws Exception {
        String bank = ledger.createAccount("Bank", "USD", true).id();
        List<String> accounts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String id = ledger.createAccount("Customer " + i, "USD", false).id();
            ledger.transfer(bank, id, 5_000, "USD", "opening balance");
            accounts.add(id);
        }
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        int threads = 16, perThread = 150;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            futures.add(pool.submit(() -> {
                Random rnd = new Random(seed);
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    String from = accounts.get(rnd.nextInt(accounts.size()));
                    String to = accounts.get(rnd.nextInt(accounts.size()));
                    if (from.equals(to)) continue;
                    try {
                        if (rnd.nextInt(5) == 0) {
                            Hold h = ledger.createHold(from, to, 1 + rnd.nextInt(800), "USD", Duration.ofMinutes(5));
                            if (rnd.nextBoolean()) ledger.capture(h.id(), (long) 1 + rnd.nextInt((int) h.amount()));
                            else ledger.voidHold(h.id());
                        } else {
                            ledger.transfer(from, to, 1 + rnd.nextInt(1_500), "USD", null);
                        }
                        ok.incrementAndGet();
                    } catch (ApiException e) {
                        if (!e.code().equals("insufficient_funds")) unexpected.add(e);
                        refused.incrementAndGet();
                    } catch (Throwable e) {
                        unexpected.add(e);
                    }
                }
            }));
        }
        long t0 = System.nanoTime();
        start.countDown();
        for (Future<?> f : futures) f.get(2, TimeUnit.MINUTES);
        pool.shutdown();
        double seconds = (System.nanoTime() - t0) / 1e9;

        assertTrue(unexpected.isEmpty(), () -> "unexpected failures, e.g. " + unexpected.peek());
        long total = 0;
        for (String id : accounts) {
            long available = ledger.account(id).available();
            assertTrue(available >= 0, id + " overdrawn: " + available);
            total += ledger.account(id).balance();
        }
        assertEquals(6 * 5_000, total, "money moved between customers but none was created or destroyed");
        Audit.Report report = audit.run();
        assertTrue(report.ok(), () -> report.problems().toString());
        System.out.printf("%d operations from %d threads in %.1f s: %d succeeded, %d refused for insufficient funds; audit clean over %d entries%n",
                ok.get() + refused.get(), threads, seconds, ok.get(), refused.get(), report.entries());
        assertTrue(ok.get() > 100 && refused.get() > 0, "both outcomes happened");
    }

    @Test
    void twentySimultaneousRetriesOfOneRequestPayOnce() throws Exception {
        Api api = new Api(port);
        String bank = api.account("Bank", "USD", true);
        String shop = api.account("Shop", "USD", false);
        String body = "{\"from\":\"" + bank + "\",\"to\":\"" + shop + "\",\"amount\":4200,\"currency\":\"USD\"}";
        String key = "checkout-" + System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return api.post("/transfers", body, key);
            }));
        }
        start.countDown();
        Set<String> transactionIds = new HashSet<>();
        Map<Integer, Integer> statuses = new ConcurrentHashMap<>();
        int replays = 0;
        for (Future<Response> f : futures) {
            Response r = f.get(1, TimeUnit.MINUTES);
            statuses.merge(r.status(), 1, Integer::sum);
            if (r.status() == 201) transactionIds.add(r.text("id"));
            if ("true".equals(r.headers().firstValue("Idempotent-Replayed").orElse(""))) replays++;
        }
        pool.shutdown();
        assertEquals(Map.of(201, 20), statuses, "every caller got the success response");
        assertEquals(1, transactionIds.size(), "all twenty saw the same transaction");
        assertEquals(19, replays);
        assertEquals(4_200, api.balance(shop), "paid exactly once");
    }
}
