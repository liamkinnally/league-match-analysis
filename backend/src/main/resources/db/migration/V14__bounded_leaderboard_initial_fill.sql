-- Initial collection is a one-time, bounded phase; restart never resets its window or host reservations.
ALTER TABLE league_analysis.leaderboard_state
    ADD COLUMN initial_fill_started_at timestamptz,
    ADD COLUMN initial_fill_deadline timestamptz,
    ADD COLUMN initial_fill_completed_at timestamptz,
    ADD COLUMN na_request_due_at timestamptz NOT NULL DEFAULT '1970-01-01T00:00:00Z',
    ADD COLUMN americas_request_due_at timestamptz NOT NULL DEFAULT '1970-01-01T00:00:00Z';
