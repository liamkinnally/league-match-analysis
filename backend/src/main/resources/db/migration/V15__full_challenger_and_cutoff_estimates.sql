-- Aggregates contain LP frequencies only, never Master/Grandmaster identities or raw payloads.
CREATE TABLE league_analysis.leaderboard_cutoff (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    cycle_at timestamptz,
    challenger_histogram jsonb,
    grandmaster_histogram jsonb,
    fetched_at timestamptz,
    failed boolean NOT NULL DEFAULT false,
    challenger_boundary integer CHECK (challenger_boundary >= 0),
    challenger_promotion integer CHECK (challenger_promotion >= 800),
    grandmaster_boundary integer CHECK (grandmaster_boundary >= 0),
    grandmaster_promotion integer CHECK (grandmaster_promotion >= 400)
);
INSERT INTO league_analysis.leaderboard_cutoff(singleton) VALUES (true);
-- One new expansion window starts only after the first full Challenger publication.
ALTER TABLE league_analysis.leaderboard_state
    ADD COLUMN initial_fill_ready boolean NOT NULL DEFAULT false,
    ADD COLUMN claimed_work_tier integer CHECK (claimed_work_tier BETWEEN -1 AND 2);
UPDATE league_analysis.leaderboard_state SET initial_fill_started_at=null,initial_fill_deadline=null,
    initial_fill_completed_at=null,next_tier=0,cycle_started_at=null,ladder_due_at=least(ladder_due_at,now());
DELETE FROM league_analysis.leaderboard_entry WHERE pending OR tier<>'CHALLENGER';
DELETE FROM league_analysis.leaderboard_member m WHERE NOT EXISTS (
    SELECT 1 FROM league_analysis.leaderboard_entry e WHERE e.puuid=m.puuid);
