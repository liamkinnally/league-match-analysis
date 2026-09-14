export type MatchDevelopmentSummary = {
  queueId: number;
  mapId: number;
  gameMode: string;
  gameVersion: string;
  gameCreationMs: number;
  durationMs: number;
  focusParticipantId: number;
  compareParticipantId: number | null;
  win: boolean;
  kills: number;
  deaths: number;
  assists: number;
  totalCs: number;
  goldEarned: number;
};

export type MatchDevelopmentParticipant = {
  runes?: ParticipantRunes | null;
  participantTotals?: ParticipantTotals | null;
  gameName?: string | null;
  tagLine?: string | null;
  summonerName?: string | null;
  participantId: number;
  teamId: number;
  championId: number;
  championName: string;
  teamPosition: string;
  win: boolean;
  kills: number;
  deaths: number;
  assists: number;
  laneCs: number;
  jungleCs: number;
  totalCs: number;
  goldEarned: number;
  goldSpent: number;
  visionScore: number;
  summonerSpellOneId: number;
  summonerSpellTwoId: number;
  endItemIds: number[];
};

export type RuneAvailability = "available" | "missing" | "unsupported" | "unverified";
export type RuneMetric = {
  id: string; label: string; availability: RuneAvailability; value: number | null;
  unit: string; targetScope: string; timeScope: "end-of-game";
  valueBasis: "source-reported" | "derived"; mappingVersion: string;
};
export type RuneCounters = { var1: number | null; var2: number | null; var3: number | null };
export type RuneSelection = { runeId: number; metrics: RuneMetric[]; counters?: RuneCounters | null };
export type RuneStyle = { styleId: number | null; role: "primaryStyle" | "subStyle" | "unknown"; selections: RuneSelection[] };
export type ParticipantRunes = {
  availability: RuneAvailability; matchPatch: string; normalizationVersion: string;
  layoutManifestId: string | null; performanceStatus: RuneAvailability;
  styles: RuneStyle[]; shards: { offense: number | null; flex: number | null; defense: number | null };
};
export type ParticipantTotals = {
  totalDamageDealt: number | null; totalDamageDealtToChampions: number | null;
  totalHeal: number | null; totalHealsOnTeammates: number | null;
  totalDamageShieldedOnTeammates: number | null;
};

export type MatchDevelopmentSample = {
  timestampMs: number;
  goldDifference: number | null;
  csDifference: number | null;
  xpDifference: number | null;
  focalLevel: number | null;
  compareLevel: number | null;
  focalTotalGold: number | null;
  focalCs: number | null;
  focalXp: number | null;
};

export type EventTeamFact = {
  teamId: number | null;
  basis: "known" | "missing" | "conflicting" | "unsupported";
};

export type MatchDevelopmentEvent = {
  presentation?: { actorTeam: EventTeamFact; objectTeam: EventTeamFact } | null;
  type?: string;
  frameAtMs?: number;
  frameEventIndex?: number;
  fields?: Record<string, unknown>;
  x?: number | null;
  y?: number | null;
  timestampMs: number;
  label: string;
  participantIds: number[];
  itemId: number | null;
  actorParticipantId: number | null;
  targetParticipantId: number | null;
  assisterParticipantIds: number[];
  assistersObserved: boolean;
};

export type MatchDevelopmentWindow = {
  id: string;
  startMs: number;
  endMs: number;
  before: MatchDevelopmentSample;
  after: MatchDevelopmentSample;
  summary: string;
};

export type MatchDevelopment = {
  matchId: string;
  summary: MatchDevelopmentSummary;
  roster: MatchDevelopmentParticipant[];
  timelineAvailable: boolean;
  samples: MatchDevelopmentSample[];
  windows: MatchDevelopmentWindow[];
  suggestedWindows: MatchDevelopmentWindow[];
  events: MatchDevelopmentEvent[];
  teams?: FinalTeamResult[];
};

export type FinalTeamResult = {
  teamId: number;
  win: boolean | null;
  kills: number | null;
  deaths: number | null;
  assists: number | null;
  goldEarned: number | null;
  objectives: Record<string, number | null>;
};

export type Metric = "gold" | "cs" | "xp";
export type PlayerRank = {
  participantId: number;
  status: "loading" | "ranked" | "unranked" | "unavailable";
  tier?: string | null;
  division?: string | null;
  leaguePoints?: number | null;
  fetchedAt?: string | null;
  cached?: boolean;
  stale?: boolean;
  error?: string | null;
};
export type CurrentRanks = {
  matchId: string;
  queueType: string | null;
  refreshing: boolean;
  players: PlayerRank[];
};

export type DemoMatch = {
  matchId: string;
  focusParticipantId: number;
  compareParticipantId: number;
  invented: boolean;
};

export type DevelopmentInterval = { from: number; to: number };
