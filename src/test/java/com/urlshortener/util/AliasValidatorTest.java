package com.urlshortener.util;

import com.urlshortener.exception.InvalidUrlException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AliasValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"my-link", "my_link_2024", "abc", "A1B2C3D4E5F6G7H8I9J0"})
    void acceptsValidAliases(String alias) {
        assertThatCode(() -> AliasValidator.validate(alias)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "has space", "has/slash", "has?query", "x".repeat(33)})
    void rejectsInvalidFormatAliases(String alias) {
        assertThatThrownBy(() -> AliasValidator.validate(alias)).isInstanceOf(InvalidUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "admin", "ADMIN", "login", "health", "swagger-ui"})
    void rejectsReservedWords(String alias) {
        assertThatThrownBy(() -> AliasValidator.validate(alias)).isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void rejectsNullAlias() {
        assertThatThrownBy(() -> AliasValidator.validate(null)).isInstanceOf(InvalidUrlException.class);
    }
}
