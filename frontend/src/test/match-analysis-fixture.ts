const evidenceReference = {
  sourceKind: "MATCH_TIMELINE",
  sourceCaptureId: "capture-1",
  sourceRecordId: "frame-10:event-1",
  representedAtMs: 780_275,
  methodVersion: "timeline-v1",
};

const claim = {
  claimId: "claim-1",
  statement: "The gold lead widened after this transition.",
  assertionMode: "OBSERVED",
  evidenceReferences: [evidenceReference],
  limitations: [],
};

const receipt = {
  interval: { startMs: 780_275, endMs: 900_291 },
  before: {
    representedAtMs: 780_275,
    focalTeamTotalGold: 24_000,
    opponentTeamTotalGold: 23_181,
    focalTeamLead: 819,
    focalParticipant: {
      participantId: 6,
      currentGold: 1_100,
      totalGold: 5_200,
      level: 10,
      creepScore: 112,
    },
    laneOpponent: {
      participantId: 1,
      currentGold: 900,
      totalGold: 4_800,
      level: 9,
      creepScore: 104,
    },
    consequenceEvidence: false,
    limitations: [],
  },
  after: {
    representedAtMs: 900_291,
    focalTeamTotalGold: 30_142,
    opponentTeamTotalGold: 27_000,
    focalTeamLead: 3_142,
    focalParticipant: {
      participantId: 6,
      currentGold: 1_300,
      totalGold: 6_900,
      level: 12,
      creepScore: 146,
    },
    laneOpponent: {
      participantId: 1,
      currentGold: 700,
      totalGold: 5_900,
      level: 11,
      creepScore: 134,
    },
    consequenceEvidence: true,
    limitations: [],
  },
  focalTeamLeadDelta: 2_323,
  projectionRuleVersion: "receipt-p3-v1",
  claims: [claim],
  limitations: [],
};

const transitions = [
  ["trn_000000000000000000000001", 480_000, 540_000, "First objective changed map pressure"],
  ["trn_000000000000000000000002", 780_275, 900_291, "A team fight widened the lead"],
  ["trn_000000000000000000000003", 1_020_000, 1_080_000, "Parallel trades redistributed gold"],
  ["trn_000000000000000000000004", 1_200_000, 1_260_000, "Late pressure constrained the defense"],
].map(([transitionId, startMs, endMs, title], index) => ({
  transitionId,
  interval: { startMs, endMs },
  anchors: [
    {
      representedAtMs: startMs,
      kind: "OBJECTIVE",
      actorParticipantId: 6,
      targetParticipantId: null,
      teamId: 200,
      x: null,
      y: null,
      descriptor: `Observed transition E${index + 1}`,
      limitations: [],
    },
  ],
  title,
  questionId: "advantage-conversion",
  primaryLens: ["MAP", "SEQUENCE", "STATE", "SEQUENCE"][index],
  materialityReasons: ["TEAM_GOLD_SWING"],
  focalRelationships: ["FOCAL_TEAM"],
  interpretation: {
    ...claim,
    claimId: `claim-${index + 1}`,
    statement: `Interpretation for transition E${index + 1}.`,
  },
  limitations: [],
}));

const episodes = transitions.map((transition, index) => ({
  id: `ep_00000000000000000000000${index + 1}`,
  transitionId: transition.transitionId,
  kind: "ADVANTAGE_CONVERSION",
  interval: transition.interval,
  questionId: transition.questionId,
  selectionRationale: `Why case ${index + 1} belongs in the learning order.`,
  beats: [
    {
      id: `beat-${index + 1}`,
      kind: "OBSERVATION",
      title: `Observed case ${index + 1}`,
      detail: `Evidence-backed detail for case ${index + 1}.`,
      assertionMode: "OBSERVED",
      sourceClaimId: `claim-${index + 1}`,
      evidenceReferences: [evidenceReference],
      limitations: [],
    },
  ],
  compositionRuleVersion: "episode-p3-v1",
}));

export const fixtureResponse = {
  match: {
    matchId: "NA1_9000000001",
    durationMs: 1_800_000,
    gameVersion: "16.3.1",
    focalParticipant: {
      participantId: 6,
      championId: 99,
      championName: "Lux",
      teamPosition: "UTILITY",
      teamId: 200,
      win: true,
    },
  },
  evidenceRevision: `ev_${"1".repeat(64)}`,
  arc: {
    transitions,
    eligibleUnselectedCount: 2,
    selectionRuleVersion: "material-transition-p3-v1",
    emptyReason: null,
  },
  review: {
    episodes,
    learningOrder: episodes.map((episode) => episode.id),
    chronologicalOrder: episodes.map((episode) => episode.id),
    temporalCues: {
      [episodes[0].id]: "EARLIER",
      [episodes[1].id]: "EARLIER",
      [episodes[2].id]: "LATER",
      [episodes[3].id]: "LATER",
    },
    orderingRuleVersion: "review-order-p3-v1",
  },
  active: null,
};

export const sequenceLens = {
  type: "SEQUENCE",
  bands: [
    {
      id: "band-1",
      startMs: 780_275,
      endMs: 840_000,
      parallel: false,
      anchors: transitions[1].anchors,
    },
  ],
  relations: [],
  claims: [claim],
};

export const selectedResponse = {
  ...fixtureResponse,
  active: {
    context: {
      matchId: fixtureResponse.match.matchId,
      focalParticipantId: 6,
      selectedObjectId: transitions[1].transitionId,
      interval: receipt.interval,
      questionId: "advantage-conversion",
      evidenceRevision: fixtureResponse.evidenceRevision,
      primaryLens: "SEQUENCE",
      selectedLens: "SEQUENCE",
      availableLenses: ["SEQUENCE", "STATE", "TRANSFER"],
    },
    sourceTransitionId: transitions[1].transitionId,
    receipt,
    lens: sequenceLens,
    claims: [claim],
    limitations: [],
    evidence: {
      claimIds: [claim.claimId],
      references: [evidenceReference],
      coverage: [
        {
          signal: "TEAM_GOLD",
          sourceKind: "MATCH_TIMELINE",
          status: "OBSERVED",
          representedStartMs: 780_275,
          representedEndMs: 900_291,
          sourceCaptureId: "capture-1",
          sourceRecordId: "frame-10:event-1",
          methodVersion: "timeline-v1",
        },
      ],
      revision: {
        evidenceRevision: fixtureResponse.evidenceRevision,
        historicalMethodVersions: ["timeline-v1"],
        transitionPolicyVersion: "transition-p3-v1",
        receiptProjectionVersion: "receipt-p3-v1",
        lensPolicyVersion: "lens-p3-v1",
        episodePolicyVersion: "episode-p3-v1",
        reviewOrderingPolicyVersion: "review-order-p3-v1",
        championKnowledgeVersion: "champion-knowledge-p3-v1",
      },
    },
    supportedQuestions: [
      {
        id: "what-changed",
        prompt: "What changed?",
        answerMode: "RECEIPT",
      },
    ],
    linkedReviewEpisode: episodes[1],
  },
};

export const lensFixtures = [
  {
    type: "MAP",
    points: [
      {
        representedAtMs: 780_275,
        x: 5_000,
        y: 7_000,
        anchorKind: "OBJECTIVE",
        participantId: 6,
        descriptor: "Focal participant position",
        evidenceReferences: [evidenceReference],
      },
    ],
    claims: [claim],
  },
  sequenceLens,
  { type: "STATE", receipt, claims: [claim] },
  {
    type: "CHAMPION_TIMING",
    knowledgeVersion: "champion-knowledge-p3-v1",
    capabilities: [{ category: "UTILITY", statement: "Provides zone control." }],
    claims: [claim],
  },
  {
    type: "TRANSFER",
    rows: [
      {
        representedAtMs: 900_291,
        category: "TEAM_GOLD",
        observedFor: "FOCAL_TEAM",
        evidenceReferences: [evidenceReference],
      },
    ],
    claims: [claim],
  },
  { type: "RECEIPT", receipt, claims: [claim] },
];
