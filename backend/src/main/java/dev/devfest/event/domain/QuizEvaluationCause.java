package dev.devfest.event.domain;

/** What evaluated a team in the AI room (rules §19.6): its last repair, or the round's Time's up. */
public enum QuizEvaluationCause {
  LAST_REPAIR,
  TIME_UP,
}
