-- Goals carry their own hours per week; quotas, priority and deadline go.
-- Existing goals start at 0 minutes (the user fills in hours on the Setup page). Goal ids and fixed blocks stay.
ALTER TABLE goal ADD COLUMN minutes_per_week INTEGER NOT NULL DEFAULT 0;
ALTER TABLE goal DROP COLUMN priority, DROP COLUMN deadline;

DELETE FROM plan_constraint WHERE type <> 'FIXED_BLOCK';
ALTER TABLE plan_constraint
    DROP COLUMN times_per_week,
    DROP COLUMN session_minutes,
    DROP COLUMN goal_id,
    DROP COLUMN minutes_per_week,
    DROP COLUMN category_id,
    DROP COLUMN evenings_per_week;

-- Weekly reviews no longer propose priorities; one still waiting for Apply can never be applied now.
UPDATE chat_message SET proposal_status = 'EXPIRED'
WHERE proposal_status = 'PENDING' AND proposal::jsonb ->> 'kind' = 'PRIORITIES';
