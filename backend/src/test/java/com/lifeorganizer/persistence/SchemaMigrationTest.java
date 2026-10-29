package com.lifeorganizer.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SchemaMigrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migrationCreatesEveryTable() {
        List<String> tables = jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'",
            String.class);

        assertThat(tables).contains("category", "goal", "plan_constraint", "block", "day_warning", "note",
            "memory_chunk", "chat_message");
    }

    @Test
    void orderedTablesHaveAPositionColumn() {
        for (String table : List.of("category", "goal", "plan_constraint", "day_warning")) {
            Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_name = ? and column_name = 'position'",
                Integer.class, table);
            assertThat(count).as("position column on " + table).isEqualTo(1);
        }
    }

    @Test
    void blockGoalIdIsIntentionallyNotAForeignKey() {
        // A block is a historical record. Replacing the setup deletes goals; an FK here would make that fail.
        Integer foreignKeys = jdbc.queryForObject(
            "select count(*) from information_schema.table_constraints where table_name = 'block' and constraint_type = 'FOREIGN KEY'",
            Integer.class);

        assertThat(foreignKeys).isZero();
    }

    @Test
    void fixedBlocksReferenceNothing() {
        Integer foreignKeys = jdbc.queryForObject(
            "select count(*) from information_schema.table_constraints where table_name = 'plan_constraint' and constraint_type = 'FOREIGN KEY'",
            Integer.class);

        assertThat(foreignKeys).isZero();
    }
}
