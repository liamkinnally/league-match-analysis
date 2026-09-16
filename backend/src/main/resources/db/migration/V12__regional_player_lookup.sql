ALTER TABLE league_analysis.ingestion_run
    DROP CONSTRAINT ingestion_run_platform_route_check,
    DROP CONSTRAINT ingestion_run_regional_route_check,
    ADD CONSTRAINT ingestion_run_platform_route_check CHECK (platform_route IN ('NA1', 'EUW1', 'EUN1', 'KR')),
    ADD CONSTRAINT ingestion_run_regional_route_check CHECK (
        (platform_route = 'NA1' AND regional_route = 'AMERICAS') OR
        (platform_route IN ('EUW1', 'EUN1') AND regional_route = 'EUROPE') OR
        (platform_route = 'KR' AND regional_route = 'ASIA'));

ALTER TABLE league_analysis.source_capture
    DROP CONSTRAINT source_capture_platform_route_check,
    DROP CONSTRAINT source_capture_regional_route_check,
    ADD CONSTRAINT source_capture_platform_route_check CHECK (platform_route IS NULL OR platform_route IN ('NA1', 'EUW1', 'EUN1', 'KR')),
    ADD CONSTRAINT source_capture_regional_route_check CHECK (regional_route IN ('AMERICAS', 'EUROPE', 'ASIA'));

CREATE INDEX riot_identity_platform_prefix_idx
    ON league_analysis.riot_identity (platform_route, lower(game_name) text_pattern_ops, lower(tag_line));
CREATE INDEX ingestion_run_platform_alias_idx
    ON league_analysis.ingestion_run (platform_route, lower(requested_game_name), lower(requested_tag_line), completed_at DESC)
    WHERE public_request AND lookup_kind = 'HISTORY';
