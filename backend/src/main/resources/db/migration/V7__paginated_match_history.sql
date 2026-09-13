ALTER TABLE league_analysis.ingestion_run
    DROP CONSTRAINT ingestion_run_queue_id_check,
    ADD CONSTRAINT ingestion_run_queue_id_check CHECK (queue_id IN (400, 420, 430, 440, 480, 490)),
    ADD COLUMN page_start integer NOT NULL DEFAULT 0 CHECK (page_start >= 0),
    ADD COLUMN page_end_time bigint CHECK (page_end_time IS NULL OR page_end_time >= 0),
    ADD COLUMN previous_run_id uuid REFERENCES league_analysis.ingestion_run(id) ON DELETE SET NULL,
    ADD COLUMN has_more boolean NOT NULL DEFAULT false,
    ADD COLUMN lookup_kind text NOT NULL DEFAULT 'HISTORY' CHECK (lookup_kind IN ('HISTORY', 'TIMELINE'));

CREATE INDEX ingestion_run_history_page_idx
    ON league_analysis.ingestion_run (previous_run_id, completed_at DESC)
    WHERE public_request AND lookup_kind = 'HISTORY' AND status = 'COMPLETE';
CREATE INDEX ingestion_run_history_refresh_idx
    ON league_analysis.ingestion_run (resolved_puuid, started_at DESC)
    WHERE public_request AND lookup_kind = 'HISTORY' AND page_start = 0;

-- Successful account verification remains identity evidence even when match work
-- later fails. Resolve requested/canonical aliases without scanning raw payloads.
CREATE INDEX ingestion_run_verified_alias_idx
    ON league_analysis.ingestion_run (lower(requested_game_name), lower(requested_tag_line))
    WHERE resolved_puuid IS NOT NULL;
CREATE INDEX source_payload_account_alias_idx
    ON league_analysis.source_payload (lower(payload_json->>'gameName'), lower(payload_json->>'tagLine'))
    WHERE source_kind = 'ACCOUNT';
CREATE INDEX source_capture_account_payload_idx
    ON league_analysis.source_capture (source_payload_id, captured_at DESC)
    WHERE source_kind = 'ACCOUNT';
CREATE INDEX riot_identity_alias_idx
    ON league_analysis.riot_identity (lower(game_name), lower(tag_line));
