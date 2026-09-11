-- Only the operator command writes the ledger checkpoint/receipts. No HTTP route exposes them.
CREATE TABLE league_analysis.privacy_control (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    ledger_sha256 text CHECK (ledger_sha256 ~ '^[0-9a-f]{64}$')
);
INSERT INTO league_analysis.privacy_control(singleton) VALUES (true);

CREATE TABLE league_analysis.privacy_exclusion (
    kind text NOT NULL CHECK (kind IN ('puuid', 'riot_id', 'match')),
    subject_hash text NOT NULL CHECK (subject_hash ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (kind, subject_hash)
);

CREATE TABLE league_analysis.privacy_completion (
    operation_id uuid PRIMARY KEY,
    completed_at timestamptz NOT NULL,
    mode text NOT NULL CHECK (mode IN ('erase-only', 'exclude')),
    affected_counts jsonb NOT NULL
);

CREATE FUNCTION league_analysis.privacy_hash(value text) RETURNS text
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT encode(sha256(convert_to(value, 'UTF8')), 'hex')
$$;

CREATE FUNCTION league_analysis.privacy_blocked(subject_kind text, value text) RETURNS boolean
LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM league_analysis.privacy_exclusion
        WHERE kind = subject_kind AND subject_hash = league_analysis.privacy_hash(value))
$$;

-- Scan strings, including metadata participants and documents not yet decoded/normalized.
CREATE FUNCTION league_analysis.privacy_json_excluded(document jsonb) RETURNS boolean
LANGUAGE sql STABLE AS $$
    SELECT EXISTS (
        SELECT 1 FROM jsonb_path_query(document, 'strict $.** ? (@.type() == "string")') AS leaf(value)
        JOIN league_analysis.privacy_exclusion e
          ON e.subject_hash = league_analysis.privacy_hash(leaf.value #>> '{}')
        WHERE e.kind IN ('puuid', 'match')
    )
$$;

CREATE FUNCTION league_analysis.privacy_write_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE body jsonb;
BEGIN
    -- Application lifetime lease uses a separate key. This transaction gate also
    -- serializes direct storage clients against removal, including old queued writes.
    PERFORM pg_advisory_xact_lock_shared(734178220047);
    IF current_setting('league_analysis.removal_operator', true) = 'on' THEN
        RETURN NEW;
    END IF;
    body := to_jsonb(NEW);
    IF league_analysis.privacy_blocked('puuid', body->>'puuid')
        OR league_analysis.privacy_blocked('puuid', body->>'resolved_puuid')
        OR league_analysis.privacy_blocked('match', body->>'match_id')
        OR (body->>'resolved_puuid' IS NULL AND
            league_analysis.privacy_blocked('riot_id', lower(body->>'requested_game_name') || '#' || lower(body->>'requested_tag_line')))
        OR league_analysis.privacy_json_excluded(body) THEN
        RAISE EXCEPTION 'PRIVACY_EXCLUDED' USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'source_capture' AND (
        (league_analysis.privacy_blocked('riot_id', lower((body->>'resource_key')))
            AND NOT EXISTS (SELECT 1 FROM league_analysis.source_payload p
                WHERE p.id=cast(body->>'source_payload_id' as uuid) AND p.source_kind='ACCOUNT'
                AND jsonb_typeof(p.payload_json->'puuid')='string'
                AND length(p.payload_json->>'puuid')>0
                AND NOT league_analysis.privacy_blocked('puuid',p.payload_json->>'puuid')))
        OR league_analysis.privacy_blocked('puuid', (body->>'resource_key'))
        OR league_analysis.privacy_blocked('match', (body->>'resource_key'))
    ) THEN
        RAISE EXCEPTION 'PRIVACY_EXCLUDED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

DO $$
DECLARE relation text;
BEGIN
    FOREACH relation IN ARRAY ARRAY['ingestion_run','source_payload','source_capture','ingestion_item',
        'riot_identity','riot_match','riot_team','riot_participant','participant_state_observation','match_event','evidence_coverage']
    LOOP
        EXECUTE format('CREATE TRIGGER privacy_write_guard BEFORE INSERT OR UPDATE ON league_analysis.%I FOR EACH ROW EXECUTE FUNCTION league_analysis.privacy_write_guard()', relation);
    END LOOP;
END
$$;
