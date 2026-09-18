package com.lifeorganizer.domain;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConflictValidationTest {

    @Test
    void nullTypeThrowsNullPointerException() {
        assertThatThrownBy(() -> new Conflict(null, "message", List.of()))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("type");
    }

    @Test
    void nullMessageThrowsNullPointerException() {
        assertThatThrownBy(() -> new Conflict(ConflictType.OVERLAP, null, List.of()))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("message");
    }

    @Test
    void nullRelatedBlockIdsThrowsNullPointerException() {
        assertThatThrownBy(() -> new Conflict(ConflictType.OVERLAP, "message", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("relatedBlockIds");
    }

    @Test
    void defensiveCopyOfRelatedBlockIds() {
        ArrayList<String> mutableBlockIds = new ArrayList<>();
        String blockId1 = "block-1";
        mutableBlockIds.add(blockId1);

        Conflict conflict = new Conflict(ConflictType.OVERLAP, "overlap message", mutableBlockIds);
        mutableBlockIds.add("block-2");

        assertThat(conflict.relatedBlockIds()).containsExactly(blockId1);
    }
}
