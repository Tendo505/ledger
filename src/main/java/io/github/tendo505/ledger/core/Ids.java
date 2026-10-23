package io.github.tendo505.ledger.core;

import java.security.SecureRandom;

/** Stripe-style identifiers: a type prefix and 16 random base-62 characters, e.g. {@code acct_3fJ9aK2mQx7LpZ1w}. */
public final class Ids {
    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {}

    public static String next(String prefix) {
        char[] out = new char[16];
        for (int i = 0; i < out.length; i++) out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        return prefix + "_" + new String(out);
    }
}
