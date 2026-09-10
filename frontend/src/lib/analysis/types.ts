export type AnalysisMode = "explore" | "review" | "investigate";

export type RequestedLens =
  | "MAP"
  | "SEQUENCE"
  | "STATE"
  | "CHAMPION_TIMING"
  | "TRANSFER"
  | "RECEIPT";

export type AnalysisPanel = "evidence" | "ask";

export type AnalysisReturnTarget = {
  mode: AnalysisMode;
  objectId?: string;
  beatId?: string;
};

export type AnalysisRouteContext = {
  matchId: string;
  focalParticipantId: number;
  mode: AnalysisMode;
  selectedObjectId?: string;
  interval?: { startMs: number; endMs: number };
  questionId?: string;
  evidenceRevision?: string;
  requestedLens?: RequestedLens;
  returnTarget?: AnalysisReturnTarget;
  panel?: AnalysisPanel;
};

export type LensType = RequestedLens;

export type Interval = {
  startMs: number;
  endMs: number;
};

export type EvidenceReference = {
  sourceKind: string;
  sourceCaptureId: string;
  sourceRecordId: string;
  representedAtMs: number;
  methodVersion: string;
};

export type EvidenceClaim = {
  claimId: string;
  statement: string;
  assertionMode: string;
  evidenceReferences: EvidenceReference[];
  limitations: string[];
};

export type Anchor = {
  representedAtMs: number;
  kind: string;
  actorParticipantId: number | null;
  targetParticipantId: number | null;
  teamId: number | null;
  x: number | null;
  y: number | null;
  descriptor: string | null;
  limitations: string[];
};

export type Transition = {
  transitionId: string;
  interval: Interval;
  anchors: Anchor[];
  title: string;
  questionId: string;
  primaryLens: RequestedLens;
  materialityReasons: string[];
  focalRelationships: string[];
  interpretation: EvidenceClaim | null;
  limitations: string[];
};

export type ReviewBeat = {
  id: string;
  kind: string;
  title: string;
  detail: string;
  assertionMode: string;
  sourceClaimId: string | null;
  evidenceReferences: EvidenceReference[];
  limitations: string[];
};

export type ReviewEpisode = {
  id: string;
  transitionId: string;
  kind: string;
  interval: Interval;
  questionId: string;
  selectionRationale: string;
  beats: ReviewBeat[];
  compositionRuleVersion: string;
};

export type Review = {
  episodes: ReviewEpisode[];
  learningOrder: string[];
  chronologicalOrder: string[];
  temporalCues: Record<string, string>;
  orderingRuleVersion: string;
};

export type ParticipantReceipt = {
  participantId: number;
  currentGold: number;
  totalGold: number;
  level: number;
  creepScore: number;
};

export type ReceiptSample = {
  representedAtMs: number;
  focalTeamTotalGold: number | null;
  opponentTeamTotalGold: number | null;
  focalTeamLead: number | null;
  focalParticipant: ParticipantReceipt | null;
  laneOpponent: ParticipantReceipt | null;
  consequenceEvidence: boolean;
  limitations: string[];
};

export type StateReceipt = {
  interval: Interval;
  before: ReceiptSample | null;
  after: ReceiptSample | null;
  focalTeamLeadDelta: number | null;
  projectionRuleVersion: string;
  claims: EvidenceClaim[];
  limitations: string[];
};

export type MapLens = {
  type: "MAP";
  points: Array<{
    representedAtMs: number;
    x: number;
    y: number;
    anchorKind: string;
    participantId: number | null;
    descriptor: string | null;
    evidenceReferences: EvidenceReference[];
  }>;
  claims: EvidenceClaim[];
};

export type SequenceLens = {
  type: "SEQUENCE";
  bands: Array<{
    id: string;
    startMs: number;
    endMs: number;
    parallel: boolean;
    anchors: Anchor[];
  }>;
  relations: Array<{
    fromBandId: string;
    toBandId: string;
    relation: string;
    evidenceReferences: EvidenceReference[];
  }>;
  claims: EvidenceClaim[];
};

export type StateLens = {
  type: "STATE";
  receipt: StateReceipt;
  claims: EvidenceClaim[];
};

export type ChampionTimingLens = {
  type: "CHAMPION_TIMING";
  knowledgeVersion: string;
  capabilities: Array<{
    category: string;
    statement: string;
    assertion?: CapabilityAssertion;
  }>;
  claims: EvidenceClaim[];
};

export type CapabilityAssertion = {
  assertionId: string;
  patch: string;
  applicableBuild: string;
  entityType: string;
  entityId: number;
  capabilities: string[];
  prerequisites: string[];
  sourceUri: string;
  sourceRevision: string;
  reviewerId: string;
  reviewStatus: string;
  reviewRevision: number;
  assertionMode: string;
  missingPrerequisites: string[];
};

export type TransferLens = {
  type: "TRANSFER";
  rows: Array<{
    representedAtMs: number;
    category: string;
    observedFor: string;
    evidenceReferences: EvidenceReference[];
  }>;
  claims: EvidenceClaim[];
};

export type ReceiptLens = {
  type: "RECEIPT";
  receipt: StateReceipt;
  claims: EvidenceClaim[];
};

export type AnalysisLens =
  | MapLens
  | SequenceLens
  | StateLens
  | ChampionTimingLens
  | TransferLens
  | ReceiptLens;

export type AnalysisEvidence = {
  claimIds: string[];
  references: EvidenceReference[];
  coverage: Array<{
    signal: string;
    sourceKind: string;
    status: string;
    representedStartMs: number | null;
    representedEndMs: number | null;
    sourceCaptureId: string | null;
    sourceRecordId: string;
    methodVersion: string;
  }>;
  revision: {
    evidenceRevision: string;
    historicalMethodVersions: string[];
    transitionPolicyVersion: string;
    receiptProjectionVersion: string;
    lensPolicyVersion: string;
    episodePolicyVersion: string;
    reviewOrderingPolicyVersion: string;
    championKnowledgeVersion: string | null;
  };
};

export type ActiveAnalysis = {
  context: {
    matchId: string;
    focalParticipantId: number;
    selectedObjectId: string;
    interval: Interval;
    questionId: string;
    evidenceRevision: string;
    primaryLens: LensType;
    selectedLens: LensType;
    availableLenses: LensType[];
  };
  sourceTransitionId: string;
  receipt: StateReceipt;
  lens: AnalysisLens;
  claims: EvidenceClaim[];
  limitations: string[];
  evidence: AnalysisEvidence;
  supportedQuestions: Array<{ id: string; prompt: string; answerMode: string }>;
  linkedReviewEpisode: ReviewEpisode | null;
};

export type MatchAnalysisResponse = {
  match: {
    matchId: string;
    durationMs: number;
    gameVersion: string;
    focalParticipant: {
      participantId: number;
      championId: number;
      championName: string;
      teamPosition: string;
      teamId: number;
      win: boolean;
    };
  };
  evidenceRevision: string;
  arc: {
    transitions: Transition[];
    eligibleUnselectedCount: number;
    selectionRuleVersion: string;
    emptyReason: string | null;
  };
  review: Review;
  active: ActiveAnalysis | null;
};
