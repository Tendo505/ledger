package io.github.tendo505.ledger.core;

import io.github.tendo505.ledger.events.Outbox;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accounts, transfers and holds.
 *
 * <p>Every change that moves money runs in one database transaction that
 * first locks the accounts involved ({@code SELECT ... FOR UPDATE}), always in
 * the same order (by id), so two transfers between the same accounts in
 * opposite directions wait for each other instead of deadlocking. Checks
 * happen after locking, so no two transfers can both spend the same balance.
 * The database's {@code no_overdraft} constraint backs this up.
 */
@Service
public class LedgerService {
    private final JdbcClient db;
    private final Clock clock;
    private final Outbox outbox;

    public LedgerService(JdbcClient db, Clock clock, Outbox outbox) {
        this.db = db;
        this.clock = clock;
        this.outbox = outbox;
    }

    // ---------------- accounts ----------------

    @Transactional
    public Account createAccount(String name, String currency, boolean allowNegative) {
        requireCurrency(currency);
        Account account = new Account(Ids.next("acct"), name, currency, allowNegative, 0, 0, now());
        db.sql("""
                INSERT INTO accounts (id, name, currency, allow_negative, balance, pending, created_at)
                VALUES (:id, :name, :currency, :allowNegative, 0, 0, :createdAt)
                """)
                .param("id", account.id())
                .param("name", name)
                .param("currency", currency)
                .param("allowNegative", allowNegative)
                .param("createdAt", odt(account.createdAt()))
                .update();
        outbox.publish("account.created", account);
        return account;
    }

    @Transactional(readOnly = true)
    public Account account(String id) {
        return db.sql("SELECT * FROM accounts WHERE id = :id").param("id", id).query(LedgerService::account).optional()
                .orElseThrow(() -> ApiException.notFound("account", id));
    }

    /** Newest first, {@code limit} at a time; pass the last entry's id as {@code before} for the next page (keyset pagination). */
    @Transactional(readOnly = true)
    public List<Transaction.Entry> entries(String accountId, Long before, int limit) {
        account(accountId);
        return db.sql("""
                SELECT * FROM entries WHERE account_id = :account AND id < :before
                ORDER BY id DESC LIMIT :limit
                """)
                .param("account", accountId)
                .param("before", before == null ? Long.MAX_VALUE : before)
                .param("limit", limit)
                .query(LedgerService::entry)
                .list();
    }

    // ---------------- transfers ----------------

    @Transactional
    public Transaction transfer(String fromId, String toId, long amount, String currency, String description) {
        requirePositive(amount);
        if (fromId.equals(toId)) throw ApiException.unprocessable("same_account", "can't transfer from an account to itself");
        Map<String, Account> locked = lock(fromId, toId);
        Account from = locked.get(fromId);
        Account to = locked.get(toId);
        requireSameCurrency(currency, from, to);
        requireFunds(from, amount);
        return post("transfer", description, List.of(new Leg(from, -amount), new Leg(to, amount)));
    }

    @Transactional(readOnly = true)
    public Transaction transaction(String id) {
        return db.sql("SELECT * FROM transactions WHERE id = :id").param("id", id)
                .query((rs, n) -> new Transaction(rs.getString("id"), rs.getString("kind"), rs.getString("description"),
                        instant(rs, "created_at"), entriesOf(id)))
                .optional()
                .orElseThrow(() -> ApiException.notFound("transaction", id));
    }

    private record Leg(Account account, long amount) {}

    /** Called with the accounts locked: writes the transaction, its entries and the new balances. */
    private Transaction post(String kind, String description, List<Leg> legs) {
        long sum = legs.stream().mapToLong(Leg::amount).sum();
        if (sum != 0) throw new IllegalStateException("unbalanced transaction: legs sum to " + sum);
        String id = Ids.next("txn");
        Instant now = now();
        db.sql("INSERT INTO transactions (id, kind, description, created_at) VALUES (:id, :kind, :description, :now)")
                .param("id", id).param("kind", kind).param("description", description).param("now", odt(now))
                .update();
        Map<String, Long> balances = new HashMap<>();
        for (Leg leg : legs) {
            long after = balances.getOrDefault(leg.account().id(), leg.account().balance()) + leg.amount();
            balances.put(leg.account().id(), after);
            db.sql("""
                    INSERT INTO entries (transaction_id, account_id, amount, balance_after, created_at)
                    VALUES (:txn, :account, :amount, :after, :now)
                    """)
                    .param("txn", id).param("account", leg.account().id()).param("amount", leg.amount())
                    .param("after", after).param("now", odt(now))
                    .update();
            db.sql("UPDATE accounts SET balance = balance + :amount WHERE id = :id")
                    .param("amount", leg.amount()).param("id", leg.account().id())
                    .update();
        }
        Transaction txn = new Transaction(id, kind, description, now, entriesOf(id));
        outbox.publish("transaction.posted", txn);
        return txn;
    }

    // ---------------- holds ----------------

    @Transactional
    public Hold createHold(String accountId, String destinationId, long amount, String currency, Duration ttl) {
        requirePositive(amount);
        if (ttl.isNegative() || ttl.isZero()) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_ttl", "a hold must last a positive time");
        if (accountId.equals(destinationId)) throw ApiException.unprocessable("same_account", "a hold can't pay its own account");
        Map<String, Account> locked = lock(accountId, destinationId);
        Account account = locked.get(accountId);
        requireSameCurrency(currency, account, locked.get(destinationId));
        requireFunds(account, amount);
        db.sql("UPDATE accounts SET pending = pending + :amount WHERE id = :id").param("amount", amount).param("id", accountId).update();
        Instant now = now();
        Hold hold = new Hold(Ids.next("hold"), accountId, destinationId, amount, Hold.Status.PENDING, null, null, now.plus(ttl), now);
        db.sql("""
                INSERT INTO holds (id, account_id, destination_id, amount, status, expires_at, created_at)
                VALUES (:id, :account, :destination, :amount, 'PENDING', :expires, :now)
                """)
                .param("id", hold.id()).param("account", accountId).param("destination", destinationId)
                .param("amount", amount).param("expires", odt(hold.expiresAt())).param("now", odt(now))
                .update();
        outbox.publish("hold.created", hold);
        return hold;
    }

    @Transactional(readOnly = true)
    public Hold hold(String id) {
        return db.sql("SELECT * FROM holds WHERE id = :id").param("id", id).query(LedgerService::hold).optional()
                .orElseThrow(() -> ApiException.notFound("hold", id));
    }

    /** Pays {@code amount} (or the whole hold, if null) to the destination and releases the rest. */
    @Transactional
    public Hold capture(String holdId, Long amount) {
        Hold hold = lockPendingHold(holdId);
        if (!hold.expiresAt().isAfter(now())) throw ApiException.conflict("hold_expired", "hold " + holdId + " expired at " + hold.expiresAt());
        long capture = amount == null ? hold.amount() : amount;
        if (capture <= 0 || capture > hold.amount()) {
            throw ApiException.unprocessable("invalid_capture_amount", "capture between 1 and " + hold.amount() + ", not " + capture);
        }
        Map<String, Account> locked = lock(hold.accountId(), hold.destinationId());
        release(hold);
        // Reload: releasing the hold changed the account's pending amount.
        Account from = withPending(locked.get(hold.accountId()), -hold.amount());
        Transaction txn = post("capture", "capture of " + holdId, List.of(new Leg(from, -capture), new Leg(locked.get(hold.destinationId()), capture)));
        db.sql("UPDATE holds SET status = 'CAPTURED', captured_amount = :amount, transaction_id = :txn WHERE id = :id")
                .param("amount", capture).param("txn", txn.id()).param("id", holdId).update();
        Hold captured = hold(holdId);
        outbox.publish("hold.captured", captured);
        return captured;
    }

    @Transactional
    public Hold voidHold(String holdId) {
        Hold hold = lockPendingHold(holdId);
        lock(hold.accountId(), hold.destinationId());
        release(hold);
        db.sql("UPDATE holds SET status = 'VOIDED' WHERE id = :id").param("id", holdId).update();
        Hold voided = hold(holdId);
        outbox.publish("hold.voided", voided);
        return voided;
    }

    /** Releases holds whose time is up. Returns how many expired. Called every second by {@link HoldSweeper}. */
    @Transactional
    public int expireDue() {
        List<String> due = db.sql("SELECT id FROM holds WHERE status = 'PENDING' AND expires_at <= :now ORDER BY expires_at LIMIT 100")
                .param("now", odt(now()))
                .query(String.class)
                .list();
        int expired = 0;
        for (String id : due) {
            Optional<Hold> hold = db.sql("SELECT * FROM holds WHERE id = :id AND status = 'PENDING' FOR UPDATE").param("id", id)
                    .query(LedgerService::hold).optional();
            if (hold.isEmpty()) continue; // captured or voided meanwhile
            lock(hold.get().accountId(), hold.get().destinationId());
            release(hold.get());
            db.sql("UPDATE holds SET status = 'EXPIRED' WHERE id = :id").param("id", id).update();
            outbox.publish("hold.expired", hold(id));
            expired++;
        }
        return expired;
    }

    private Hold lockPendingHold(String id) {
        Hold hold = db.sql("SELECT * FROM holds WHERE id = :id FOR UPDATE").param("id", id).query(LedgerService::hold).optional()
                .orElseThrow(() -> ApiException.notFound("hold", id));
        if (hold.status() != Hold.Status.PENDING) {
            throw ApiException.conflict("hold_not_pending", "hold " + id + " is already " + hold.status().name().toLowerCase());
        }
        return hold;
    }

    private void release(Hold hold) {
        db.sql("UPDATE accounts SET pending = pending - :amount WHERE id = :id").param("amount", hold.amount()).param("id", hold.accountId()).update();
    }

    // ---------------- locking and checks ----------------

    /** Locks both accounts, lower id first, so concurrent transactions over the same pair can't deadlock. */
    private Map<String, Account> lock(String a, String b) {
        Map<String, Account> out = new HashMap<>();
        List<String> ids = new ArrayList<>(List.of(a, b));
        ids.sort(null);
        for (String id : ids) {
            Account account = db.sql("SELECT * FROM accounts WHERE id = :id FOR UPDATE").param("id", id).query(LedgerService::account).optional()
                    .orElseThrow(() -> ApiException.notFound("account", id));
            out.put(id, account);
        }
        return out;
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_amount", "amounts are positive integers in minor units (cents)");
    }

    private static void requireFunds(Account account, long amount) {
        if (!account.allowNegative() && account.available() < amount) {
            throw ApiException.unprocessable("insufficient_funds",
                    "account " + account.id() + " has " + account.available() + " available, needs " + amount);
        }
    }

    private static void requireSameCurrency(String currency, Account from, Account to) {
        if (!from.currency().equals(currency) || !to.currency().equals(currency)) {
            throw ApiException.unprocessable("currency_mismatch",
                    "the request is in " + currency + " but the accounts are in " + from.currency() + " and " + to.currency());
        }
    }

    private static void requireCurrency(String code) {
        try {
            Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_currency", code + " is not an ISO 4217 currency code");
        }
    }

    private static Account withPending(Account a, long delta) {
        return new Account(a.id(), a.name(), a.currency(), a.allowNegative(), a.balance(), a.pending() + delta, a.createdAt());
    }

    // ---------------- mapping ----------------

    private List<Transaction.Entry> entriesOf(String transactionId) {
        return db.sql("SELECT * FROM entries WHERE transaction_id = :id ORDER BY id").param("id", transactionId).query(LedgerService::entry).list();
    }

    private Instant now() {
        return clock.instant();
    }

    static OffsetDateTime odt(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    static Account account(ResultSet rs, int row) throws SQLException {
        return new Account(rs.getString("id"), rs.getString("name"), rs.getString("currency"), rs.getBoolean("allow_negative"),
                rs.getLong("balance"), rs.getLong("pending"), instant(rs, "created_at"));
    }

    static Transaction.Entry entry(ResultSet rs, int row) throws SQLException {
        return new Transaction.Entry(rs.getLong("id"), rs.getString("account_id"), rs.getLong("amount"), rs.getLong("balance_after"),
                instant(rs, "created_at"), rs.getString("transaction_id"));
    }

    static Hold hold(ResultSet rs, int row) throws SQLException {
        long captured = rs.getLong("captured_amount");
        Long capturedAmount = rs.wasNull() ? null : captured; // must be checked right after reading that column
        return new Hold(rs.getString("id"), rs.getString("account_id"), rs.getString("destination_id"), rs.getLong("amount"),
                Hold.Status.valueOf(rs.getString("status")), capturedAmount, rs.getString("transaction_id"),
                instant(rs, "expires_at"), instant(rs, "created_at"));
    }
}
