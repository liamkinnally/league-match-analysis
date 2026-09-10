ALTER TABLE league_analysis.evidence_coverage
    ADD CONSTRAINT evidence_coverage_source_provenance_check
    CHECK (source_capture_id IS NOT NULL OR status IN ('UNKNOWN', 'UNAVAILABLE'));
