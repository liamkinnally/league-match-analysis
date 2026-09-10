package dev.leagueanalysis.analysis.death.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.leagueanalysis.analysis.death.application.CoarseMapProjector;
import dev.leagueanalysis.analysis.death.application.DeathContextRequest;
import dev.leagueanalysis.analysis.death.application.DeathContextService;
import dev.leagueanalysis.analysis.death.application.HistoricalDeathQuery;
import dev.leagueanalysis.analysis.death.application.ParticipantStateProjector;
import dev.leagueanalysis.analysis.death.domain.DeathEvent;
import dev.leagueanalysis.analysis.death.domain.ObjectiveEvent;
import dev.leagueanalysis.analysis.death.domain.TemporalRelation;
import dev.leagueanalysis.evidence.application.InventoryProjector;
import dev.leagueanalysis.evidence.domain.CoverageStatus;
import dev.leagueanalysis.evidence.domain.ItemTransition;
import dev.leagueanalysis.evidence.domain.SourceCoverage;
import dev.leagueanalysis.evidence.domain.TimelineEventKey;
import dev.leagueanalysis.support.PostgresTestConfiguration;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class JdbcHistoricalDeathQueryIntegrationTest {
    private static final String MATCH_ID = "NA1_4242424242";
    private static final UUID RUN_ID = uuid(1);
    private static final UUID DETAIL_PAYLOAD_ID = uuid(2);
    private static final UUID TIMELINE_PAYLOAD_ID = uuid(3);
    private static final UUID DETAIL_CAPTURE_ID = uuid(4);
    private static final UUID TIMELINE_CAPTURE_ID = uuid(5);
    private static final UUID ALTERNATE_TIMELINE_CAPTURE_ID = uuid(6);
    private static final UUID SECOND_ALTERNATE_TIMELINE_CAPTURE_ID = uuid(7);

    @Autowired
    HistoricalDeathQuery query;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seedNormalizedEvidence() {
        clearDatabase();
        seedSourcesAndMatch();
        seedRosterAndEndItems();
        seedObservations();
        seedEventsOutOfOrder();
        seedCoverage();
    }

    @AfterEach
    void leaveDatabaseClean() {
        clearDatabase();
    }

    @Test
    void returnsRevisionAndNormalizedRosterWithoutDerivingMembershipFromObservations() {
        assertThat(query.findRevision(MATCH_ID)).hasValueSatisfying(revision -> {
            assertThat(revision.matchId()).isEqualTo(MATCH_ID);
            assertThat(revision.mapId()).isEqualTo(11);
            assertThat(revision.detailCaptureId()).isEqualTo(DETAIL_CAPTURE_ID);
            assertThat(revision.timelineCaptureId()).isEqualTo(TIMELINE_CAPTURE_ID);
            assertThat(revision.materializationVersion()).isEqualTo("materializer-v2");
        });
        assertThat(query.findParticipantIds(MATCH_ID)).containsExactly(1, 2, 3);
        assertThat(query.findRevision("NA1_missing")).isEmpty();
    }

    @Test
    void ordersDeathsByLogicalEventOrderAndKeepsProvenanceSeparate() {
        var deaths = query.findDeaths(MATCH_ID);

        assertThat(deaths).extracting(death -> death.key().providerEventType())
                .containsExactly("CHAMPION_KILL_EARLY_FRAME", "CHAMPION_KILL_LATE_FRAME", "CHAMPION_KILL_LATER");
        assertThat(deaths.getFirst().key())
                .isEqualTo(new TimelineEventKey(
                        MATCH_ID, 1_000, 1_000, 2, "CHAMPION_KILL_EARLY_FRAME"));
        assertThat(deaths.getFirst().evidence().sourceCaptureId()).isEqualTo(TIMELINE_CAPTURE_ID);
        assertThat(deaths.getFirst().evidence().sourceRecordId()).isEqualTo(uuid(102));
        assertThat(deaths.getFirst().key().toString()).doesNotContain(TIMELINE_CAPTURE_ID.toString());
        assertThat(deaths.getFirst().assistingParticipantIds()).containsExactly(2, 3);
        assertThat(deaths.getFirst().killerParticipantId()).isZero();
    }

    @Test
    void reconcilesIdenticalDeathCapturesOnceAndRetainsEveryReference() {
        insertEvent(121, 1_000, 1_000, 2, "CHAMPION_KILL_EARLY_FRAME", "CHAMPION_KILL",
                0, 1, 100, 200, "{\"assistingParticipantIds\":[2,3]}", SECOND_ALTERNATE_TIMELINE_CAPTURE_ID);

        var deaths = query.findDeaths(MATCH_ID);
        var selected = query.findDeath(MATCH_ID, 1_000, 2).orElseThrow();

        assertThat(deaths).hasSize(3);
        assertThat(deaths.getFirst()).isEqualTo(selected);
        assertThat(selected.victimParticipantId()).isEqualTo(1);
        assertThat(selected.evidenceReferences())
                .extracting(reference -> reference.sourceRecordId())
                .containsExactly(uuid(102), uuid(121));
        assertThat(selected.limitationCodes()).isEmpty();
    }

    @Test
    void reconcilesConflictingDeathCapturesAsAnExplicitPartialWithoutChoosingFields() {
        insertEvent(122, 1_000, 1_000, 2, "CHAMPION_KILL_EARLY_FRAME", "CHAMPION_KILL",
                0, 2, 100, 200, "{\"assistingParticipantIds\":[2,3]}", SECOND_ALTERNATE_TIMELINE_CAPTURE_ID);

        var selected = query.findDeath(MATCH_ID, 1_000, 2).orElseThrow();

        assertThat(selected.victimParticipantId()).isNull();
        assertThat(selected.killerParticipantId()).isNull();
        assertThat(selected.assistingParticipantIds()).isEmpty();
        assertThat(selected.assistingParticipantIdsObserved()).isFalse();
        assertThat(selected.positionX()).isNull();
        assertThat(selected.positionY()).isNull();
        assertThat(selected.evidenceReferences())
                .extracting(reference -> reference.sourceRecordId())
                .containsExactly(uuid(102), uuid(122));
        assertThat(selected.limitationCodes()).containsExactlyInAnyOrder(
                "AMBIGUOUS_DEATH_EVENT", "VICTIM_PARTICIPANT_ID_UNAVAILABLE");
        assertThat(query.findDeaths(MATCH_ID)).hasSize(3);
    }

    @Test
    void preservesUnavailableVictimWithoutAbortingUnrelatedValidDeaths() {
        insertEvent(125, 2_900, 3_400, 0, "CHAMPION_KILL_MISSING_VICTIM", "CHAMPION_KILL",
                1, null, null, null, "{\"assistingParticipantIds\":[]}", TIMELINE_CAPTURE_ID);

        var deaths = query.findDeaths(MATCH_ID);
        var unavailable = query.findDeath(MATCH_ID, 3_400, 0).orElseThrow();

        assertThat(deaths).hasSize(4);
        assertThat(deaths).anySatisfy(death -> assertThat(death.victimParticipantId()).isEqualTo(1));
        assertThat(unavailable.victimParticipantId()).isNull();
        assertThat(unavailable.limitationCodes()).containsExactly("VICTIM_PARTICIPANT_ID_UNAVAILABLE");
        assertThat(unavailable.evidenceReferences())
                .extracting(reference -> reference.sourceRecordId())
                .containsExactly(uuid(125));
    }

    @Test
    void loadsOnlyTheExactChampionKillAtFrameAndIndex() {
        assertThat(query.findDeath(MATCH_ID, 1_000, 2)).hasValueSatisfying(death -> {
            assertThat(death.key().providerEventType()).isEqualTo("CHAMPION_KILL_EARLY_FRAME");
            assertThat(death.victimParticipantId()).isEqualTo(1);
        });
        assertThat(query.findDeath(MATCH_ID, 1_000, 99)).isEmpty();
    }

    @Test
    void distinguishesMissingKillerFromObservedProviderZero() {
        insertEvent(114, 2_600, 3_100, 0, "CHAMPION_KILL_MISSING_KILLER", "CHAMPION_KILL",
                null, 2, null, null, "{\"assistingParticipantIds\":[]}", TIMELINE_CAPTURE_ID);
        insertEvent(115, 2_601, 3_100, 1, "CHAMPION_KILL_ZERO_KILLER", "CHAMPION_KILL",
                0, 2, null, null, "{\"assistingParticipantIds\":[]}", TIMELINE_CAPTURE_ID);

        var missing = query.findDeath(MATCH_ID, 3_100, 0).orElseThrow();
        var observedZero = query.findDeath(MATCH_ID, 3_100, 1).orElseThrow();

        assertThat((Object) missing.killerParticipantId()).isNull();
        assertThat(observedZero.killerParticipantId()).isZero();
    }

    @Test
    void distinguishesUnavailableAssisterEvidenceFromObservedEmptyAssisters() {
        insertEvent(116, 2_700, 3_200, 0, "CHAMPION_KILL_MISSING_ASSISTERS", "CHAMPION_KILL",
                1, 2, null, null, "{}", TIMELINE_CAPTURE_ID);
        insertEvent(117, 2_701, 3_200, 1, "CHAMPION_KILL_MALFORMED_ASSISTERS", "CHAMPION_KILL",
                1, 2, null, null, "{\"assistingParticipantIds\":\"invalid\"}", TIMELINE_CAPTURE_ID);
        insertEvent(118, 2_702, 3_200, 2, "CHAMPION_KILL_MIXED_ASSISTERS", "CHAMPION_KILL",
                1, 2, null, null, "{\"assistingParticipantIds\":[3,\"invalid\"]}", TIMELINE_CAPTURE_ID);
        insertEvent(119, 2_703, 3_200, 3, "CHAMPION_KILL_EMPTY_ASSISTERS", "CHAMPION_KILL",
                1, 2, null, null, "{\"assistingParticipantIds\":[]}", TIMELINE_CAPTURE_ID);

        var missing = query.findDeath(MATCH_ID, 3_200, 0).orElseThrow();
        var malformed = query.findDeath(MATCH_ID, 3_200, 1).orElseThrow();
        var mixedMalformed = query.findDeath(MATCH_ID, 3_200, 2).orElseThrow();
        var observedEmpty = query.findDeath(MATCH_ID, 3_200, 3).orElseThrow();

        assertThat(missing.assistingParticipantIdsObserved()).isFalse();
        assertThat(missing.assistingParticipantIds()).isEmpty();
        assertThat(malformed.assistingParticipantIdsObserved()).isFalse();
        assertThat(malformed.assistingParticipantIds()).isEmpty();
        assertThat(mixedMalformed.assistingParticipantIdsObserved()).isFalse();
        assertThat(mixedMalformed.assistingParticipantIds()).isEmpty();
        assertThat(observedEmpty.assistingParticipantIdsObserved()).isTrue();
        assertThat(observedEmpty.assistingParticipantIds()).isEmpty();
    }

    @Test
    void treatsOutOfRangeAssisterIdsAsUnavailableEvidence() {
        insertEvent(120, 2_800, 3_300, 0, "CHAMPION_KILL_OVERFLOW_ASSISTER", "CHAMPION_KILL",
                1, 2, null, null, "{\"assistingParticipantIds\":[4294967297]}", TIMELINE_CAPTURE_ID);

        var death = query.findDeath(MATCH_ID, 3_300, 0).orElseThrow();

        assertThat(death.assistingParticipantIdsObserved()).isFalse();
        assertThat(death.assistingParticipantIds()).isEmpty();
    }

    @Test
    void returnsEveryReportAtEachParticipantsLatestStrictlyPriorTimestampWithoutAgeFiltering() {
        var observations = query.findLatestPriorObservations(MATCH_ID, 1_000);

        assertThat(observations).extracting(observation -> observation.participantId())
                .containsExactly(1, 1, 2, 3);
        assertThat(observations.get(0).evidence().representedAtMs()).isEqualTo(999);
        assertThat(observations.get(0).evidence().sourceRecordId()).isEqualTo(uuid(200));
        assertThat(observations.get(0).totalGold()).isEqualTo(1_998);
        assertThat(observations.get(1).evidence().representedAtMs()).isEqualTo(999);
        assertThat(observations.get(1).evidence().sourceRecordId()).isEqualTo(uuid(202));
        assertThat(observations.get(1).totalGold()).isEqualTo(1_999);
        assertThat(observations.get(2).evidence().representedAtMs()).isEqualTo(900);
        assertThat(observations.get(3).evidence().representedAtMs()).isEqualTo(899);
        assertThat(observations).allSatisfy(observation ->
                assertThat(observation.evidence().representedAtMs()).isLessThan(1_000));
    }

    @Test
    void appliesExclusiveTotalOrderBoundaryToItemTransitions() {
        var upperBound = new TimelineEventKey(MATCH_ID, 2_000, 2_000, 4, "ITEM_PURCHASED");

        assertThat(query.findItemTransitionsBefore(MATCH_ID, upperBound))
                .extracting(ItemTransition::itemId)
                .containsExactly(1001, 1002, 10_025);
        assertThat(query.findAllItemTransitions(MATCH_ID))
                .extracting(ItemTransition::itemId)
                .containsExactly(1001, 1002, 10_025, 1003, 1004);
    }

    @Test
    void preservesBothObservedInventoryIdsForItemUndo() {
        insertEvent(113, 2_500, 3_000, 9, "ITEM_UNDO", "ITEM",
                1, null, null, null, "{\"beforeId\":1003,\"afterId\":1002}", TIMELINE_CAPTURE_ID);

        var undo = query.findAllItemTransitions(MATCH_ID).stream()
                .filter(transition -> transition.key().providerEventType().equals("ITEM_UNDO"))
                .findFirst()
                .orElseThrow();

        assertThat(undo.itemId()).isNull();
        assertThat(undo.beforeId()).isEqualTo(1003);
        assertThat(undo.afterId()).isEqualTo(1002);
    }

    @Test
    void distinguishesUnavailableItemActorFromObservedZeroWithOriginalProvenance() {
        insertEvent(123, 2_600, 3_100, 0, "ITEM_PURCHASED_ZERO_ACTOR", "ITEM",
                0, null, null, null, "{\"itemId\":2003}", TIMELINE_CAPTURE_ID);
        insertEvent(124, 2_601, 3_100, 1, "ITEM_PURCHASED_MISSING_ACTOR", "ITEM",
                null, null, null, null, "{\"itemId\":3006}", TIMELINE_CAPTURE_ID);

        var observedZero = query.findAllItemTransitions(MATCH_ID).stream()
                .filter(item -> item.key().providerEventType().equals("ITEM_PURCHASED_ZERO_ACTOR"))
                .findFirst()
                .orElseThrow();
        var unavailable = query.findAllItemTransitions(MATCH_ID).stream()
                .filter(item -> item.key().providerEventType().equals("ITEM_PURCHASED_MISSING_ACTOR"))
                .findFirst()
                .orElseThrow();

        assertThat(observedZero.actorParticipantId()).isZero();
        assertThat(unavailable.actorParticipantId()).isNull();
        assertThat(observedZero).isNotEqualTo(unavailable);
        assertThat(observedZero.itemId()).isEqualTo(2003);
        assertThat(unavailable.itemId()).isEqualTo(3006);
        assertThat(observedZero.key()).isEqualTo(new TimelineEventKey(
                MATCH_ID, 2_600, 3_100, 0, "ITEM_PURCHASED_ZERO_ACTOR"));
        assertThat(observedZero.evidence().sourceCaptureId()).isEqualTo(TIMELINE_CAPTURE_ID);
        assertThat(observedZero.evidence().sourceRecordId()).isEqualTo(uuid(123));
        assertThat(unavailable.evidence().sourceRecordId()).isEqualTo(uuid(124));
        assertThat(observedZero.evidence().representedAtMs()).isEqualTo(2_600);
        assertThat(unavailable.evidence().representedAtMs()).isEqualTo(2_601);
        assertThat(observedZero.evidence().methodVersion()).isEqualTo("timeline-v1");
    }

    @Test
    void leavesOutOfRangeItemAndUndoIdsAbsent() {
        insertEvent(121, 2_900, 3_400, 0, "ITEM_UNDO_OVERFLOW", "ITEM",
                1, null, null, null,
                "{\"beforeId\":4294967297,\"afterId\":-4294967295}", TIMELINE_CAPTURE_ID);
        insertEvent(122, 2_901, 3_400, 1, "ITEM_PURCHASED_OVERFLOW", "ITEM",
                1, null, null, null, "{\"itemId\":4294967297}", TIMELINE_CAPTURE_ID);

        var transitions = query.findAllItemTransitions(MATCH_ID);
        var undo = transitions.stream()
                .filter(transition -> transition.key().providerEventType().equals("ITEM_UNDO_OVERFLOW"))
                .findFirst()
                .orElseThrow();
        var purchase = transitions.stream()
                .filter(transition -> transition.key().providerEventType().equals("ITEM_PURCHASED_OVERFLOW"))
                .findFirst()
                .orElseThrow();

        assertThat(undo.beforeId()).isNull();
        assertThat(undo.afterId()).isNull();
        assertThat(purchase.itemId()).isNull();
    }

    @Test
    void returnsSevenSlotEndInventoriesInParticipantOrder() {
        var inventories = query.findObservedEndItems(MATCH_ID);

        assertThat(inventories.keySet()).containsExactly(1, 2, 3);
        assertThat(inventories.get(1)).containsExactly(1056, 2003, 0, 0, 0, 0, 3340);
        assertThat(inventories.get(2)).containsExactly(3006, 3100, 3118, 3157, 3089, 0, 3364);
    }

    @Test
    void makesTheWholeEndInventoryUnavailableWhenAnyItemIdIsOutOfRange() {
        jdbc.update("""
                update league_analysis.riot_participant
                set end_item_ids = cast(? as jsonb)
                where match_id = ? and participant_id = 1
                """, "[4294967297,2003,0,0,0,0,3340]", MATCH_ID);

        assertThat(query.findObservedEndItems(MATCH_ID).get(1)).isEmpty();
    }

    @Test
    void makesMalformedOrNonSevenSlotEndInventoriesUnavailable() {
        var unavailableInventories = List.of(
                "[1056,\"invalid\",0,0,0,0,3340]",
                "[1056,1.5,0,0,0,0,3340]",
                "[1056,null,0,0,0,0,3340]",
                "[1056,2003,0,0,0,3340]",
                "[1056,2003,0,0,0,0,3340,3364]");

        for (var unavailableInventory : unavailableInventories) {
            jdbc.update("""
                    update league_analysis.riot_participant
                    set end_item_ids = cast(? as jsonb)
                    where match_id = ? and participant_id = 1
                    """, unavailableInventory, MATCH_ID);

            assertThat(query.findObservedEndItems(MATCH_ID).get(1))
                    .as("inventory %s", unavailableInventory)
                    .isEmpty();
        }
    }

    @Test
    void includesObjectiveTimestampBoundsAndOrdersTiesDeterministically() {
        var objectives = query.findObjectives(MATCH_ID, 3_000, 4_000);

        assertThat(objectives).extracting(ObjectiveEvent::objectiveDescriptor)
                .containsExactly(
                        "DRAGON:HEXTECH_DRAGON",
                        "TOWER_BUILDING:MID_LANE:OUTER_TURRET",
                        "BARON_NASHOR");
        assertThat(objectives).extracting(ObjectiveEvent::teamId)
                .containsExactly(100, 200, 100);
    }

    @Test
    void returnsEveryRequestedCoverageSourceAndMethodWithFullProvenance() {
        insertCoverage(
                306, "MATCH_DETAIL", "participant_positions", "UNKNOWN",
                null, null, null, "detail-v1");
        insertCoverage(
                307, "MATCH_TIMELINE", "participant_positions", "OBSERVED",
                500L, 4_500L, TIMELINE_CAPTURE_ID, "timeline-v2");

        var coverage = query.findCoverage(
                MATCH_ID, Set.of("participant_positions", "missing_signal"));

        assertThat(coverage).hasSize(3);
        assertThat(coverage).extracting(SourceCoverage::signal)
                .containsOnly("participant_positions");
        assertThat(coverage).extracting(SourceCoverage::sourceKind)
                .containsExactly("MATCH_DETAIL", "MATCH_TIMELINE", "MATCH_TIMELINE");
        assertThat(coverage).extracting(SourceCoverage::methodVersion)
                .containsExactly("detail-v1", "timeline-v1", "timeline-v2");
        assertThat(coverage).extracting(SourceCoverage::status)
                .containsExactly(CoverageStatus.UNKNOWN, CoverageStatus.LIMITED, CoverageStatus.OBSERVED);
        assertThat(coverage).extracting(SourceCoverage::sourceRecordId)
                .containsExactly(uuid(306), uuid(301), uuid(307));
        assertThat(coverage.getFirst().sourceCaptureId()).isNull();
        assertThat(coverage.get(1).sourceCaptureId()).isEqualTo(TIMELINE_CAPTURE_ID);
        assertThat(coverage.get(2).representedStartMs()).isEqualTo(500);
        assertThat(coverage.get(2).representedEndMs()).isEqualTo(4_500);
    }

    @Test
    void exposesNoProviderJsonAcrossTheHistoricalQueryPort() {
        assertThat(Stream.of(
                        DeathEvent.class,
                        ItemTransition.class,
                        ObjectiveEvent.class)
                .flatMap(type -> Stream.of(type.getRecordComponents()))
                .map(component -> component.getType()))
                .noneMatch(JsonNode.class::isAssignableFrom);
        assertThat(query.findDeaths(MATCH_ID)).allMatch(death -> !death.toString().contains("providerSecret"));
        assertThat(query.findObjectives(MATCH_ID, 0, 5_000))
                .allMatch(objective -> !objective.toString().contains("providerSecret"));
    }

    @Test
    void composesCompleteAndDeliberatelyPartialContextsThroughTheRealJdbcAdapter() {
        jdbc.update(
                "delete from league_analysis.participant_state_observation where id = ?",
                uuid(200));
        for (int participantId = 4; participantId <= 10; participantId++) {
            insertParticipant(participantId, participantId <= 5 ? 100 : 200, "[0,0,0,0,0,0,0]");
            insertObservation(600 + participantId, participantId, 950, 2_000 + participantId);
        }
        jdbc.update("update league_analysis.riot_participant set end_item_ids = '[0,0,0,0,0,0,0]'::jsonb where match_id = ?", MATCH_ID);
        jdbc.update("update league_analysis.riot_participant set end_item_ids = '[1001,2003,0,0,0,0,0]'::jsonb where match_id = ? and participant_id = 1", MATCH_ID);
        jdbc.update("delete from league_analysis.match_event where match_id = ? and (canonical_event_kind = 'ITEM' or id = ?)", MATCH_ID, uuid(111));
        insertEvent(501, 999, 999, 8, "ITEM_PURCHASED", "ITEM",
                1, null, null, null, "{\"itemId\":1001}", TIMELINE_CAPTURE_ID);
        insertEvent(502, 1_000, 1_000, 2, "ITEM_PURCHASED", "ITEM",
                1, null, null, null, "{\"itemId\":2003}", ALTERNATE_TIMELINE_CAPTURE_ID);
        insertEvent(510, 900, 900, 0, "ELITE_MONSTER_KILL_BEFORE", "OBJECTIVE",
                1, null, 0, 0, "{\"killerTeamId\":100,\"monsterType\":\"DRAGON\"}", TIMELINE_CAPTURE_ID);
        insertEvent(511, 1_000, 1_000, 0, "ELITE_MONSTER_KILL_SAME", "OBJECTIVE",
                1, null, 5_000, 5_000, "{\"killerTeamId\":100,\"monsterType\":\"RIFTHERALD\"}", TIMELINE_CAPTURE_ID);
        insertEvent(513, 1_000, 1_000, 0, "ELITE_MONSTER_KILL_SAME", "OBJECTIVE",
                1, null, 5_000, 5_000, "{\"killerTeamId\":100,\"monsterType\":\"RIFTHERALD\"}", ALTERNATE_TIMELINE_CAPTURE_ID);
        insertEvent(512, 1_100, 1_100, 0, "ELITE_MONSTER_KILL_AFTER", "OBJECTIVE",
                1, null, 10_000, 10_000, "{\"killerTeamId\":100,\"monsterType\":\"BARON_NASHOR\"}", TIMELINE_CAPTURE_ID);
        jdbc.update("delete from league_analysis.evidence_coverage where match_id = ?", MATCH_ID);
        insertCoverage(701, "match_roster_result_patch", "OBSERVED");
        insertCoverage(702, "participant_positions", "OBSERVED");
        insertCoverage(703, "economy_snapshots", "OBSERVED");
        insertCoverage(704, "item_transitions", "OBSERVED");
        insertCoverage(705, "match_events", "OBSERVED");

        var service = new DeathContextService(
                query,
                new ParticipantStateProjector(new CoarseMapProjector()),
                new InventoryProjector());
        var request = new DeathContextRequest(MATCH_ID, 1_000, 2, 200, 100, 100);

        var complete = service.analyze(request).orElseThrow();

        assertThat(complete.participantStates()).hasSize(10)
                .allSatisfy(state -> assertThat(state.coverageStatus()).isEqualTo(CoverageStatus.OBSERVED));
        assertThat(complete.participantStates().get(3).coarseMapRegion().name()).isEqualTo("SOUTH_WEST");
        assertThat(complete.inventories().getFirst().itemQuantities())
                .containsExactlyEntriesOf(java.util.Map.of(1001, 1));
        assertThat(complete.inventories()).allSatisfy(inventory -> {
            assertThat(inventory.coverageStatus()).isEqualTo(CoverageStatus.RECONSTRUCTED);
            assertThat(inventory.limitationCodes()).isEmpty();
        });
        assertThat(complete.objectiveRelationships())
                .extracting(relationship -> relationship.objective().objectiveDescriptor())
                .containsExactly("DRAGON", "RIFTHERALD", "BARON_NASHOR");
        assertThat(complete.objectiveRelationships())
                .extracting(relationship -> relationship.temporalRelation())
                .containsExactly(TemporalRelation.BEFORE, TemporalRelation.SAME_TIME, TemporalRelation.AFTER);
        assertThat(complete.objectiveRelationships())
                .extracting(relationship -> relationship.signedDeltaMs())
                .containsExactly(-100L, 0L, 100L);
        assertThat(complete.inputCoverage()).extracting(SourceCoverage::status)
                .containsOnly(CoverageStatus.OBSERVED);
        assertThat(complete.ruleVersions()).contains(
                "latest-strictly-prior-participant-observation-v1",
                "bounded-objective-relationship-v1");
        assertThat(complete.limitationCodes()).isEmpty();
        assertThat(complete.evidenceReferences())
                .extracting(reference -> reference.sourceRecordId())
                .contains(uuid(102), uuid(501), uuid(502), uuid(510), uuid(511), uuid(512), uuid(513));

        jdbc.update("delete from league_analysis.participant_state_observation where match_id = ? and participant_id = 10", MATCH_ID);
        jdbc.update("delete from league_analysis.evidence_coverage where match_id = ? and signal = 'economy_snapshots'", MATCH_ID);
        jdbc.update("update league_analysis.riot_participant set end_item_ids = '[3006,0,0,0,0,0,0]'::jsonb where match_id = ? and participant_id = 2", MATCH_ID);

        var partial = service.analyze(request).orElseThrow();

        assertThat(partial.participantStates().get(9).coverageStatus()).isEqualTo(CoverageStatus.LIMITED);
        assertThat(partial.participantStates().get(9).limitationCodes())
                .containsExactly("NO_PRIOR_OBSERVATION");
        assertThat(partial.inventories().get(1).itemQuantities()).isEmpty();
        assertThat(partial.inventories().get(1).limitationCodes())
                .containsExactly("END_INVENTORY_MISMATCH");
        assertThat(partial.inputCoverage())
                .noneMatch(coverage -> coverage.signal().equals("economy_snapshots"));
        assertThat(partial.limitationCodes()).contains(
                "END_INVENTORY_MISMATCH",
                "INPUT_COVERAGE_MISSING_ECONOMY_SNAPSHOTS",
                "NO_PRIOR_OBSERVATION");
    }

    @Test
    void analyzeUsesOneRepeatableReadSnapshotAcrossACommittedRematerialization() {
        jdbc.update(
                "delete from league_analysis.participant_state_observation where id = ?",
                uuid(200));
        var rematerialized = new AtomicBoolean();
        var interleavingQuery = interleavingAfterRevision(() -> {
            assertThat(jdbc.queryForObject("show transaction_isolation", String.class))
                    .isEqualTo("repeatable read");
            assertThat(jdbc.queryForObject("show transaction_read_only", String.class))
                    .isEqualTo("on");
        }, () -> {
            var updated = jdbc.update("""
                    with revised_match as (
                        update league_analysis.riot_match
                        set materialization_version = 'materializer-v3'
                        where match_id = ?
                        returning match_id
                    )
                    update league_analysis.participant_state_observation
                    set total_gold = 7777
                    where match_id = ? and id = ?
                      and exists (select 1 from revised_match)
                    """, MATCH_ID, MATCH_ID, uuid(202));
            assertThat(updated).isEqualTo(1);
            rematerialized.set(true);
        });
        var service = new DeathContextService(
                interleavingQuery,
                new ParticipantStateProjector(new CoarseMapProjector()),
                new InventoryProjector());

        var result = service.analyze(
                        new DeathContextRequest(MATCH_ID, 1_000, 2, 200, 100, 100))
                .orElseThrow();

        assertThat(rematerialized).isTrue();
        assertThat(result.sourceRevision().materializationVersion()).isEqualTo("materializer-v2");
        assertThat(result.participantStates()).filteredOn(state -> state.participantId() == 1)
                .singleElement()
                .extracting(state -> state.totalGold())
                .isEqualTo(1_999);
        assertThat(query.findRevision(MATCH_ID).orElseThrow().materializationVersion())
                .isEqualTo("materializer-v3");
        assertThat(query.findLatestPriorObservations(MATCH_ID, 1_000))
                .filteredOn(observation -> observation.participantId() == 1)
                .singleElement()
                .extracting(observation -> observation.totalGold())
                .isEqualTo(7_777);
    }

    private void clearDatabase() {
        jdbc.execute("truncate table league_analysis.ingestion_run, league_analysis.source_payload cascade");
    }

    private HistoricalDeathQuery interleavingAfterRevision(
            Runnable snapshotAssertion, Runnable committedWrite) {
        var invoked = new AtomicBoolean();
        return (HistoricalDeathQuery) Proxy.newProxyInstance(
                HistoricalDeathQuery.class.getClassLoader(),
                new Class<?>[] {HistoricalDeathQuery.class},
                (proxy, method, arguments) -> {
                    try {
                        var result = method.invoke(query, arguments);
                        if (method.getName().equals("findRevision") && invoked.compareAndSet(false, true)) {
                            snapshotAssertion.run();
                            CompletableFuture.runAsync(committedWrite).join();
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private void seedSourcesAndMatch() {
        jdbc.update("""
                insert into league_analysis.ingestion_run
                    (id, requested_game_name, requested_tag_line, platform_route, regional_route,
                     queue_id, match_limit, status, started_at)
                values (?, 'Invented', 'NA1', 'NA1', 'AMERICAS', 420, 1, 'COMPLETE', ?)
                """, RUN_ID, timestamp());
        insertPayload(DETAIL_PAYLOAD_ID, "MATCH_DETAIL", "a");
        insertPayload(TIMELINE_PAYLOAD_ID, "MATCH_TIMELINE", "b");
        insertCapture(DETAIL_CAPTURE_ID, DETAIL_PAYLOAD_ID, "MATCH_DETAIL", 1);
        insertCapture(TIMELINE_CAPTURE_ID, TIMELINE_PAYLOAD_ID, "MATCH_TIMELINE", 1);
        insertCapture(ALTERNATE_TIMELINE_CAPTURE_ID, TIMELINE_PAYLOAD_ID, "MATCH_TIMELINE", 2);
        insertCapture(SECOND_ALTERNATE_TIMELINE_CAPTURE_ID, TIMELINE_PAYLOAD_ID, "MATCH_TIMELINE", 3);
        jdbc.update("""
                insert into league_analysis.riot_match
                    (match_id, game_id, queue_id, map_id, game_mode, game_type, game_version,
                     data_version, game_creation_ms, game_start_ms, game_end_ms,
                     game_duration_seconds, detail_source_capture_id, timeline_source_capture_id,
                     materialization_version, materialized_at)
                values (?, 4242424242, 420, 11, 'CLASSIC', 'MATCHED_GAME', '16.17',
                        '2', 1, 1, 5000, 5, ?, ?, 'materializer-v2', ?)
                """, MATCH_ID, DETAIL_CAPTURE_ID, TIMELINE_CAPTURE_ID, timestamp());
        jdbc.update("""
                insert into league_analysis.riot_team (match_id, team_id, win, objectives)
                values (?, 100, true, '{}'::jsonb), (?, 200, false, '{}'::jsonb)
                """, MATCH_ID, MATCH_ID);
    }

    private void seedRosterAndEndItems() {
        insertParticipant(3, 100, "[1036,0,0,0,0,0,3340]");
        insertParticipant(1, 100, "[1056,2003,0,0,0,0,3340]");
        insertParticipant(2, 200, "[3006,3100,3118,3157,3089,0,3364]");
    }

    private void seedObservations() {
        insertObservation(201, 1, 1_000, 2_000);
        insertObservation(202, 1, 999, 1_999);
        insertObservation(200, 1, 999, 1_998, ALTERNATE_TIMELINE_CAPTURE_ID);
        insertObservation(203, 1, 900, 1_900);
        insertObservation(204, 1, 1_001, 2_001);
        insertObservation(205, 2, 900, 2_900);
        insertObservation(206, 3, 899, 3_899);
    }

    private void seedEventsOutOfOrder() {
        insertEvent(110, 2_000, 3_000, 0, "CHAMPION_KILL_LATER", "CHAMPION_KILL",
                2, 1, 400, 500, "{\"assistingParticipantIds\":[3],\"providerSecret\":\"hidden\"}", TIMELINE_CAPTURE_ID);
        insertEvent(104, 2_000, 2_000, 4, "ITEM_PURCHASED", "ITEM",
                1, null, null, null, "{\"itemId\":1003}", TIMELINE_CAPTURE_ID);
        insertEvent(102, 1_000, 1_000, 2, "CHAMPION_KILL_EARLY_FRAME", "CHAMPION_KILL",
                0, 1, 100, 200, "{\"assistingParticipantIds\":[2,3],\"providerSecret\":\"hidden\"}", TIMELINE_CAPTURE_ID);
        insertEvent(107, 3_000, 3_000, 2, "BUILDING_KILL", "OBJECTIVE",
                2, null, 300, 400, "{\"teamId\":200,\"buildingType\":\"TOWER_BUILDING\",\"laneType\":\"MID_LANE\",\"towerType\":\"OUTER_TURRET\",\"providerSecret\":\"hidden\"}", TIMELINE_CAPTURE_ID);
        insertEvent(101, 2_000, 2_000, 2, "ITEM_PURCHASED", "ITEM",
                1, null, null, null, "{\"itemId\":1002}", TIMELINE_CAPTURE_ID);
        insertEvent(106, 4_000, 4_000, 0, "ELITE_MONSTER_KILL", "OBJECTIVE",
                1, null, 500, 600, "{\"killerTeamId\":100,\"monsterType\":\"BARON_NASHOR\"}", TIMELINE_CAPTURE_ID);
        insertEvent(103, 1_999, 9_999, 99, "ITEM_PURCHASED", "ITEM",
                1, null, null, null, "{\"itemId\":1001}", TIMELINE_CAPTURE_ID);
        insertEvent(108, 3_000, 3_000, 1, "ELITE_MONSTER_KILL", "OBJECTIVE",
                1, null, 200, 300, "{\"killerTeamId\":100,\"monsterType\":\"DRAGON\",\"monsterSubType\":\"HEXTECH_DRAGON\"}", TIMELINE_CAPTURE_ID);
        insertEvent(109, 1_000, 2_000, 5, "CHAMPION_KILL_LATE_FRAME", "CHAMPION_KILL",
                2, 3, null, null, "{\"assistingParticipantIds\":[]}", TIMELINE_CAPTURE_ID);
        insertEvent(105, 2_001, 1_000, 0, "ITEM_DESTROYED", "ITEM",
                1, null, null, null, "{\"itemId\":1004}", TIMELINE_CAPTURE_ID);
        insertEvent(112, 2_000, 2_000, 4, "ITEM_DESTROYED", "ITEM",
                1, null, null, null, "{\"itemId\":10025}", ALTERNATE_TIMELINE_CAPTURE_ID);
        insertEvent(111, 1_000, 1_000, 2, "NON_KILL_AT_SAME_FRAME_INDEX", "OTHER",
                2, null, null, null, "{\"itemId\":9999}", ALTERNATE_TIMELINE_CAPTURE_ID);
    }

    private void seedCoverage() {
        insertCoverage(301, "participant_positions", "LIMITED");
        insertCoverage(302, "item_events", "OBSERVED");
        insertCoverage(303, "objective_events", "ESTIMATED");
    }

    private void insertPayload(UUID id, String kind, String hashCharacter) {
        jdbc.update("""
                insert into league_analysis.source_payload
                    (id, source_kind, body_sha256, body_size_bytes, payload_json)
                values (?, ?, ?, 2, '{}'::jsonb)
                """, id, kind, hashCharacter.repeat(64));
    }

    private void insertCapture(UUID id, UUID payloadId, String kind, int attempt) {
        jdbc.update("""
                insert into league_analysis.source_capture
                    (id, ingestion_run_id, source_payload_id, source_kind, resource_key,
                     regional_route, platform_route, captured_at, http_status,
                     response_metadata, parser_version, attempt)
                values (?, ?, ?, ?, ?, 'AMERICAS', 'NA1', ?, 200, '{}'::jsonb, 'parser-v1', ?)
                """, id, RUN_ID, payloadId, kind, MATCH_ID, timestamp(), attempt);
    }

    private void insertParticipant(int participantId, int teamId, String endItems) {
        var puuid = "invented-puuid-" + participantId;
        jdbc.update("""
                insert into league_analysis.riot_identity
                    (puuid, first_observed_at, last_observed_at, last_source_capture_id)
                values (?, ?, ?, ?)
                """, puuid, timestamp(), timestamp(), DETAIL_CAPTURE_ID);
        jdbc.update("""
                insert into league_analysis.riot_participant
                    (match_id, participant_id, puuid, team_id, champion_id, champion_name,
                     team_position, kills, deaths, assists, total_minions_killed,
                     neutral_minions_killed, gold_earned, gold_spent, vision_score,
                     summoner_spell_one_id, summoner_spell_two_id, win, end_item_ids)
                values (?, ?, ?, ?, ?, ?, 'MIDDLE', 0, 0, 0, 0, 0, 0, 0, 0, 4, 14, false,
                        cast(? as jsonb))
                """, MATCH_ID, participantId, puuid, teamId, 100 + participantId,
                "Champion" + participantId, endItems);
    }

    private void insertObservation(long id, int participantId, long representedAtMs, int totalGold) {
        insertObservation(id, participantId, representedAtMs, totalGold, TIMELINE_CAPTURE_ID);
    }

    private void insertObservation(
            long id,
            int participantId,
            long representedAtMs,
            int totalGold,
            UUID sourceCaptureId) {
        jdbc.update("""
                insert into league_analysis.participant_state_observation
                    (id, match_id, participant_id, represented_at_ms, x, y, current_gold,
                     total_gold, level, xp, minions_killed, jungle_minions_killed,
                     source_capture_id, method_version)
                values (?, ?, ?, ?, 10, 20, 100, ?, 5, 1000, 20, 2, ?, 'timeline-v1')
                """, uuid(id), MATCH_ID, participantId, representedAtMs, totalGold, sourceCaptureId);
    }

    private void insertEvent(
            long id,
            long representedAtMs,
            long frameAtMs,
            int frameEventIndex,
            String providerEventType,
            String canonicalEventKind,
            Integer actorParticipantId,
            Integer targetParticipantId,
            Integer x,
            Integer y,
            String payload,
            UUID sourceCaptureId) {
        jdbc.update("""
                insert into league_analysis.match_event
                    (id, match_id, represented_at_ms, frame_at_ms, frame_event_index,
                     provider_event_type, canonical_event_kind, actor_participant_id,
                     target_participant_id, position_x, position_y, event_payload,
                     source_capture_id, method_version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, 'timeline-v1')
                """, uuid(id), MATCH_ID, representedAtMs, frameAtMs, frameEventIndex,
                providerEventType, canonicalEventKind, actorParticipantId,
                targetParticipantId, x, y, payload, sourceCaptureId);
    }

    private void insertCoverage(long id, String signal, String status) {
        insertCoverage(
                id,
                "MATCH_TIMELINE",
                signal,
                status,
                0L,
                5_000L,
                TIMELINE_CAPTURE_ID,
                "timeline-v1");
    }

    private void insertCoverage(
            long id,
            String sourceKind,
            String signal,
            String status,
            Long representedStartMs,
            Long representedEndMs,
            UUID sourceCaptureId,
            String methodVersion) {
        jdbc.update("""
                insert into league_analysis.evidence_coverage
                    (id, match_id, source_kind, signal, status, represented_start_ms,
                     represented_end_ms, details, source_capture_id, method_version)
                values (?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, ?, ?)
                """, uuid(id), MATCH_ID, sourceKind, signal, status, representedStartMs,
                representedEndMs, sourceCaptureId, methodVersion);
    }

    private static OffsetDateTime timestamp() {
        return OffsetDateTime.of(2026, 9, 3, 12, 0, 0, 0, ZoneOffset.UTC);
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
