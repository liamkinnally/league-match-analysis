ALTER TABLE league_analysis.riot_participant
    ADD COLUMN rune_snapshot jsonb,
    ADD COLUMN participant_totals jsonb,
    ADD COLUMN game_ended_in_early_surrender boolean,
    ADD COLUMN game_ended_in_surrender boolean,
    ADD COLUMN team_early_surrendered boolean,
    ADD COLUMN detail_extension_version text,
    ADD CONSTRAINT rune_snapshot_object CHECK (rune_snapshot IS NULL OR jsonb_typeof(rune_snapshot) = 'object'),
    ADD CONSTRAINT participant_totals_object CHECK (participant_totals IS NULL OR jsonb_typeof(participant_totals) = 'object');
