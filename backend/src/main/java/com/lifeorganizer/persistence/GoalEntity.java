package com.lifeorganizer.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;


@Entity
@Table(name = "goal")
public class GoalEntity {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "category_id")
    private CategoryEntity category;

    private String name;

    @Column(name = "minutes_per_week")
    private int minutesPerWeek;

    @Column(name = "default_block_minutes")
    private int defaultBlockMinutes;

    private String energy;

    @Column(name = "preferred_time")
    private String preferredTime;

    private int position;

    protected GoalEntity() {
    }

    public GoalEntity(String id, CategoryEntity category, String name, int minutesPerWeek, int defaultBlockMinutes,
                      String energy, String preferredTime, int position) {
        this.id = id;
        this.category = category;
        this.name = name;
        this.minutesPerWeek = minutesPerWeek;
        this.defaultBlockMinutes = defaultBlockMinutes;
        this.energy = energy;
        this.preferredTime = preferredTime;
        this.position = position;
    }

    public String getId() {
        return id;
    }

    public CategoryEntity getCategory() {
        return category;
    }

    public String getName() {
        return name;
    }

    public int getMinutesPerWeek() {
        return minutesPerWeek;
    }

    public int getDefaultBlockMinutes() {
        return defaultBlockMinutes;
    }

    public String getEnergy() {
        return energy;
    }

    public String getPreferredTime() {
        return preferredTime;
    }

    public int getPosition() {
        return position;
    }
}
