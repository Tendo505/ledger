package io.github.tendo505.ledger.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-derives the books from the raw entries and checks they agree with the
 * cached balances. In a correct ledger every check passes; the tests run it
 * after hammering the API concurrently, and tamper with the database to
 * prove it notices.
 */
@Service
public class Audit {

    public record Report(boolean ok, long accounts, long transactions, long entries, Map<String, Long> netByCurrency, List<String> problems) {}

    private final JdbcClient db;

    public Audit(JdbcClient db) {
        this.db = db;
    }

    @Transactional(readOnly = true)
    public Report run() {
        List<String> problems = new ArrayList<>();

        // Double entry: each transaction's entries sum to zero.
        db.sql("SELECT transaction_id, SUM(amount) FROM entries GROUP BY transaction_id HAVING SUM(amount) <> 0")
                .query((rs, n) -> "transaction " + rs.getString(1) + " is unbalanced by " + rs.getLong(2))
                .list().forEach(problems::add);

        // Cached balances match the sum of their entries.
        db.sql("""
                SELECT a.id, a.balance, COALESCE(SUM(e.amount), 0) FROM accounts a LEFT JOIN entries e ON e.account_id = a.id
                GROUP BY a.id, a.balance HAVING a.balance <> COALESCE(SUM(e.amount), 0)
                """)
                .query((rs, n) -> "account " + rs.getString(1) + " says balance " + rs.getLong(2) + " but its entries sum to " + rs.getLong(3))
                .list().forEach(problems::add);

        // Each entry's balance_after is the running total of the account's entries up to it.
        db.sql("""
                SELECT id, account_id, balance_after, running FROM (
                    SELECT id, account_id, balance_after, SUM(amount) OVER (PARTITION BY account_id ORDER BY id) AS running FROM entries
                ) t WHERE balance_after <> running
                """)
                .query((rs, n) -> "entry " + rs.getLong(1) + " of " + rs.getString(2) + " says balance " + rs.getLong(3) + ", running total is " + rs.getLong(4))
                .list().forEach(problems::add);

        // Reserved amounts match the open holds.
        db.sql("""
                SELECT a.id, a.pending, COALESCE(SUM(h.amount), 0) FROM accounts a
                LEFT JOIN holds h ON h.account_id = a.id AND h.status = 'PENDING'
                GROUP BY a.id, a.pending HAVING a.pending <> COALESCE(SUM(h.amount), 0)
                """)
                .query((rs, n) -> "account " + rs.getString(1) + " reserves " + rs.getLong(2) + " but its open holds total " + rs.getLong(3))
                .list().forEach(problems::add);

        // Nobody is overdrawn.
        db.sql("SELECT id, balance - pending FROM accounts WHERE NOT allow_negative AND balance - pending < 0")
                .query((rs, n) -> "account " + rs.getString(1) + " is overdrawn: " + rs.getLong(2))
                .list().forEach(problems::add);

        // Money is only moved, never created: per currency, all balances together net to zero.
        Map<String, Long> net = new LinkedHashMap<>();
        db.sql("SELECT currency, SUM(balance) FROM accounts GROUP BY currency ORDER BY currency")
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2)))
                .list().forEach(e -> net.put(e.getKey(), e.getValue()));
        net.forEach((currency, sum) -> {
            if (sum != 0) problems.add(currency + " balances net to " + sum + " instead of zero");
        });

        long accounts = db.sql("SELECT COUNT(*) FROM accounts").query(Long.class).single();
        long transactions = db.sql("SELECT COUNT(*) FROM transactions").query(Long.class).single();
        long entries = db.sql("SELECT COUNT(*) FROM entries").query(Long.class).single();
        return new Report(problems.isEmpty(), accounts, transactions, entries, net, problems);
    }
}
