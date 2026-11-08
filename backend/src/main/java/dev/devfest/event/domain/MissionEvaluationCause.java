package dev.devfest.event.domain;

/**
 * What evaluated a team in the AI room (rules §19.6): its last stage, or the round's Time's up. LAST_REPAIR only
 * exists for evaluations recorded before the mission room replaced the quiz.
 */
public enum MissionEvaluationCause {
  LAST_REPAIR,
  LAST_STAGE,
  TIME_UP,
}
