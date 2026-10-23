package io.github.tendo505.ledger.events;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook signatures: {@code Ledger-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}.
 * Signing the timestamp with the body lets receivers reject replays of old deliveries.
 */
public final class Signatures {
    private Signatures() {}

    public static String header(String secret, long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + hmac(secret, timestamp + "." + body);
    }

    /** What a receiver does: recompute and compare in constant time, and refuse stale timestamps. */
    public static boolean verify(String secret, String header, String body, long nowSeconds, long toleranceSeconds) {
        long t = -1;
        String v1 = null;
        for (String part : header.split(",")) {
            if (part.startsWith("t=")) t = Long.parseLong(part.substring(2));
            if (part.startsWith("v1=")) v1 = part.substring(3);
        }
        if (t < 0 || v1 == null || Math.abs(nowSeconds - t) > toleranceSeconds) return false;
        byte[] expected = hmac(secret, t + "." + body).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, v1.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
