package dev.leagueanalysis.ingestion.riot.application;

import dev.leagueanalysis.ingestion.riot.adapter.out.persistence.JdbcRiotIngestionStore;
import dev.leagueanalysis.ingestion.riot.adapter.out.riot.MatchV5Decoder;
import dev.leagueanalysis.ingestion.riot.domain.ProviderDocument;
import dev.leagueanalysis.ingestion.riot.domain.RiotAccount;
import dev.leagueanalysis.ingestion.riot.domain.RiotId;
import dev.leagueanalysis.ingestion.riot.domain.SourceKind;
import dev.leagueanalysis.privacy.PrivacyHash;
import dev.leagueanalysis.support.PostgresTestConfiguration;
import dev.leagueanalysis.support.PublicLookupGatewayFixture;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** All identities and provider documents here are invented and run in Testcontainers. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PrivacyAwareIngestionIntegrationTest {
    private static final String SHARED = PublicLookupGatewayFixture.MATCH_ID;
    private static final String UNRELATED = "NA1_7000000003";
    private static final String EXCLUDED_PUUID = "lookup-invented-participant-1";
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC);
    private final Queue<Runnable> work = new ArrayDeque<>();
    @Autowired JdbcRiotIngestionStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private PublicLookupGatewayFixture gateway;
    private PublicMatchLookupService service;
    private String accountPuuid;

    @BeforeEach void setup() {
        clear();
        accountPuuid = PublicLookupGatewayFixture.PUUID;
        gateway = new PublicLookupGatewayFixture(json, clock) {
            @Override public RiotAccountLookup resolveAccount(RiotId id) {
                var original = super.resolveAccount(id);
                if (accountPuuid.equals(PublicLookupGatewayFixture.PUUID)) return original;
                return new RiotAccountLookup(new RiotAccount(accountPuuid, id.gameName(), id.tagLine()),
                        document(SourceKind.ACCOUNT, id.gameName() + "#" + id.tagLine(),
                                "{\"puuid\":\"" + accountPuuid + "\"}"));
            }
            @Override public RiotMatchList listRankedMatchIds(String puuid, int count) {
                calls.add("list");
                return new RiotMatchList(List.of(SHARED, UNRELATED), document(SourceKind.MATCH_LIST, puuid,
                        "[\"" + SHARED + "\",\"" + UNRELATED + "\"]"));
            }
            @Override public ProviderDocument fetchMatchDetail(String matchId) {
                calls.add("detail:" + matchId);
                return withoutExcludedPlayerInUnrelatedMatch(super.fetchMatchDetail(matchId));
            }
            @Override public ProviderDocument fetchMatchTimeline(String matchId) {
                calls.add("timeline:" + matchId);
                return withoutExcludedPlayerInUnrelatedMatch(super.fetchMatchTimeline(matchId));
            }
        };
        service = new PublicMatchLookupService(new RiotIngestionService(gateway, new MatchV5Decoder(), store, clock),
                store, clock, true, work::add);
    }

    @AfterEach void clear() {
        jdbc.execute("truncate table league_analysis.ingestion_run, league_analysis.source_payload cascade");
        jdbc.execute("truncate table league_analysis.privacy_exclusion");
        work.clear();
    }

    @Test void knownAliasIsVerifiedByPuuidBeforeItsOriginalOwnerIsExcluded() {
        exclude("riot_id", PrivacyHash.riotId("Invented", "NA1"));
        exclude("puuid", PrivacyHash.of(PublicLookupGatewayFixture.PUUID));
        UUID id = service.submit("INVENTED", "na1", "peer").lookup().runId();
        work.remove().run();

        assertThatThrownBy(() -> service.get(id)).isInstanceOfSatisfying(PublicLookupException.class, exception -> {
            assertThat(exception.status()).isEqualTo(404);
            assertThat(exception.getMessage()).isEqualTo("Lookup not found. Search again.");
        });
        assertThat(gateway.calls).containsExactly("account");
        assertThat(count("ingestion_run")).isZero();
        assertThat(count("source_capture")).isZero();
    }

    @Test void reusedAliasDoesNotExcludeDifferentVerifiedPuuidOrItsMatches() {
        exclude("riot_id", PrivacyHash.riotId("OtherPlayer", "NA1"));
        exclude("puuid", PrivacyHash.of(PublicLookupGatewayFixture.PUUID));
        accountPuuid = "new-owner-verified-puuid";

        var result = runLookup();

        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.gameName()).isEqualTo("OtherPlayer");
        assertThat(result.matches()).extracting(PublicMatchLookup.MatchSummary::matchId)
                .containsExactly(SHARED, UNRELATED);
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.riot_identity where puuid=?", Integer.class,
                PublicLookupGatewayFixture.PUUID)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.riot_participant where puuid=?", Integer.class,
                accountPuuid)).isEqualTo(2);
    }

    @Test void crashBeforeAccountResolutionCannotRetainSubmittedRenamedIdentity() {
        exclude("puuid", PrivacyHash.of(PublicLookupGatewayFixture.PUUID));
        UUID id = service.submit("NotYetVerifiedNewName", "NA1", "peer").lookup().runId();
        assertThat(service.get(id).gameName()).isEqualTo("NotYetVerifiedNewName");
        assertThat(jdbc.queryForMap("select requested_game_name,requested_tag_line from league_analysis.ingestion_run where id=?", id))
                .containsEntry("requested_game_name", "").containsEntry("requested_tag_line", "");

        var restarted = new PublicMatchLookupService(new RiotIngestionService(gateway, new MatchV5Decoder(), store, clock),
                store, clock, true, work::add);
        restarted.interruptPreviousRuns();
        assertThat(restarted.get(id).status()).isEqualTo("FAILED");
        assertThat(restarted.get(id).gameName()).isEmpty();
        assertThat(restarted.get(id).tagLine()).isEmpty();
        assertThat(gateway.calls).isEmpty();
    }

    @Test void localPendingRunAlsoStoresNoUnverifiedNameOrTag() {
        UUID id = store.startRun(new RiotIngestionCommand("UnverifiedLocal", "NA1", 5), clock.instant());
        assertThat(jdbc.queryForMap("select requested_game_name,requested_tag_line from league_analysis.ingestion_run where id=?", id))
                .containsEntry("requested_game_name", "").containsEntry("requested_tag_line", "");
    }

    @Test void renamedExcludedPuuidDiscardsUnresolvedRunAndNeverCapturesAccountOrAlias() {
        exclude("puuid", PrivacyHash.of(PublicLookupGatewayFixture.PUUID));
        UUID id = service.submit("NewName", "NA1", "peer").lookup().runId();

        work.remove().run();

        assertThatThrownBy(() -> service.get(id)).isInstanceOfSatisfying(PublicLookupException.class,
                exception -> assertThat(exception.status()).isEqualTo(404));
        assertThat(gateway.calls).containsExactly("account");
        assertThat(count("ingestion_run")).isZero();
        assertThat(count("source_capture")).isZero();
        assertThat(count("source_payload")).isZero();
        assertThat(count("riot_identity")).isZero();
    }

    @Test void anotherPlayerCannotReimportExcludedParticipantInUnknownSharedMatch() {
        exclude("puuid", PrivacyHash.of(EXCLUDED_PUUID));
        var result = runLookup();

        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.matches()).extracting(PublicMatchLookup.MatchSummary::matchId).containsExactly(UNRELATED);
        assertThat(store.isExcludedMatch(SHARED)).isTrue();
        assertThat(gateway.calls).doesNotContain("timeline:" + SHARED);
        assertNoSharedRecords();
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.riot_participant where match_id = ?",
                Integer.class, UNRELATED)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.source_capture where source_kind = 'MATCH_LIST'",
                Integer.class)).isZero();
    }

    @Test void knownSharedMatchIsNotFetchedAndOriginalMatchListIsNotFabricatedOrStored() {
        exclude("puuid", PrivacyHash.of(EXCLUDED_PUUID));
        exclude("match", PrivacyHash.of(SHARED));

        var result = runLookup();

        assertThat(result.matches()).extracting(PublicMatchLookup.MatchSummary::matchId).containsExactly(UNRELATED);
        assertThat(gateway.calls).doesNotContain("detail:" + SHARED, "timeline:" + SHARED);
        assertNoSharedRecords();
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.source_capture where source_kind = 'MATCH_LIST'",
                Integer.class)).isZero();
    }

    private PublicMatchLookup runLookup() {
        UUID id = service.submit("OtherPlayer", "NA1", "peer").lookup().runId();
        while (!work.isEmpty()) work.remove().run();
        return service.get(id);
    }

    private void assertNoSharedRecords() {
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.riot_match where match_id = ?", Integer.class, SHARED)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.ingestion_item where match_id = ?", Integer.class, SHARED)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.source_capture where resource_key = ?", Integer.class, SHARED)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from league_analysis.source_payload where payload_json::text like ?",
                Integer.class, "%" + EXCLUDED_PUUID + "\"%")).isZero();
    }

    private ProviderDocument withoutExcludedPlayerInUnrelatedMatch(ProviderDocument source) {
        String body = source.payload().toString();
        if (!accountPuuid.equals(PublicLookupGatewayFixture.PUUID)) {
            body = body.replace("\"" + PublicLookupGatewayFixture.PUUID + "\"", "\"" + accountPuuid + "\"");
        }
        if (source.resourceKey().equals(UNRELATED)) {
            body = body.replace("\"" + EXCLUDED_PUUID + "\"", "\"unrelated-participant-1\"");
        }
        return document(source.kind(), source.resourceKey(), body);
    }

    private ProviderDocument document(SourceKind kind, String key, String body) {
        return new ProviderDocument(kind, key, clock.instant(), 200, "AMERICAS", "NA1", "16.17.1",
                PrivacyHash.of(body), body.getBytes(StandardCharsets.UTF_8).length,
                json.readTree(body), json.createObjectNode(), "test-v1", 1);
    }

    private void exclude(String kind, String hash) {
        jdbc.update("insert into league_analysis.privacy_exclusion(kind, subject_hash) values (?, ?)", kind, hash);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from league_analysis." + table, Integer.class);
    }
}
