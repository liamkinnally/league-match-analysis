CREATE TABLE league_analysis.player_profile_current (
    puuid text NOT NULL REFERENCES league_analysis.riot_identity(puuid) ON DELETE CASCADE,
    platform text NOT NULL,
    profile_icon_id integer CHECK (profile_icon_id >= 0),
    summoner_level bigint CHECK (summoner_level >= 0),
    revision_at timestamptz,
    fetched_at timestamptz,
    retry_at timestamptz,
    lease_until timestamptz,
    lease_id uuid,
    error text,
    provenance text NOT NULL DEFAULT 'summoner-v4-v1',
    recent_requested_at timestamptz,
    recent_run_id uuid REFERENCES league_analysis.ingestion_run(id) ON DELETE SET NULL,
    profile_pending_run_id uuid REFERENCES league_analysis.ingestion_run(id) ON DELETE SET NULL,
    profile_pending_until timestamptz,
    PRIMARY KEY (puuid, platform)
);
CREATE TABLE league_analysis.rank_refresh_state (
    puuid text NOT NULL REFERENCES league_analysis.riot_identity(puuid) ON DELETE CASCADE,
    platform text NOT NULL,
    entries jsonb,
    fetched_at timestamptz,
    retry_at timestamptz,
    lease_until timestamptz,
    lease_id uuid,
    error text,
    refresh_id uuid,
    provenance text NOT NULL DEFAULT 'league-v4-v1',
    PRIMARY KEY (puuid, platform)
);
CREATE TABLE league_analysis.rank_observation (
    id uuid PRIMARY KEY,
    puuid text NOT NULL REFERENCES league_analysis.riot_identity(puuid) ON DELETE CASCADE,
    platform text NOT NULL,
    queue_type text NOT NULL,
    observed_at timestamptz NOT NULL,
    status text NOT NULL CHECK (status IN ('ranked','unranked')),
    tier text,
    division text,
    league_points integer CHECK (league_points >= 0),
    wins integer CHECK (wins >= 0),
    losses integer CHECK (losses >= 0),
    period_key text,
    refresh_id uuid NOT NULL,
    provenance text NOT NULL DEFAULT 'league-v4-v1',
    UNIQUE (puuid, platform, queue_type, refresh_id),
    CHECK ((status='unranked' AND tier IS NULL AND division IS NULL AND league_points IS NULL AND wins IS NULL AND losses IS NULL)
        OR (status='ranked' AND tier IS NOT NULL AND division IS NOT NULL AND league_points IS NOT NULL))
);
CREATE INDEX rank_observation_history_idx ON league_analysis.rank_observation(puuid, platform, queue_type, observed_at DESC, id DESC);
DO $$
DECLARE relation text;
BEGIN
    FOREACH relation IN ARRAY ARRAY['player_profile_current','rank_refresh_state','rank_observation'] LOOP
        EXECUTE format('CREATE TRIGGER privacy_write_guard BEFORE INSERT OR UPDATE ON league_analysis.%I FOR EACH ROW EXECUTE FUNCTION league_analysis.privacy_write_guard()', relation);
    END LOOP;
END
$$;
