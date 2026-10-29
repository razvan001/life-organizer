package com.lifeorganizer.persistence.mapper;

import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.FixedBlock;
import com.lifeorganizer.persistence.ConstraintEntity;
import com.lifeorganizer.persistence.FixedBlockEntity;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConstraintMapperTest {

    private static Constraint roundTrip(Constraint constraint) {
        return ConstraintMapper.toDomain(ConstraintMapper.toEntity(constraint, 0));
    }

    @Test
    void fixedBlockRoundTrips() {
        Constraint work = new FixedBlock("Work", LocalTime.of(8, 30), LocalTime.of(17, 0),
            EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));

        assertThat(roundTrip(work)).isEqualTo(work);
    }

    @Test
    void overnightFixedBlockRoundTrips() {
        Constraint sleep = new FixedBlock("Sleep", LocalTime.of(23, 30), LocalTime.of(7, 0),
            EnumSet.allOf(DayOfWeek.class));

        assertThat(roundTrip(sleep)).isEqualTo(sleep);
    }

    @Test
    void fixedBlockDaysAreStoredInWeekOrderNoMatterHowTheSetIsOrdered() {
        Set<DayOfWeek> days = Set.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
        FixedBlock block = new FixedBlock("Odd", LocalTime.of(9, 0), LocalTime.of(10, 0), days);

        FixedBlockEntity entity = (FixedBlockEntity) ConstraintMapper.toEntity(block, 0);

        assertThat(entity.getDays()).isEqualTo("MONDAY,WEDNESDAY,SUNDAY");
    }

    @Test
    void entityCarriesThePosition() {
        ConstraintEntity work = ConstraintMapper.toEntity(new FixedBlock("Work", LocalTime.of(8, 30), LocalTime.of(17, 0),
            EnumSet.of(DayOfWeek.MONDAY)), 3);

        assertThat(work).isInstanceOf(FixedBlockEntity.class);
        assertThat(work.getPosition()).isEqualTo(3);
        assertThat(work.getLabel()).isEqualTo("Work");
    }
}
