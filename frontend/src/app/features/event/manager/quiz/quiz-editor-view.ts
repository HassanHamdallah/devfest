import { QuizQuestionRequest, QuizRepairRequest, QuizSection } from "../../../../core/event/event-models";
import { UiSelectOption } from "../../../../shared/ui/ui-select/ui-select";

/** Same rules as the server (QuizContentRules); the server has the final word. */
export const SECTION_OPTIONS: readonly UiSelectOption[] = [
  { value: "EASY", label: "Easy" },
  { value: "MEDIUM", label: "Medium" },
  { value: "ADVANCED", label: "Advanced" },
  { value: "LANDMARK", label: "Landmark" },
  { value: "RECENT", label: "Recent" },
];

export const SECTION_LABELS: Record<QuizSection, string> = {
  EASY: "Easy",
  MEDIUM: "Medium",
  ADVANCED: "Advanced",
  LANDMARK: "Landmark",
  RECENT: "Recent",
};

export function questionProblem(q: QuizQuestionRequest): string | null {
  if (!q.prompt) return "Enter the question.";
  if (q.options.length < 2 || q.options.length > 6) return "A question needs 2 to 6 options.";
  if (q.options.some((o) => !o)) return "Every option needs a text.";
  if (new Set(q.options).size !== q.options.length) return "Two options have the same text.";
  if (q.correctOption < 0 || q.correctOption >= q.options.length) return "Mark exactly one option as correct.";
  if (!Number.isInteger(q.points) || q.points < 1 || q.points > 10) return "Points must be between 1 and 10.";
  return null;
}

export function repairProblem(r: QuizRepairRequest): string | null {
  if (!r.title) return "Enter a title.";
  if (!Number.isInteger(r.points) || r.points < 1 || r.points > 100) return "Points must be between 1 and 100.";
  if (r.snippets.length < 4 || r.snippets.length > 6) return "A repair needs 4 to 6 snippets.";
  if (r.snippets.some((s) => !s.trim())) return "Every snippet needs code.";
  if (new Set(r.snippets).size !== r.snippets.length) return "Two snippets are identical, so their order could not be told apart.";
  return null;
}
