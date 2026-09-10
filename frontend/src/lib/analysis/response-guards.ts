import type { MatchAnalysisResponse } from "./types";

type UnknownRecord = Record<string, unknown>;
type Guard = (value: unknown) => boolean;

const isRecord = (value: unknown): value is UnknownRecord =>
  typeof value === "object" && value !== null && !Array.isArray(value);
const isString = (value: unknown): value is string => typeof value === "string";
const isBoolean = (value: unknown): value is boolean => typeof value === "boolean";
const isInteger = (value: unknown): value is number =>
  typeof value === "number" && Number.isSafeInteger(value);
const isNonNegativeInteger = (value: unknown): value is number =>
  isInteger(value) && value >= 0;
const isNullable = (value: unknown, guard: Guard): boolean =>
  value === null || guard(value);
const isArrayOf = (value: unknown, guard: Guard): boolean =>
  Array.isArray(value) && value.every(guard);
const isStringArray = (value: unknown): value is string[] =>
  isArrayOf(value, isString);

function has(value: UnknownRecord, shape: Record<string, Guard>): boolean {
  return Object.entries(shape).every(([key, guard]) => guard(value[key]));
}

function isInterval(value: unknown): boolean {
  return (
    isRecord(value) &&
    isNonNegativeInteger(value.startMs) &&
    isNonNegativeInteger(value.endMs) &&
    value.endMs >= value.startMs
  );
}

function isEvidenceReference(value: unknown): boolean {
  return (
    isRecord(value) &&
    has(value, {
      sourceKind: isString,
      sourceCaptureId: isString,
      sourceRecordId: isString,
      representedAtMs: isNonNegativeInteger,
      methodVersion: isString,
    })
  );
}

function isClaim(value: unknown): boolean {
  return (
    isRecord(value) &&
    has(value, {
      claimId: isString,
      statement: isString,
      assertionMode: isString,
      evidenceReferences: (item) => isArrayOf(item, isEvidenceReference),
      limitations: isStringArray,
    })
  );
}

function isAnchor(value: unknown): boolean {
  const nullableInteger = (item: unknown) => isNullable(item, isInteger);
  return (
    isRecord(value) &&
    has(value, {
      representedAtMs: isNonNegativeInteger,
      kind: isString,
      actorParticipantId: nullableInteger,
      targetParticipantId: nullableInteger,
      teamId: nullableInteger,
      x: nullableInteger,
      y: nullableInteger,
      descriptor: (item) => isNullable(item, isString),
      limitations: isStringArray,
    })
  );
}

function isTransition(value: unknown): boolean {
  return (
    isRecord(value) &&
    has(value, {
      transitionId: isString,
      interval: isInterval,
      anchors: (item) => isArrayOf(item, isAnchor),
      title: isString,
      questionId: isString,
      primaryLens: isLensType,
      materialityReasons: isStringArray,
      focalRelationships: isStringArray,
      interpretation: (item) => isNullable(item, isClaim),
      limitations: isStringArray,
    })
  );
}

function isReviewBeat(value: unknown): boolean {
  return (
    isRecord(value) &&
    has(value, {
      id: isString,
      kind: isString,
      title: isString,
      detail: isString,
      assertionMode: isString,
      sourceClaimId: (item) => isNullable(item, isString),
      evidenceReferences: (item) => isArrayOf(item, isEvidenceReference),
      limitations: isStringArray,
    })
  );
}

function isReviewEpisode(value: unknown): boolean {
  return (
    isRecord(value) &&
    has(value, {
      id: isString,
      transitionId: isString,
      kind: isString,
      interval: isInterval,
      questionId: isString,
      selectionRationale: isString,
      beats: (item) => isArrayOf(item, isReviewBeat),
      compositionRuleVersion: isString,
    })
  );
}

function isParticipantReceipt(value: unknown): boolean {
  return (
    isRecord(value) &&
    has(value, {
      participantId: isNonNegativeInteger,
      currentGold: isNonNegativeInteger,
      totalGold: isNonNegativeInteger,
      level: isNonNegativeInteger,
      creepScore: isNonNegativeInteger,
    })
  );
}

function isReceiptSample(value: unknown): boolean {
  const nullableInteger = (item: unknown) => isNullable(item, isInteger);
  const nullableNonNegativeInteger = (item: unknown) =>
    isNullable(item, isNonNegativeInteger);
  const nullableParticipant = (item: unknown) =>
    isNullable(item, isParticipantReceipt);
  return (
    isRecord(value) &&
    has(value, {
      representedAtMs: isNonNegativeInteger,
      focalTeamTotalGold: nullableNonNegativeInteger,
      opponentTeamTotalGold: nullableNonNegativeInteger,
      focalTeamLead: nullableInteger,
      focalParticipant: nullableParticipant,
      laneOpponent: nullableParticipant,
      consequenceEvidence: isBoolean,
      limitations: isStringArray,
    })
  );
}

function isReceipt(value: unknown): boolean {
  return (
    isRecord(value) &&
    has(value, {
      interval: isInterval,
      before: (item) => isNullable(item, isReceiptSample),
      after: (item) => isNullable(item, isReceiptSample),
      focalTeamLeadDelta: (item) => isNullable(item, isInteger),
      projectionRuleVersion: isString,
      claims: (item) => isArrayOf(item, isClaim),
      limitations: isStringArray,
    })
  );
}

function isMapLens(value: UnknownRecord): boolean {
  return has(value, {
    points: (points) =>
      isArrayOf(
        points,
        (point) =>
          isRecord(point) &&
          has(point, {
            representedAtMs: isNonNegativeInteger,
            x: isInteger,
            y: isInteger,
            anchorKind: isString,
            participantId: (item) => isNullable(item, isInteger),
            descriptor: (item) => isNullable(item, isString),
            evidenceReferences: (item) => isArrayOf(item, isEvidenceReference),
          }),
      ),
    claims: (item) => isArrayOf(item, isClaim),
  });
}

function isSequenceLens(value: UnknownRecord): boolean {
  return has(value, {
    bands: (bands) =>
      isArrayOf(
        bands,
        (band) =>
          isRecord(band) &&
          isNonNegativeInteger(band.startMs) &&
          isNonNegativeInteger(band.endMs) &&
          band.endMs >= band.startMs &&
          has(band, {
            id: isString,
            parallel: isBoolean,
            anchors: (item) => isArrayOf(item, isAnchor),
          }),
      ),
    relations: (relations) =>
      isArrayOf(
        relations,
        (relation) =>
          isRecord(relation) &&
          has(relation, {
            fromBandId: isString,
            toBandId: isString,
            relation: isString,
            evidenceReferences: (item) => isArrayOf(item, isEvidenceReference),
          }),
      ),
    claims: (item) => isArrayOf(item, isClaim),
  });
}

function isCapabilityAssertion(value: unknown): boolean {
  const text = (item: unknown) => isString(item) && item.trim().length > 0;
  const prerequisites = (item: unknown) => isArrayOf(item, (tag) =>
    isString(tag) && ["OWNED", "ACTIVE_USE_REQUIRED", "CHAMPION_HIT_FOR_MOVEMENT"].includes(tag));
  return isRecord(value) && has(value, {
    assertionId: text,
    patch: text,
    applicableBuild: text,
    entityType: (item) => item === "ITEM",
    entityId: (item) => isInteger(item) && item > 0,
    capabilities: (item) => isArrayOf(item, (tag) => tag === "SLOW" || tag === "MOVEMENT_SPEED"),
    prerequisites,
    sourceUri: (item) => text(item) && (item as string).startsWith("https://"),
    sourceRevision: text,
    reviewerId: text,
    reviewStatus: (item) => item === "APPROVED",
    reviewRevision: (item) => isInteger(item) && item > 0,
    assertionMode: (item) => item === "EXPERT_MAINTAINED",
    missingPrerequisites: prerequisites,
  });
}

function isLens(value: unknown): boolean {
  if (!isRecord(value) || !isString(value.type)) return false;
  const claims = (item: unknown) => isArrayOf(item, isClaim);
  switch (value.type) {
    case "MAP":
      return isMapLens(value);
    case "SEQUENCE":
      return isSequenceLens(value);
    case "STATE":
    case "RECEIPT":
      return has(value, { receipt: isReceipt, claims });
    case "CHAMPION_TIMING":
      return has(value, {
        knowledgeVersion: isString,
        capabilities: (capabilities) =>
          isArrayOf(
            capabilities,
            (capability) =>
              isRecord(capability) &&
              has(capability, { category: isString, statement: isString }) &&
              (capability.assertion === undefined || isCapabilityAssertion(capability.assertion)),
          ),
        claims,
      });
    case "TRANSFER":
      return has(value, {
        rows: (rows) =>
          isArrayOf(
            rows,
            (row) =>
              isRecord(row) &&
              has(row, {
                representedAtMs: isNonNegativeInteger,
                category: isString,
                observedFor: isString,
                evidenceReferences: (item) =>
                  isArrayOf(item, isEvidenceReference),
              }),
          ),
        claims,
      });
    default:
      return false;
  }
}

const isLensType = (item: unknown) =>
    typeof item === "string" &&
    ["MAP", "SEQUENCE", "STATE", "CHAMPION_TIMING", "TRANSFER", "RECEIPT"].includes(
      item,
    );

function isAnalysisContext(value: unknown): boolean {
  return (
    isRecord(value) &&
    has(value, {
      matchId: isString,
      focalParticipantId: isInteger,
      selectedObjectId: isString,
      interval: isInterval,
      questionId: isString,
      evidenceRevision: isString,
      primaryLens: isLensType,
      selectedLens: isLensType,
      availableLenses: (item) => isArrayOf(item, isLensType),
    })
  );
}

function isEvidence(value: unknown): boolean {
  return (
    isRecord(value) &&
    has(value, {
      claimIds: isStringArray,
      references: (item) => isArrayOf(item, isEvidenceReference),
      coverage: (coverage) =>
        isArrayOf(
          coverage,
          (entry) =>
            isRecord(entry) &&
            has(entry, {
              signal: isString,
              sourceKind: isString,
              status: isString,
              representedStartMs: (item) =>
                isNullable(item, isNonNegativeInteger),
              representedEndMs: (item) =>
                isNullable(item, isNonNegativeInteger),
              sourceCaptureId: (item) => isNullable(item, isString),
              sourceRecordId: isString,
              methodVersion: isString,
            }),
        ),
      revision: (revision) =>
        isRecord(revision) &&
        has(revision, {
          evidenceRevision: isString,
          historicalMethodVersions: isStringArray,
          transitionPolicyVersion: isString,
          receiptProjectionVersion: isString,
          lensPolicyVersion: isString,
          episodePolicyVersion: isString,
          reviewOrderingPolicyVersion: isString,
          championKnowledgeVersion: (item) => isNullable(item, isString),
        }),
    })
  );
}

function isActive(value: unknown): boolean {
  return (
    isRecord(value) &&
    has(value, {
      context: isAnalysisContext,
      sourceTransitionId: isString,
      receipt: isReceipt,
      lens: isLens,
      claims: (item) => isArrayOf(item, isClaim),
      limitations: isStringArray,
      evidence: isEvidence,
      supportedQuestions: (questions) =>
        isArrayOf(
          questions,
          (question) =>
            isRecord(question) &&
            has(question, {
              id: isString,
              prompt: isString,
              answerMode: isString,
            }),
        ),
      linkedReviewEpisode: (item) => isNullable(item, isReviewEpisode),
    })
  );
}

function isMatchAnalysisResponse(value: unknown): value is MatchAnalysisResponse {
  return (
    isRecord(value) &&
    has(value, {
      match: (match) =>
        isRecord(match) &&
        has(match, {
          matchId: isString,
          durationMs: isNonNegativeInteger,
          gameVersion: isString,
          focalParticipant: (participant) =>
            isRecord(participant) &&
            has(participant, {
              participantId: isInteger,
              championId: isInteger,
              championName: isString,
              teamPosition: isString,
              teamId: isInteger,
              win: isBoolean,
            }),
        }),
      evidenceRevision: isString,
      arc: (arc) =>
        isRecord(arc) &&
        has(arc, {
          transitions: (item) => isArrayOf(item, isTransition),
          eligibleUnselectedCount: isNonNegativeInteger,
          selectionRuleVersion: isString,
          emptyReason: (item) => isNullable(item, isString),
        }),
      review: (review) =>
        isRecord(review) &&
        has(review, {
          episodes: (item) => isArrayOf(item, isReviewEpisode),
          learningOrder: isStringArray,
          chronologicalOrder: isStringArray,
          temporalCues: (cues) =>
            isRecord(cues) && Object.values(cues).every(isString),
          orderingRuleVersion: isString,
        }),
      active: (item) => isNullable(item, isActive),
    })
  );
}

export function parseMatchAnalysisResponse(value: unknown): MatchAnalysisResponse {
  if (!isMatchAnalysisResponse(value)) {
    throw new Error("INVALID_MATCH_ANALYSIS_RESPONSE");
  }
  return value;
}
