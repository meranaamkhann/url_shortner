package com.urlshortener.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashUtilTest {

    @Test
    void sameInputProducesSameHash() {
        String h1 = HashUtil.sha256Hex("https://example.com");
        String h2 = HashUtil.sha256Hex("https://example.com");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void differentInputProducesDifferentHash() {
        assertThat(HashUtil.sha256Hex("https://example.com/a"))
                .isNotEqualTo(HashUtil.sha256Hex("https://example.com/b"));
    }

    @Test
    void producesA64CharacterHexString() {
        String hash = HashUtil.sha256Hex("192.168.1.1");
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }
}
