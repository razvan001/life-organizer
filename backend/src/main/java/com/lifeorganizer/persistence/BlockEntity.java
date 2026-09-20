package com.lifeorganizer.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalTime;

@Entity
@Table(name = "block")
public class BlockEntity {

    @Id
    private String id;

    @Column(name = "block_date")
    private LocalDate date;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    private String title;

    private String kind;

    /** Not a foreign key on purpose - see the schema migration. */
    @Column(name = "goal_id")
    private String goalId;

    private String status;

    @Column(name = "postpone_count")
    private int postponeCount;

    private boolean flagged;

    private String reason;

    protected BlockEntity() {
    }

    public BlockEntity(String id, LocalDate date, LocalTime startTime, LocalTime endTime, String title, String kind,
                       String goalId, String status, int postponeCount, boolean flagged, String reason) {
        this.id = id;
        this.date = date;
        this.startTime = startTime;
        this.endTime = endTime;
        this.title = title;
        this.kind = kind;
        this.goalId = goalId;
        this.status = status;
        this.postponeCount = postponeCount;
        this.flagged = flagged;
        this.reason = reason;
    }

    public String getId() {
        return id;
    }

    public LocalDate getDate() {
        return date;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public String getTitle() {
        return title;
    }

    public String getKind() {
        return kind;
    }

    public String getGoalId() {
        return goalId;
    }

    public String getStatus() {
        return status;
    }

    public int getPostponeCount() {
        return postponeCount;
    }

    public boolean isFlagged() {
        return flagged;
    }

    public String getReason() {
        return reason;
    }
}
