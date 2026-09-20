package com.lifeorganizer.persistence.mapper;

import com.lifeorganizer.domain.Constraint;
import com.lifeorganizer.domain.DurationQuota;
import com.lifeorganizer.domain.EveningQuota;
import com.lifeorganizer.domain.FixedBlock;
import com.lifeorganizer.domain.FrequencyQuota;
import com.lifeorganizer.persistence.ConstraintEntity;
import com.lifeorganizer.persistence.DurationQuotaEntity;
import com.lifeorganizer.persistence.EveningQuotaEntity;
import com.lifeorganizer.persistence.FixedBlockEntity;
import com.lifeorganizer.persistence.FrequencyQuotaEntity;
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
    void frequencyQuotaRoundTrips() {
        Constraint gym = new FrequencyQuota("Gym", 4, 60, "health-gym");

        assertThat(roundTrip(gym)).isEqualTo(gym);
    }

    @Test
    void durationQuotaRoundTrips() {
        Constraint learning = new DurationQuota("Learning", 300, "career");

        assertThat(roundTrip(learning)).isEqualTo(learning);
    }

    @Test
    void eveningQuotaRoundTrips() {
        Constraint social = new EveningQuota("Social", 2, "social");

        assertThat(roundTrip(social)).isEqualTo(social);
    }

    @Test
    void entityCarriesTheTypeSpecificClassAndThePosition() {
        ConstraintEntity gym = ConstraintMapper.toEntity(new FrequencyQuota("Gym", 4, 60, "health-gym"), 3);
        ConstraintEntity learning = ConstraintMapper.toEntity(new DurationQuota("Learning", 300, "career"), 4);
        ConstraintEntity social = ConstraintMapper.toEntity(new EveningQuota("Social", 2, "social"), 5);

        assertThat(gym).isInstanceOf(FrequencyQuotaEntity.class);
        assertThat(gym.getPosition()).isEqualTo(3);
        assertThat(learning).isInstanceOf(DurationQuotaEntity.class);
        assertThat(learning.getPosition()).isEqualTo(4);
        assertThat(social).isInstanceOf(EveningQuotaEntity.class);
        assertThat(social.getLabel()).isEqualTo("Social");
    }
}
