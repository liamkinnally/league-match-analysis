-- Only the current top 100 and one bounded in-progress cycle are retained.
CREATE TABLE league_analysis.leaderboard_state (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    fetched_at timestamptz,
    ladder_due_at timestamptz NOT NULL DEFAULT now(),
    request_due_at timestamptz NOT NULL DEFAULT now(),
    lease_id uuid,
    lease_until timestamptz,
    cycle_started_at timestamptz,
    next_tier integer NOT NULL DEFAULT 0 CHECK (next_tier BETWEEN 0 AND 2),
    failed boolean NOT NULL DEFAULT false
);
INSERT INTO league_analysis.leaderboard_state(singleton) VALUES (true);
CREATE TABLE league_analysis.leaderboard_entry (
    pending boolean NOT NULL,
    puuid text NOT NULL,
    tier text NOT NULL CHECK (tier IN ('CHALLENGER','GRANDMASTER','MASTER')),
    league_points integer NOT NULL CHECK (league_points >= 0),
    wins integer NOT NULL CHECK (wins >= 0),
    losses integer NOT NULL CHECK (losses >= 0),
    PRIMARY KEY (pending,puuid)
);
CREATE TABLE league_analysis.leaderboard_member (
    puuid text PRIMARY KEY,
    game_name text,
    tag_line text,
    profile_icon_id integer CHECK (profile_icon_id >= 0),
    name_fetched_at timestamptz,
    icon_fetched_at timestamptz,
    name_due_at timestamptz NOT NULL DEFAULT now(),
    icon_due_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((game_name IS NULL) = (tag_line IS NULL))
);
CREATE TRIGGER privacy_write_guard BEFORE INSERT OR UPDATE ON league_analysis.leaderboard_entry
    FOR EACH ROW EXECUTE FUNCTION league_analysis.privacy_write_guard();
CREATE TRIGGER privacy_write_guard BEFORE INSERT OR UPDATE ON league_analysis.leaderboard_member
    FOR EACH ROW EXECUTE FUNCTION league_analysis.privacy_write_guard();
