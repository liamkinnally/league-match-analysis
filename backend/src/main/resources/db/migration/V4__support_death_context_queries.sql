ALTER TABLE league_analysis.riot_participant
    ADD COLUMN end_item_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT riot_participant_end_item_ids_array_check
        CHECK (jsonb_typeof(end_item_ids) = 'array');

CREATE INDEX participant_state_subject_time_idx
    ON league_analysis.participant_state_observation
        (match_id, participant_id, represented_at_ms DESC);

CREATE INDEX match_event_kind_order_idx
    ON league_analysis.match_event
        (match_id, canonical_event_kind, represented_at_ms, frame_at_ms, frame_event_index);
