package io.github.tendo505.ledger.events;

import io.github.tendo505.ledger.core.ApiException;
import io.github.tendo505.ledger.core.Ids;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookService {

    /** A subscriber. The secret is only ever returned once, when the webhook is created. */
    public record Webhook(String id, String url, String secret, Instant createdAt) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcClient db;
    private final Clock clock;

    public WebhookService(JdbcClient db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Transactional
    public Webhook create(String url) {
        URI uri = null;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            // reported below
        }
        if (uri == null || uri.getHost() == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_url", "webhook URLs must be absolute http(s) URLs");
        }
        byte[] secret = new byte[24];
        RANDOM.nextBytes(secret);
        Instant now = clock.instant();
        Webhook hook = new Webhook(Ids.next("wh"), url, "whsec_" + HexFormat.of().formatHex(secret), now);
        db.sql("INSERT INTO webhooks (id, url, secret, created_at) VALUES (:id, :url, :secret, :now)")
                .param("id", hook.id()).param("url", url).param("secret", hook.secret())
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
        return hook;
    }

    /** Listed without their secrets. */
    @Transactional(readOnly = true)
    public List<Webhook> list() {
        return db.sql("SELECT id, url, created_at FROM webhooks ORDER BY created_at")
                .query((rs, n) -> new Webhook(rs.getString(1), rs.getString(2), null, rs.getObject(3, OffsetDateTime.class).toInstant()))
                .list();
    }
}
