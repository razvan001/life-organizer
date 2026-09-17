package com.lifeorganizer.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CategoryTest {

    @Test
    void ofDerivesSlugId() {
        Category c = Category.of("Career");
        assertThat(c.id()).isEqualTo("career");
        assertThat(c.name()).isEqualTo("Career");
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> new Category("x", " "))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
