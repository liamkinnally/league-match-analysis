-- Standard ARAM is an additional concrete queue. Zero remains a HISTORY-only
-- lookup sentinel; queue/map compatibility is checked before materialization.
ALTER TABLE league_analysis.ingestion_run
    DROP CONSTRAINT ingestion_run_queue_id_check,
    ADD CONSTRAINT ingestion_run_queue_id_check CHECK (
        queue_id IN (400, 420, 430, 440, 450, 480, 490)
        OR (queue_id = 0 AND lookup_kind = 'HISTORY')
    );
