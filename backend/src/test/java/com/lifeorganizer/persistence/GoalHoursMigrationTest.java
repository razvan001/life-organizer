package com.lifeorganizer.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V4 moves a saved setup from quotas to per-goal hours. Runs Flyway by hand: migrate to V3, add data, then V4. */
@Testcontainers
class GoalHoursMigrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target(target).load();
    }

    @Test
    void v4KeepsGoalsAndFixedBlocksAndDropsQuotas() {
        DriverManagerDataSource dataSource =
            new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        flyway(dataSource, "3").migrate();

        jdbc.update("insert into category (id, name, position) values ('health', 'Health', 0)");
        jdbc.update("insert into goal (id, category_id, name, priority, deadline, default_block_minutes, energy,"
            + " preferred_time, position) values ('health-gym', 'health', 'Gym', 4, '2026-12-01', 45, 'HIGH', 'MORNING', 0)");
        jdbc.update("insert into plan_constraint (type, label, position, start_time, end_time, days)"
            + " values ('FIXED_BLOCK', 'Work', 0, '08:30', '17:00', 'MONDAY,TUESDAY')");
        jdbc.update("insert into plan_constraint (type, label, position, times_per_week, session_minutes, goal_id)"
            + " values ('FREQUENCY_QUOTA', 'Gym', 1, 4, 60, 'health-gym')");
        jdbc.update("insert into chat_message (chat_date, position, role, text, proposal, proposal_status, created_at)"
            + " values ('2026-09-30', 0, 'ASSISTANT', 'review', '{\"kind\":\"PRIORITIES\",\"rationale\":\"r\"}', 'PENDING', now()),"
            + " ('2026-09-30', 1, 'ASSISTANT', 'plan', '{\"kind\":\"PLAN\",\"rationale\":\"r\"}', 'PENDING', now()),"
            + " ('2026-09-29', 0, 'ASSISTANT', 'old', '{\"kind\":\"PRIORITIES\",\"rationale\":\"r\"}', 'APPLIED', now())");

        flyway(dataSource, "latest").migrate();

        assertThat(jdbc.queryForList("select id, name, minutes_per_week, default_block_minutes, energy, preferred_time from goal"))
            .containsExactly(Map.of("id", "health-gym", "name", "Gym", "minutes_per_week", 0, "default_block_minutes", 45,
                "energy", "HIGH", "preferred_time", "MORNING"));
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'goal'",
            String.class)).doesNotContain("priority", "deadline");
        assertThat(jdbc.queryForList("select type, label, start_time::text as s, end_time::text as e, days from plan_constraint"))
            .containsExactly(Map.of("type", "FIXED_BLOCK", "label", "Work", "s", "08:30:00", "e", "17:00:00",
                "days", "MONDAY,TUESDAY"));
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'plan_constraint'",
            String.class)).doesNotContain("times_per_week", "session_minutes", "goal_id", "minutes_per_week",
                "category_id", "evenings_per_week");
        assertThat(jdbc.queryForList("select text from chat_message where proposal_status = 'EXPIRED'", String.class))
            .containsExactly("review");
        assertThat(jdbc.queryForList("select proposal_status from chat_message order by chat_date, position", String.class))
            .isEqualTo(List.of("APPLIED", "EXPIRED", "PENDING"));
    }
}
