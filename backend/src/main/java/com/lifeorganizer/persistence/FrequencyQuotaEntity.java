package com.lifeorganizer.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

@Entity
@DiscriminatorValue("FREQUENCY_QUOTA")
public class FrequencyQuotaEntity extends ConstraintEntity {

    @Column(name = "times_per_week")
    private Integer timesPerWeek;

    @Column(name = "session_minutes")
    private Integer sessionMinutes;

    @Column(name = "goal_id")
    private String goalId;

    protected FrequencyQuotaEntity() {
    }

    public FrequencyQuotaEntity(String label, int position, int timesPerWeek, int sessionMinutes, String goalId) {
        super(label, position);
        this.timesPerWeek = timesPerWeek;
        this.sessionMinutes = sessionMinutes;
        this.goalId = goalId;
    }

    public Integer getTimesPerWeek() {
        return timesPerWeek;
    }

    public Integer getSessionMinutes() {
        return sessionMinutes;
    }

    public String getGoalId() {
        return goalId;
    }
}
