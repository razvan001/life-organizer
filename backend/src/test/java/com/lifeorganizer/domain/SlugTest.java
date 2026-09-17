package com.lifeorganizer.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SlugTest {

    @Test
    void lowercasesAndDashesSpaces() {
        assertThat(Slug.of("Claude certification")).isEqualTo("claude-certification");
    }

    @Test
    void collapsesNonAlphanumericRuns() {
        assertThat(Slug.of("Padel / football")).isEqualTo("padel-football");
    }

    @Test
    void trimsLeadingAndTrailingDashes() {
        assertThat(Slug.of("  RAG showcase!  ")).isEqualTo("rag-showcase");
    }

    @Test
    void fallsBackToItemWhenNothingAlphanumeric() {
        assertThat(Slug.of("///")).isEqualTo("item");
    }
}
