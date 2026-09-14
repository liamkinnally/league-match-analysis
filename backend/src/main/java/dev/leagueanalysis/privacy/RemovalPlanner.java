package dev.leagueanalysis.privacy;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Plans against retained evidence, never against unverified free-text guesses or provider calls. */
public final class RemovalPlanner {
    private static final List<String> TABLES = List.of("ingestion_run", "source_payload", "source_capture",
            "ingestion_item", "riot_identity", "riot_match", "riot_team", "riot_participant",
            "participant_state_observation", "match_event", "evidence_coverage",
            "player_profile_current", "rank_refresh_state", "rank_observation");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RemovalPlanner(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public RemovalPlan plan(String puuid) {
        if (puuid == null || puuid.isBlank()) throw new IllegalArgumentException("UNVERIFIED_PUUID");
        var data = load();
        if (!data.verifiedPuuids().contains(puuid)) throw new IllegalArgumentException("UNVERIFIED_PUUID");
        return plan(data, Set.of(PrivacyHash.of(puuid)), Set.of(), Set.of());
    }

    /** Hashes must originate from the verified private removal ledger, not a public request. */
    public RemovalPlan planHashes(Set<String> puuidHashes, Set<String> aliasHashes, Set<String> matchHashes) {
        for (var values : List.of(puuidHashes, aliasHashes, matchHashes))
            for (String value : values) if (value == null || !value.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("INVALID_REMOVAL_HASH");
        return plan(load(), puuidHashes, aliasHashes, matchHashes);
    }

    private RemovalPlan plan(Data data, Set<String> puuidHashes, Set<String> initialAliases, Set<String> initialMatches) {
        var subjects = new HashSet<String>();
        data.verifiedPuuids().stream().filter(p -> puuidHashes.contains(PrivacyHash.of(p))).forEach(subjects::add);
        // Restore can encounter raw-only or malformed older captures. Trusted ledger hashes
        // still identify exact scalar values even when no current normalized identity survives.
        for (var payload : data.payloads.values()) collectHashedStrings(payload.body, puuidHashes, subjects);
        for (var capture : data.captures.values()) if (puuidHashes.contains(PrivacyHash.of(capture.resource)))
            subjects.add(capture.resource);
        var aliases = new TreeSet<>(initialAliases);
        for (var identity : data.identities) if (subjects.contains(identity.puuid)) addAlias(aliases, identity.name, identity.tag);
        for (var run : data.runs) if (subjects.contains(run.puuid)) addAlias(aliases, run.name, run.tag);
        for (var payload : data.payloads.values()) {
            for (var person : people(payload)) if (subjects.contains(person.puuid)) addAlias(aliases, person.name, person.tag);
        }
        for (var capture : data.captures.values()) {
            var payload = data.payloads.get(capture.payload);
            if (capture.kind.equals("ACCOUNT") && subjects.contains(text(payload.body, "puuid"))) {
                int separator = capture.resource.lastIndexOf('#');
                if (separator > 0 && separator < capture.resource.length() - 1)
                    addAlias(aliases, capture.resource.substring(0, separator), capture.resource.substring(separator + 1));
            }
        }

        var matches = new HashSet<String>();
        for (var payload : data.payloads.values()) collectHashedStrings(payload.body, initialMatches, matches);
        for (String match : matches) if (!match.matches("NA1_[0-9]+"))
            throw new IllegalStateException("UNSUPPORTED_CAPTURE_MATCH_IDENTIFIER");
        data.participants.stream().filter(p -> subjects.contains(p.puuid)).map(p -> p.match).forEach(matches::add);
        for (var row : data.matches) if (initialMatches.contains(PrivacyHash.of(row.match))) matches.add(row.match);
        for (var item : data.items) if (initialMatches.contains(PrivacyHash.of(item.match))) matches.add(item.match);
        for (var payload : data.payloads.values()) {
            if (isMatch(payload.kind)) {
                String id = text(payload.body.path("metadata"), "matchId");
                if (id != null && (initialMatches.contains(PrivacyHash.of(id)) || containsSubject(payload.body, subjects)))
                    addMatch(matches, id);
            }
        }
        for (var capture : data.captures.values()) if (isMatch(capture.kind)) {
            var payload = data.payloads.get(capture.payload);
            String recordedMatch = text(payload.body.path("metadata"), "matchId");
            if (containsSubject(payload.body, subjects) && recordedMatch != null && !recordedMatch.equals(capture.resource))
                throw new IllegalStateException("CONFLICTING_CAPTURE_MATCH_IDENTIFIERS");
            if (initialMatches.contains(PrivacyHash.of(capture.resource)) || containsSubject(payload.body, subjects))
                addMatch(matches, capture.resource);
        }
        for (var capture : data.captures.values()) if (capture.kind.equals("MATCH_LIST") && subjects.contains(capture.resource)) {
            var body = data.payloads.get(capture.payload).body;
            if (!body.isArray()) throw new IllegalStateException("UNSUPPORTED_SUBJECT_MATCH_LIST");
            for (var id : body) {
                if (!id.isString()) throw new IllegalStateException("UNSUPPORTED_SUBJECT_MATCH_LIST");
                var represented = data.participants.stream().filter(p -> p.match.equals(id.stringValue())).toList();
                if (!represented.isEmpty() && represented.stream().noneMatch(p -> subjects.contains(p.puuid)))
                    throw new IllegalStateException("CONFLICTING_MATCH_MEMBERSHIP");
                addMatch(matches, id.stringValue());
            }
        }
        var matchHashes = new TreeSet<>(initialMatches);
        matches.stream().map(PrivacyHash::of).forEach(matchHashes::add);

        // A reused name must not make an unresolved request look like verified ownership.
        var aliasOwners = new HashMap<String, Set<String>>();
        for (var identity : data.identities) owner(aliasOwners, identity.puuid, identity.name, identity.tag);
        for (var payload : data.payloads.values()) for (var person : people(payload)) owner(aliasOwners, person.puuid, person.name, person.tag);
        for (var run : data.runs) if (run.puuid != null) owner(aliasOwners, run.puuid, run.name, run.tag);
        var runs = new TreeSet<UUID>();
        for (var run : data.runs) {
            if (subjects.contains(run.puuid)) { runs.add(run.id); continue; }
            if (run.puuid == null && aliases.contains(PrivacyHash.riotId(run.name, run.tag))) {
                var owners = aliasOwners.getOrDefault(PrivacyHash.riotId(run.name, run.tag), Set.of());
                if (owners.stream().anyMatch(p -> !subjects.contains(p)))
                    throw new IllegalStateException("AMBIGUOUS_UNRESOLVED_LOOKUP_ALIAS");
                runs.add(run.id);
            }
        }

        // An interruption can happen after Account-V1 capture but before resolved_puuid
        // is stored. That capture verifies lookup ownership; a shared match capture does not.
        for (var capture : data.captures.values()) if (capture.kind.equals("ACCOUNT")
                && subjects.contains(text(data.payloads.get(capture.payload).body, "puuid"))) {
            var ownerRun = data.runs.stream().filter(r -> r.id.equals(capture.run)).findFirst().orElseThrow();
            if (ownerRun.puuid != null && !subjects.contains(ownerRun.puuid))
                throw new IllegalStateException("CONFLICTING_ACCOUNT_RUN_OWNERSHIP");
            for (var other : data.captures.values()) if (other.run.equals(capture.run) && other.kind.equals("ACCOUNT")) {
                String owner = text(data.payloads.get(other.payload).body, "puuid");
                if (owner != null && !subjects.contains(owner))
                    throw new IllegalStateException("CONFLICTING_ACCOUNT_RUN_OWNERSHIP");
            }
            runs.add(capture.run);
        }

        var captures = new HashSet<UUID>();
        var sensitivePayloads = new TreeSet<UUID>();
        for (var payload : data.payloads.values()) {
            if (containsSubject(payload.body, subjects)
                    || isMatch(payload.kind) && matches.contains(text(payload.body.path("metadata"), "matchId"))
                    || payload.kind.equals("MATCH_LIST") && containsMatch(payload.body, matches)) sensitivePayloads.add(payload.id);
        }
        for (var capture : data.captures.values()) {
            if (sensitivePayloads.contains(capture.payload)
                    || isMatch(capture.kind) && matches.contains(capture.resource)
                    || capture.kind.equals("MATCH_LIST") && subjects.contains(capture.resource)
                    || runs.contains(capture.run) && Set.of("ACCOUNT", "MATCH_LIST").contains(capture.kind))
                captures.add(capture.id);
        }
        // Never remove a subject run's otherwise unrelated capture and its normalized match as collateral.
        for (var capture : data.captures.values()) if (runs.contains(capture.run) && !captures.contains(capture.id))
            throw new IllegalStateException("SUBJECT_RUN_HAS_RETAINED_PROVENANCE");

        var items = new ArrayList<Item>();
        for (var item : data.items) if (matches.contains(item.match) || runs.contains(item.run)
                || captures.contains(item.detail) || captures.contains(item.timeline)) items.add(item);
        for (var match : data.matches) if (!matches.contains(match.match)
                && (captures.contains(match.detail) || captures.contains(match.timeline)))
            throw new IllegalStateException("RETAINED_MATCH_REFERENCES_REMOVED_CAPTURE");

        var identities = new TreeSet<>(subjects);
        var reanchors = new ArrayList<Reanchor>();
        for (var identity : data.identities) if (!identities.contains(identity.puuid) && captures.contains(identity.capture)) {
            var evidence = new ArrayList<IdentityEvidence>();
            for (var capture : data.captures.values()) if (!captures.contains(capture.id) && Set.of("ACCOUNT", "MATCH_DETAIL").contains(capture.kind)) {
                for (var person : people(data.payloads.get(capture.payload))) if (identity.puuid.equals(person.puuid))
                    evidence.add(new IdentityEvidence(capture, person));
            }
            evidence.sort(Comparator.comparing((IdentityEvidence e) -> e.capture.at).thenComparing(e -> e.capture.id));
            if (evidence.isEmpty()) {
                boolean retained = data.participants.stream().anyMatch(p -> identity.puuid.equals(p.puuid) && !matches.contains(p.match));
                if (retained) throw new IllegalStateException("RETAINED_IDENTITY_HAS_NO_VERIFIED_PROVENANCE");
                identities.add(identity.puuid);
            } else {
                String name = null, tag = null;
                for (var e : evidence) { if (e.person.name != null) name = e.person.name; if (e.person.tag != null) tag = e.person.tag; }
                reanchors.add(new Reanchor(identity.puuid, evidence.getLast().capture.id, name, tag,
                        evidence.getFirst().capture.at, evidence.getLast().capture.at));
            }
        }
        for (var participant : data.participants) if (identities.contains(participant.puuid) && !matches.contains(participant.match))
            throw new IllegalStateException("REMOVED_IDENTITY_HAS_RETAINED_MATCH");

        var payloads = new TreeSet<UUID>(sensitivePayloads);
        for (var id : captures) payloads.add(data.captures.get(id).payload);
        payloads.removeIf(id -> data.captures.values().stream().anyMatch(c -> c.payload.equals(id) && !captures.contains(c.id)));
        var counts = new TreeMap<String, Long>();
        for (String table : TABLES) counts.put(table, 0L);
        counts.put("ingestion_run", (long)runs.size()); counts.put("source_capture", (long)captures.size());
        counts.put("source_payload", (long)payloads.size()); counts.put("ingestion_item", (long)items.size());
        counts.put("riot_identity", data.identities.stream().filter(i -> identities.contains(i.puuid)).count());
        counts.put("riot_identity_reanchored", (long)reanchors.size());
        for (String table : List.of("riot_match", "riot_team", "riot_participant", "participant_state_observation", "match_event", "evidence_coverage")) {
            long count = 0;
            for (String match : matches) count += jdbc.queryForObject("select count(*) from league_analysis." + table + " where match_id=?", Long.class, match);
            counts.put(table, count);
        }
        for(String table:List.of("player_profile_current","rank_refresh_state","rank_observation")) {
            long count=0;
            for(String identity:identities) count+=jdbc.queryForObject("select count(*) from league_analysis."+table+" where puuid=?",Long.class,identity);
            counts.put(table,count);
        }
        var changes = new Changes(runs, captures, payloads, identities, items, reanchors);
        String fingerprint = fingerprint(puuidHashes, aliases, matchHashes, data.rows);
        return new RemovalPlan(puuidHashes, aliases, matchHashes, matches.stream().sorted().toList(), counts, fingerprint, changes);
    }

    public void apply(RemovalPlan plan) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !"on".equals(jdbc.queryForObject("select current_setting('league_analysis.removal_operator',true)", String.class)))
            throw new IllegalStateException("REMOVAL_REQUIRES_OPERATOR_TRANSACTION");
        // Protect plan validation from even an uncooperative older application writer.
        for (String table : TABLES) jdbc.execute("lock table league_analysis." + table + " in share row exclusive mode");
        var current = planHashes(plan.puuidHashes(), plan.aliasHashes(), plan.matchHashes());
        if (!current.fingerprint().equals(plan.fingerprint())) throw new IllegalStateException("REMOVAL_PLAN_CHANGED");
        var changes = current.changes;
        for (String id : current.matchIds()) jdbc.update("delete from league_analysis.riot_match where match_id=?", id);
        for (var item : changes.items) jdbc.update("delete from league_analysis.ingestion_item where ingestion_run_id=? and match_id=?", item.run, item.match);
        for (String puuid : changes.identities) jdbc.update("delete from league_analysis.riot_identity where puuid=?", puuid);
        for (var row : changes.reanchors) jdbc.update("""
                update league_analysis.riot_identity set last_source_capture_id=?,game_name=?,tag_line=?,
                    first_observed_at=?,last_observed_at=? where puuid=?
                """, row.capture, row.name, row.tag, row.first, row.last, row.puuid);
        for (UUID id : changes.captures) jdbc.update("delete from league_analysis.source_capture where id=?", id);
        for (UUID id : changes.payloads) jdbc.update("delete from league_analysis.source_payload where id=?", id);
        for (UUID id : changes.runs) jdbc.update("delete from league_analysis.ingestion_run where id=?", id);
    }

    private Data load() {
        var payloads = new TreeMap<UUID, Payload>();
        jdbc.query("select id,source_kind,payload_json::text from league_analysis.source_payload", rs -> {
            UUID id = rs.getObject(1, UUID.class); payloads.put(id, new Payload(id, rs.getString(2), json.readTree(rs.getString(3))));
        });
        var captures = new TreeMap<UUID, Capture>();
        jdbc.query("select id,ingestion_run_id,source_payload_id,source_kind,resource_key,captured_at from league_analysis.source_capture", rs -> {
            UUID id = rs.getObject(1, UUID.class); captures.put(id, new Capture(id, rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime.class)));
        });
        var identities = jdbc.query("select puuid,game_name,tag_line,last_source_capture_id from league_analysis.riot_identity order by puuid",
                (rs,n) -> new Identity(rs.getString(1),rs.getString(2),rs.getString(3),rs.getObject(4,UUID.class)));
        var participants = jdbc.query("select match_id,puuid from league_analysis.riot_participant order by match_id,participant_id", (rs,n) -> new Participant(rs.getString(1),rs.getString(2)));
        var runs = jdbc.query("select id,requested_game_name,requested_tag_line,resolved_puuid from league_analysis.ingestion_run order by id", (rs,n) -> new Run(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4)));
        var items = jdbc.query("select ingestion_run_id,match_id,detail_capture_id,timeline_capture_id from league_analysis.ingestion_item order by ingestion_run_id,match_id", (rs,n) -> new Item(rs.getObject(1,UUID.class),rs.getString(2),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class)));
        var matches = jdbc.query("select match_id,detail_source_capture_id,timeline_source_capture_id from league_analysis.riot_match order by match_id", (rs,n) -> new Match(rs.getString(1),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class)));
        var rows = new ArrayList<String>();
        for (String table : TABLES) {
            rows.add(table);
            rows.addAll(jdbc.queryForList("select to_jsonb(t)::text from league_analysis." + table + " t order by to_jsonb(t)::text", String.class));
        }
        return new Data(payloads,captures,identities,participants,runs,items,matches,rows);
    }
    private static String fingerprint(Set<String> subjects, Set<String> aliases, Set<String> matches, List<String> rows) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            // Preserve the v1 character lengths and UTF-8 encoding without
            // allocating the complete dataset again as text or a byte array.
            try (var content = new OutputStreamWriter(new DigestOutputStream(OutputStream.nullOutputStream(), digest),
                    StandardCharsets.UTF_8)) {
                content.write("removal-plan-v1\n");
                for (var group : List.of(subjects,aliases,matches)) {
                    content.write(new TreeSet<>(group).toString());
                    content.write('\n');
                }
                for (String row : rows) {
                    content.write(Integer.toString(row.length()));
                    content.write(':');
                    content.write(row);
                    content.write('\n');
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private static boolean isMatch(String kind) { return kind.equals("MATCH_DETAIL") || kind.equals("MATCH_TIMELINE"); }
    private static void addMatch(Set<String> matches, String match) {
        if (match == null || !match.matches("NA1_[0-9]+")) throw new IllegalStateException("UNSUPPORTED_CAPTURE_MATCH_IDENTIFIER");
        matches.add(match);
    }
    private static void addAlias(Set<String> aliases, String name, String tag) {
        if (name != null && !name.isBlank() && tag != null && !tag.isBlank()) aliases.add(PrivacyHash.riotId(name,tag));
    }
    private static void owner(Map<String,Set<String>> owners, String puuid, String name, String tag) {
        if (puuid != null && name != null && !name.isBlank() && tag != null && !tag.isBlank())
            owners.computeIfAbsent(PrivacyHash.riotId(name,tag), ignored -> new HashSet<>()).add(puuid);
    }
    private static String text(JsonNode node, String field) {
        var child = node.path(field); return child.isString() && !child.stringValue().isBlank() ? child.stringValue() : null;
    }
    private static void collectHashedStrings(JsonNode node, Set<String> hashes, Set<String> result) {
        if (hashes.isEmpty()) return;
        if (node.isString() && hashes.contains(PrivacyHash.of(node.stringValue()))) result.add(node.stringValue());
        if (node.isArray() || node.isObject()) for (var child : node) collectHashedStrings(child, hashes, result);
    }
    private static boolean containsSubject(JsonNode node, Set<String> subjects) {
        if (node.isString()) return subjects.contains(node.stringValue());
        if (node.isArray() || node.isObject()) for (var child : node) if (containsSubject(child,subjects)) return true;
        return false;
    }
    private static boolean containsMatch(JsonNode node, Set<String> matches) {
        if (!node.isArray()) return false;
        for (var child : node) if (child.isString() && matches.contains(child.stringValue())) return true;
        return false;
    }
    private static List<Person> people(Payload payload) {
        var result = new ArrayList<Person>();
        if (payload.kind.equals("ACCOUNT")) addPerson(result,payload.body,"gameName","tagLine");
        if (isMatch(payload.kind)) {
            for (var node : payload.body.path("info").path("participants")) addPerson(result,node,"riotIdGameName","riotIdTagline");
            for (var node : payload.body.path("metadata").path("participants"))
                if (node.isString() && !node.stringValue().isBlank()) result.add(new Person(node.stringValue(),null,null));
        }
        return result;
    }
    private static void addPerson(List<Person> people, JsonNode node, String name, String tag) {
        String puuid = text(node,"puuid");
        if (puuid != null) people.add(new Person(puuid,text(node,name),text(node,tag)));
    }
    record Changes(Set<UUID> runs, Set<UUID> captures, Set<UUID> payloads, Set<String> identities, List<Item> items, List<Reanchor> reanchors) {}
    record Payload(UUID id,String kind,JsonNode body) {}
    record Capture(UUID id,UUID run,UUID payload,String kind,String resource,OffsetDateTime at) {}
    record Identity(String puuid,String name,String tag,UUID capture) {}
    record Participant(String match,String puuid) {}
    record Run(UUID id,String name,String tag,String puuid) {}
    record Item(UUID run,String match,UUID detail,UUID timeline) {}
    record Match(String match,UUID detail,UUID timeline) {}
    record Person(String puuid,String name,String tag) {}
    record IdentityEvidence(Capture capture,Person person) {}
    record Reanchor(String puuid,UUID capture,String name,String tag,OffsetDateTime first,OffsetDateTime last) {}
    record Data(Map<UUID,Payload> payloads,Map<UUID,Capture> captures,List<Identity> identities,List<Participant> participants,
                List<Run> runs,List<Item> items,List<Match> matches,List<String> rows) {
        Set<String> verifiedPuuids() {
            var values = new HashSet<String>();
            identities.forEach(i -> values.add(i.puuid)); participants.forEach(p -> values.add(p.puuid));
            runs.forEach(r -> { if(r.puuid != null) values.add(r.puuid); });
            payloads.values().forEach(p -> people(p).forEach(person -> values.add(person.puuid)));
            return values;
        }
    }
}
