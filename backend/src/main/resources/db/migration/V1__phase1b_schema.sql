CREATE TABLE category (
    id       VARCHAR(255) PRIMARY KEY,
    name     VARCHAR(255) NOT NULL,
    position INTEGER      NOT NULL
);

CREATE TABLE goal (
    id                    VARCHAR(255) PRIMARY KEY,
    category_id           VARCHAR(255) NOT NULL REFERENCES category (id),
    name                  VARCHAR(255) NOT NULL,
    priority              INTEGER      NOT NULL,
    deadline              DATE         NULL,
    default_block_minutes INTEGER      NOT NULL,
    energy                VARCHAR(20)  NOT NULL,
    preferred_time        VARCHAR(20)  NOT NULL,
    position              INTEGER      NOT NULL
);

-- Single table for the four Constraint variants; columns unused by a variant stay NULL.
-- (The table is not called "constraint": that is a reserved word in Postgres.)
CREATE TABLE plan_constraint (
    id                BIGSERIAL    PRIMARY KEY,
    type              VARCHAR(30)  NOT NULL,
    label             VARCHAR(255) NOT NULL,
    position          INTEGER      NOT NULL,
    start_time        TIME         NULL,
    end_time          TIME         NULL,
    days              VARCHAR(100) NULL,
    times_per_week    INTEGER      NULL,
    session_minutes   INTEGER      NULL,
    goal_id           VARCHAR(255) NULL REFERENCES goal (id),
    minutes_per_week  INTEGER      NULL,
    category_id       VARCHAR(255) NULL REFERENCES category (id),
    evenings_per_week INTEGER      NULL
);

-- goal_id is deliberately NOT a foreign key: a block is a historical record and
-- replacing the setup (which deletes goals) must never fail because blocks exist.
CREATE TABLE block (
    id             VARCHAR(255) PRIMARY KEY,
    block_date     DATE         NOT NULL,
    start_time     TIME         NOT NULL,
    end_time       TIME         NOT NULL,
    title          VARCHAR(255) NOT NULL,
    kind           VARCHAR(20)  NOT NULL,
    goal_id        VARCHAR(255) NULL,
    status         VARCHAR(20)  NOT NULL,
    postpone_count INTEGER      NOT NULL,
    flagged        BOOLEAN      NOT NULL,
    reason         VARCHAR(500) NULL
);
CREATE INDEX idx_block_date ON block (block_date);

CREATE TABLE day_warning (
    id           BIGSERIAL    PRIMARY KEY,
    warning_date DATE         NOT NULL,
    position     INTEGER      NOT NULL,
    message      VARCHAR(500) NOT NULL
);
CREATE INDEX idx_day_warning_date ON day_warning (warning_date);
