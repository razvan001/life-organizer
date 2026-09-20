package com.lifeorganizer.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

import java.time.LocalTime;

@Entity
@DiscriminatorValue("FIXED_BLOCK")
public class FixedBlockEntity extends ConstraintEntity {

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    /** Comma-joined DayOfWeek names in week order, e.g. {@code MONDAY,TUESDAY}. */
    private String days;

    protected FixedBlockEntity() {
    }

    public FixedBlockEntity(String label, int position, LocalTime startTime, LocalTime endTime, String days) {
        super(label, position);
        this.startTime = startTime;
        this.endTime = endTime;
        this.days = days;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public String getDays() {
        return days;
    }
}
