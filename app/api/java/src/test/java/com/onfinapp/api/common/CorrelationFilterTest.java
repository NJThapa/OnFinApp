package com.onfinapp.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class CorrelationFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {"7f1c2a9e-6f2b-4c1d-9f0a-1234567890ab", "checkout_flow.42", "A"})
    void plainTokensAreAccepted(String value) {
        assertThat(CorrelationFilter.accept(value)).isEqualTo(value);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
        "",
        "has space",
        "line\nbreak",
        "{\"json\":true}",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", // 65 characters
    })
    void anythingElseIsReplaced(String value) {
        assertThat(CorrelationFilter.accept(value)).isNull();
    }
}
