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

export type MatchDevelopmentEvent = {
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
