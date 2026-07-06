package com.urlshortener.util;

/**
 * Base62 encoding ([0-9a-zA-Z]) — the standard alphabet used by Bitly/TinyURL-style
 * shorteners because every character is URL-safe with no escaping needed, unlike
 * Base64 ('+', '/', '=').
 *
 * 62^7 ≈ 3.5 trillion combinations — comfortably enough for a "billions of URLs" system
 * with 7-character codes, which is why app.short-code.length defaults to 7.
 */
public final class Base62Encoder {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int BASE = ALPHABET.length;

    private Base62Encoder() {
    }

    public static String encode(long value) {
        if (value == 0) return String.valueOf(ALPHABET[0]);
        StringBuilder sb = new StringBuilder();
        long v = value;
        while (v > 0) {
            sb.append(ALPHABET[(int) (v % BASE)]);
            v /= BASE;
        }
        return sb.reverse().toString();
    }

    public static long decode(String code) {
        long result = 0;
        for (char c : code.toCharArray()) {
            result = result * BASE + indexOf(c);
        }
        return result;
    }

    private static int indexOf(char c) {
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == c) return i;
        }
        throw new IllegalArgumentException("Invalid Base62 character: " + c);
    }
}
