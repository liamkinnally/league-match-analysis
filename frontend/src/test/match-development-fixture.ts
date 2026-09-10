export const developmentFixture = {
  matchId: "NA1_7000000001",
  summary: {
    queueId: 420,
    mapId: 11,
    gameMode: "CLASSIC",
    gameVersion: "16.17.1",
    gameCreationMs: 1_789_000_000_000,
    durationMs: 1_800_000,
    focusParticipantId: 6,
    compareParticipantId: 1,
    win: true,
    kills: 8,
    deaths: 3,
    assists: 7,
    totalCs: 238,
    goldEarned: 13_800,
  },
  roster: [
    {
      participantId: 1, teamId: 100, championId: 122, championName: "Darius",
      teamPosition: "TOP", win: false, kills: 5, deaths: 6, assists: 3,
      laneCs: 190, jungleCs: 5, totalCs: 195, goldEarned: 12_600,
      goldSpent: 11_800, visionScore: 18, summonerSpellOneId: 4,
      summonerSpellTwoId: 12, endItemIds: [3078, 3047, 3053, 0, 0, 0, 3363],
    },
    {
      participantId: 2, teamId: 100, championId: 64, championName: "LeeSin",
      teamPosition: "JUNGLE", win: false, kills: 4, deaths: 7, assists: 5,
      laneCs: 31, jungleCs: 141, totalCs: 172, goldEarned: 11_800,
      goldSpent: 11_200, visionScore: 22, summonerSpellOneId: 4,
      summonerSpellTwoId: 11, endItemIds: [6630, 3111, 3053, 0, 0, 0, 3364],
    },
    {
      participantId: 6, teamId: 200, championId: 86, championName: "Garen",
      teamPosition: "TOP", win: true, kills: 8, deaths: 3, assists: 7,
      laneCs: 228, jungleCs: 10, totalCs: 238, goldEarned: 13_800,
      goldSpent: 13_100, visionScore: 20, summonerSpellOneId: 4,
      summonerSpellTwoId: 12, endItemIds: [3071, 3006, 3053, 0, 0, 0, 3363],
    },
    {
      participantId: 7, teamId: 200, championId: 254, championName: "Vi",
      teamPosition: "JUNGLE", win: true, kills: 5, deaths: 4, assists: 9,
      laneCs: 27, jungleCs: 157, totalCs: 184, goldEarned: 12_500,
      goldSpent: 11_900, visionScore: 28, summonerSpellOneId: 4,
      summonerSpellTwoId: 11, endItemIds: [3071, 3047, 3053, 0, 0, 0, 3364],
    },
  ],
  timelineAvailable: true,
  samples: [
    { timestampMs: 480_000, goldDifference: 100, csDifference: 4, xpDifference: 20,
      focalLevel: 8, compareLevel: 8, focalTotalGold: 3_100, focalCs: 55, focalXp: 2_420 },
    { timestampMs: 540_000, goldDifference: null, csDifference: null, xpDifference: null,
      focalLevel: 8, compareLevel: null, focalTotalGold: 3_700, focalCs: 65, focalXp: 2_850 },
    { timestampMs: 600_000, goldDifference: 510, csDifference: 13, xpDifference: 220,
      focalLevel: 9, compareLevel: 9, focalTotalGold: 4_710, focalCs: 78, focalXp: 3_720 },
  ],
  windows: [
    {
      id: "480000-600000", startMs: 480_000, endMs: 600_000,
      before: { timestampMs: 480_000, goldDifference: 100, csDifference: 4, xpDifference: 20,
        focalLevel: 8, compareLevel: 8, focalTotalGold: 3_100, focalCs: 55, focalXp: 2_420 },
      after: { timestampMs: 600_000, goldDifference: 510, csDifference: 13, xpDifference: 220,
        focalLevel: 9, compareLevel: 9, focalTotalGold: 4_710, focalCs: 78, focalXp: 3_720 },
      summary: "Garen extended his lead: CS +4 to +13, gold +100 to +510, and XP +20 to +220.",
    },
  ],
  suggestedWindows: [
    {
      id: "480000-600000", startMs: 480_000, endMs: 600_000,
      before: { timestampMs: 480_000, goldDifference: 100, csDifference: 4, xpDifference: 20,
        focalLevel: 8, compareLevel: 8, focalTotalGold: 3_100, focalCs: 55, focalXp: 2_420 },
      after: { timestampMs: 600_000, goldDifference: 510, csDifference: 13, xpDifference: 220,
        focalLevel: 9, compareLevel: 9, focalTotalGold: 4_710, focalCs: 78, focalXp: 3_720 },
      summary: "Garen extended his lead: CS +4 to +13, gold +100 to +510, and XP +20 to +220.",
    },
  ],
  events: [
    { timestampMs: 505_210, label: "Purchased item 3071", participantIds: [6], itemId: 3071, actorParticipantId: 6, targetParticipantId: null, assisterParticipantIds: [], assistersObserved: false },
    { timestampMs: 552_430, label: "Champion kill", participantIds: [1, 6, 7], itemId: null, actorParticipantId: 6, targetParticipantId: 1, assisterParticipantIds: [7], assistersObserved: true },
    { timestampMs: 589_775, label: "Dragon secured", participantIds: [7], itemId: null, actorParticipantId: 7, targetParticipantId: null, assisterParticipantIds: [], assistersObserved: false },
  ],
};

export const demoFixture = {
  matchId: "NA1_7000000001",
  focusParticipantId: 6,
  compareParticipantId: 1,
  invented: true,
};

const partialBefore = {
  timestampMs: 0, goldDifference: 0, csDifference: 0, xpDifference: null,
  focalLevel: 1, compareLevel: 1, focalTotalGold: 500, focalCs: 0, focalXp: 0,
};
const partialMiddle = {
  timestampMs: 120_000, goldDifference: null, csDifference: 1, xpDifference: null,
  focalLevel: 2, compareLevel: 2, focalTotalGold: 1_000, focalCs: 8, focalXp: 500,
};
const partialAfter = {
  timestampMs: 150_000, goldDifference: 400, csDifference: 2, xpDifference: null,
  focalLevel: 3, compareLevel: 2, focalTotalGold: 1_400, focalCs: 11, focalXp: 750,
};
const partialSuggestion = {
  id: "0-150000", startMs: 0, endMs: 150_000,
  before: partialBefore, after: partialAfter,
  summary: "Garen's comparison changed: CS 0 to +2 and gold 0 to +400.",
};

export const partialMetricWindowFixture = {
  ...developmentFixture,
  samples: [partialBefore, partialMiddle, partialAfter],
  windows: [
    {
      id: "0-120000", startMs: 0, endMs: 120_000,
      before: partialBefore, after: partialMiddle,
      summary: "Garen's comparison changed: CS 0 to +1.",
    },
    partialSuggestion,
    {
      id: "120000-150000", startMs: 120_000, endMs: 150_000,
      before: partialMiddle, after: partialAfter,
      summary: "Garen's comparison changed: CS +1 to +2.",
    },
  ],
  suggestedWindows: [partialSuggestion],
  events: [],
};
