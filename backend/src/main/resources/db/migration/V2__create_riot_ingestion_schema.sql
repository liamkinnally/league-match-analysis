CREATE TABLE league_analysis.ingestion_run (
    id uuid PRIMARY KEY,
    requested_game_name text NOT NULL,
    requested_tag_line text NOT NULL,
    platform_route text NOT NULL CHECK (platform_route = 'NA1'),
    regional_route text NOT NULL CHECK (regional_route = 'AMERICAS'),
    queue_id integer NOT NULL CHECK (queue_id = 420),
    match_limit integer NOT NULL CHECK (match_limit BETWEEN 1 AND 20),
    status text NOT NULL CHECK (status IN ('RUNNING', 'COMPLETE', 'PARTIAL', 'FAILED')),
    failure_code text,
    failure_message text,
    started_at timestamptz NOT NULL,
    completed_at timestamptz
);

CREATE TABLE league_analysis.source_payload (
    id uuid PRIMARY KEY,
    source_kind text NOT NULL CHECK (
        source_kind IN ('ACCOUNT', 'MATCH_LIST', 'MATCH_DETAIL', 'MATCH_TIMELINE')),
    body_sha256 char(64) NOT NULL CHECK (body_sha256 ~ '^[0-9a-f]{64}$'),
    body_size_bytes integer NOT NULL CHECK (body_size_bytes >= 0),
    payload_json jsonb NOT NULL,
    UNIQUE (source_kind, body_sha256)
);

CREATE TABLE league_analysis.source_capture (
    id uuid PRIMARY KEY,
    ingestion_run_id uuid NOT NULL REFERENCES league_analysis.ingestion_run(id),
    source_payload_id uuid NOT NULL REFERENCES league_analysis.source_payload(id),
    source_kind text NOT NULL CHECK (
        source_kind IN ('ACCOUNT', 'MATCH_LIST', 'MATCH_DETAIL', 'MATCH_TIMELINE')),
    resource_key text NOT NULL,
    regional_route text NOT NULL CHECK (regional_route = 'AMERICAS'),
    platform_route text CHECK (platform_route IS NULL OR platform_route = 'NA1'),
    captured_at timestamptz NOT NULL,
    http_status smallint NOT NULL CHECK (http_status BETWEEN 100 AND 599),
    provider_game_version text,
    response_metadata jsonb NOT NULL,
    parser_version text NOT NULL,
    attempt integer NOT NULL CHECK (attempt >= 1),
    UNIQUE (ingestion_run_id, source_kind, resource_key, attempt)
);

CREATE TABLE league_analysis.ingestion_item (
    ingestion_run_id uuid NOT NULL REFERENCES league_analysis.ingestion_run(id),
    match_id text NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    status text NOT NULL CHECK (
        status IN ('PENDING', 'RUNNING', 'COMPLETE', 'PARTIAL', 'FAILED', 'SKIPPED')),
    detail_capture_id uuid REFERENCES league_analysis.source_capture(id),
    timeline_capture_id uuid REFERENCES league_analysis.source_capture(id),
    failure_code text,
    failure_message text,
    started_at timestamptz,
    completed_at timestamptz,
    PRIMARY KEY (ingestion_run_id, match_id),
    UNIQUE (ingestion_run_id, ordinal)
);

CREATE TABLE league_analysis.riot_identity (
    puuid text PRIMARY KEY,
    game_name text,
    tag_line text,
    platform_route text,
    first_observed_at timestamptz NOT NULL,
    last_observed_at timestamptz NOT NULL,
    last_source_capture_id uuid NOT NULL REFERENCES league_analysis.source_capture(id)
);

CREATE TABLE league_analysis.riot_match (
    match_id text PRIMARY KEY,
    game_id bigint NOT NULL,
    queue_id integer NOT NULL,
    map_id integer NOT NULL,
    game_mode text NOT NULL,
    game_type text NOT NULL,
    game_version text NOT NULL,
    data_version text NOT NULL,
    game_creation_ms bigint NOT NULL,
    game_start_ms bigint,
    game_end_ms bigint,
    game_duration_seconds bigint NOT NULL,
    detail_source_capture_id uuid NOT NULL REFERENCES league_analysis.source_capture(id),
    timeline_source_capture_id uuid REFERENCES league_analysis.source_capture(id),
    materialization_version text NOT NULL,
    materialized_at timestamptz NOT NULL
);

CREATE TABLE league_analysis.riot_team (
    match_id text NOT NULL REFERENCES league_analysis.riot_match(match_id) ON DELETE CASCADE,
    team_id integer NOT NULL,
    win boolean NOT NULL,
    objectives jsonb NOT NULL,
    PRIMARY KEY (match_id, team_id)
);

CREATE TABLE league_analysis.riot_participant (
    match_id text NOT NULL REFERENCES league_analysis.riot_match(match_id) ON DELETE CASCADE,
    participant_id integer NOT NULL CHECK (participant_id BETWEEN 1 AND 10),
    puuid text NOT NULL REFERENCES league_analysis.riot_identity(puuid),
    team_id integer NOT NULL,
    champion_id integer NOT NULL,
    champion_name text NOT NULL,
    team_position text NOT NULL,
    kills integer NOT NULL,
    deaths integer NOT NULL,
    assists integer NOT NULL,
    total_minions_killed integer NOT NULL,
    neutral_minions_killed integer NOT NULL,
    gold_earned integer NOT NULL,
    gold_spent integer NOT NULL,
    vision_score integer NOT NULL,
    summoner_spell_one_id integer NOT NULL,
    summoner_spell_two_id integer NOT NULL,
    win boolean NOT NULL,
    PRIMARY KEY (match_id, participant_id),
    UNIQUE (match_id, puuid),
    FOREIGN KEY (match_id, team_id)
        REFERENCES league_analysis.riot_team(match_id, team_id)
);

CREATE TABLE league_analysis.participant_state_observation (
    id uuid PRIMARY KEY,
    match_id text NOT NULL,
    participant_id integer NOT NULL,
    represented_at_ms bigint NOT NULL CHECK (represented_at_ms >= 0),
    x integer,
    y integer,
    current_gold integer NOT NULL,
    total_gold integer NOT NULL,
    level integer NOT NULL,
    xp integer NOT NULL,
    minions_killed integer NOT NULL,
    jungle_minions_killed integer NOT NULL,
    source_capture_id uuid NOT NULL REFERENCES league_analysis.source_capture(id),
    method_version text NOT NULL,
    UNIQUE (match_id, participant_id, represented_at_ms, source_capture_id),
    FOREIGN KEY (match_id, participant_id)
        REFERENCES league_analysis.riot_participant(match_id, participant_id) ON DELETE CASCADE
);

CREATE TABLE league_analysis.match_event (
    id uuid PRIMARY KEY,
    match_id text NOT NULL REFERENCES league_analysis.riot_match(match_id) ON DELETE CASCADE,
    represented_at_ms bigint NOT NULL CHECK (represented_at_ms >= 0),
    frame_at_ms bigint NOT NULL CHECK (frame_at_ms >= 0),
    frame_event_index integer NOT NULL CHECK (frame_event_index >= 0),
    provider_event_type text NOT NULL,
    canonical_event_kind text NOT NULL CHECK (
        canonical_event_kind IN ('ITEM', 'CHAMPION_KILL', 'OBJECTIVE', 'WARD', 'PROGRESSION', 'OTHER')),
    actor_participant_id integer,
    target_participant_id integer,
    position_x integer,
    position_y integer,
    event_payload jsonb NOT NULL,
    source_capture_id uuid NOT NULL REFERENCES league_analysis.source_capture(id),
    method_version text NOT NULL,
    UNIQUE (match_id, source_capture_id, frame_at_ms, frame_event_index)
);

CREATE TABLE league_analysis.evidence_coverage (
    id uuid PRIMARY KEY,
    match_id text NOT NULL REFERENCES league_analysis.riot_match(match_id) ON DELETE CASCADE,
    source_kind text NOT NULL CHECK (
        source_kind IN ('ACCOUNT', 'MATCH_LIST', 'MATCH_DETAIL', 'MATCH_TIMELINE')),
    signal text NOT NULL,
    status text NOT NULL CHECK (
        status IN ('OBSERVED', 'RECONSTRUCTED', 'ESTIMATED', 'LIMITED', 'UNKNOWN', 'UNAVAILABLE')),
    represented_start_ms bigint CHECK (represented_start_ms IS NULL OR represented_start_ms >= 0),
    represented_end_ms bigint CHECK (represented_end_ms IS NULL OR represented_end_ms >= 0),
    details jsonb NOT NULL,
    source_capture_id uuid REFERENCES league_analysis.source_capture(id),
    method_version text NOT NULL,
    UNIQUE (match_id, source_kind, signal, method_version),
    CHECK (represented_start_ms IS NULL OR represented_end_ms IS NULL
        OR represented_end_ms >= represented_start_ms)
);

CREATE INDEX participant_state_match_time_idx
    ON league_analysis.participant_state_observation(match_id, represented_at_ms, participant_id);

CREATE INDEX match_event_match_time_idx
    ON league_analysis.match_event(match_id, represented_at_ms, frame_event_index);

CREATE INDEX source_capture_run_kind_idx
    ON league_analysis.source_capture(ingestion_run_id, source_kind);
