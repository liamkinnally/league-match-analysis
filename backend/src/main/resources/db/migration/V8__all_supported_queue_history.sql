-- Zero describes an unfiltered history lookup; individual matches and optional
-- timeline lookups retain their actual concrete queue.
ALTER TABLE league_analysis.ingestion_run
    DROP CONSTRAINT ingestion_run_queue_id_check,
    ADD CONSTRAINT ingestion_run_queue_id_check CHECK (
        queue_id IN (400, 420, 430, 440, 480, 490)
        OR (queue_id = 0 AND lookup_kind = 'HISTORY')
    );
