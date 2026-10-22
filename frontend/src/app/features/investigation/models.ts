export interface Room {
  id: number;
  name: string;
  archived: boolean;
  managerIds: number[];
}
export interface Team {
  id: number;
  name: string;
  roomId: number;
  representativeId: number | null;
  archived: boolean;
}
export interface Account {
  id: number;
  name: string;
  email: string;
  role: string;
  active: boolean;
}
export interface Settings {
  rulesConfirmed: boolean;
  resultsPublished: boolean;
  rules: string;
}
export interface Challenge {
  id: number;
  roomId: number;
  title: string;
  instructions: string;
  evidenceLinks: string[];
  answerFormat: string;
  rubric: string;
  maxScore: number;
  kind: "ANSWER" | "FINAL";
  state: "DRAFT" | "OPEN" | "CLOSED";
  opensAt: string;
  closesAt: string;
  version: number;
  availability: string;
}
export interface Report {
  conclusion: string;
  reasoning: string;
  evidence: string;
}
export interface Draft {
  report: Report;
  version: number;
  updatedAt: string | null;
  locked: boolean;
}
export interface Receipt {
  id: string;
  teamId: number;
  challengeId: number;
  receivedAt: string;
}
export interface Submission extends Receipt {
  roomId: number;
  authorId: number;
  kind: string;
  report: Report;
  maxScore: number;
  challengeSnapshot: string;
  points: number | null;
  feedback: string | null;
  scoreVersion: number;
  reviewerId: number | null;
  scoredAt: string | null;
  latest: boolean;
}
export interface Result {
  teamId: number;
  team: string;
  roomId: number;
  total: number;
  pending: number;
  rank: number;
}
export const INV = "/investigation";
