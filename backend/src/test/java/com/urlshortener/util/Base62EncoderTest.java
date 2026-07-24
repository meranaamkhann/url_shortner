package com.urlshortener.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Base62EncoderTest {

    @Test
    void encodeAndDecodeAreInverses() {
        long[] values = {0, 1, 61, 62, 123456789L, Long.MAX_VALUE / 2};
        for (long v : values) {
            String encoded = Base62Encoder.encode(v);
            assertThat(Base62Encoder.decode(encoded)).isEqualTo(v);
        }
    }

    @Test
    void encodedStringUsesOnlyUrlSafeCharacters() {
        String encoded = Base62Encoder.encode(987654321L);
        assertThat(encoded).matches("[0-9A-Za-z]+");
    }

    @Test
    void zeroEncodesToFirstAlphabetCharacter() {
        assertThat(Base62Encoder.encode(0)).isEqualTo("0");
    }
}
