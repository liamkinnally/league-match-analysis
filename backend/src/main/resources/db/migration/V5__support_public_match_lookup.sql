ALTER TABLE league_analysis.ingestion_run
    ADD COLUMN resolved_puuid text,
    ADD COLUMN public_request boolean NOT NULL DEFAULT false,
    ADD COLUMN retry_not_before timestamptz;

CREATE INDEX ingestion_run_public_recent_idx
    ON league_analysis.ingestion_run (lower(requested_game_name), lower(requested_tag_line), completed_at DESC)
    WHERE public_request AND status = 'COMPLETE';
CREATE INDEX ingestion_run_public_cooldown_idx
    ON league_analysis.ingestion_run (retry_not_before DESC)
    WHERE public_request AND retry_not_before IS NOT NULL;
CREATE INDEX ingestion_run_public_running_idx
    ON league_analysis.ingestion_run (status) WHERE public_request AND status = 'RUNNING';
