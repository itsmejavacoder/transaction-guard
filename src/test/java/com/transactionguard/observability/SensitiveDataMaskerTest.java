package com.transactionguard.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @Test
    void masksMiddleOfAccountId() {
        assertThat(masker.maskAccountId("ACC-201")).isEqualTo("ACC-***01");
    }

    @Test
    void neverReturnsTheFullIdentifier() {
        String masked = masker.maskAccountId("ACC-201");
        assertThat(masked).doesNotContain("201").isNotEqualTo("ACC-201");
    }

    @Test
    void handlesShortAndMissingValues() {
        assertThat(masker.maskAccountId("ABC")).isEqualTo("****");
        assertThat(masker.maskAccountId("ABCD")).isEqualTo("****");
        assertThat(masker.maskAccountId(null)).isEqualTo("****");
        assertThat(masker.maskAccountId("   ")).isEqualTo("****");
    }
}
