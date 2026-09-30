-- One row per chat message. The thread is the calendar day (chat_date); position orders messages in it.
-- proposal is the assistant's proposed change set as JSON (null when there is none); proposal_status tracks it.
CREATE TABLE chat_message (
    id              BIGSERIAL    PRIMARY KEY,
    chat_date       DATE         NOT NULL,
    position        INTEGER      NOT NULL,
    role            VARCHAR(10)  NOT NULL,
    text            TEXT         NOT NULL,
    proposal        TEXT         NULL,
    proposal_status VARCHAR(10)  NULL,
    created_at      TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX idx_chat_message_thread ON chat_message (chat_date, position);
