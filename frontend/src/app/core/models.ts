export type Language = "CPP" | "JAVA" | "PYTHON";
export interface User {
  id: number;
  email: string;
  name: string;
  role: "ADMIN" | "PARTICIPANT" | "MANAGER" | "MEMBER";
}
export interface Tokens {
  accessToken: string;
  refreshToken: string;
  user: User;
}
export interface Contest {
  title: string;
  startAt: string;
  endAt: string;
  serverNow: string;
}
export interface Example {
  input: string;
  output: string;
}
export interface Problem {
  id: number;
  version: number;
  title: string;
  statement: string;
  inputFormat: string;
  outputFormat: string;
  constraints: string;
  examples: Example[];
  languages: Language[];
  cpuSeconds: number;
  memoryKb: number;
}
export interface ProblemData {
  title: string;
  statement: string;
  inputFormat: string;
  outputFormat: string;
  constraints: string;
  examples: Example[];
  tests: Example[];
  languages: Language[];
  cpuSeconds: number;
  memoryKb: number;
}
export interface Draft {
  source: string;
  version: number;
  updatedAt: string | null;
}
export interface Submission {
  id: string;
  userId: number;
  problemId: number;
  problemVersion: number;
  kind: "RUN" | "SUBMIT";
  language: Language;
  runtime: string;
  receivedAt: string;
  state: string;
  generation: number;
  passed: number;
  total: number;
  source: string | null;
  stdout: string;
  stderr: string;
  compileOutput: string;
}
export interface Score {
  userId: number;
  name: string;
  solved: number;
  lastAccepted: string | null;
  rank: number;
}
export interface Participant {
  id: number;
  email: string;
  name: string;
  active: boolean;
}
export const templates: Record<Language, string> = {
  CPP: "#include <iostream>\nusing namespace std;\nint main() {\n    // Read stdin and write stdout\n    return 0;\n}\n",
  JAVA: "import java.util.*;\nclass Main {\n    public static void main(String[] args) {\n        Scanner in = new Scanner(System.in);\n    }\n}\n",
  PYTHON: "# Read stdin and write stdout\n",
};
export const languageNames: Record<Language, string> = {
  CPP: "C++ · GCC 14.1.0",
  JAVA: "Java · JDK 17.0.6",
  PYTHON: "Python · 3.13.2",
};
export const pending = (s: string) => s === "QUEUED" || s === "RUNNING";
