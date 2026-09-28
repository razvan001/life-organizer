CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE note (
    id         BIGSERIAL    PRIMARY KEY,
    title      VARCHAR(255) NOT NULL,
    body       TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

-- One row per embedded chunk. source_id is the note id, or the ISO date for a DAY summary.
-- Not a foreign key: DAY rows have no parent table, and note deletes remove their chunks in code.
CREATE TABLE memory_chunk (
    id           BIGSERIAL    PRIMARY KEY,
    source_type  VARCHAR(10)  NOT NULL,
    source_id    VARCHAR(255) NOT NULL,
    source_date  DATE         NULL,
    title        VARCHAR(255) NOT NULL,
    text         TEXT         NOT NULL,
    content_hash VARCHAR(64)  NOT NULL,
    embedding    VECTOR(384)  NOT NULL
);
CREATE INDEX idx_memory_chunk_source ON memory_chunk (source_type, source_id);
CREATE INDEX idx_memory_chunk_embedding ON memory_chunk USING hnsw (embedding vector_cosine_ops);
