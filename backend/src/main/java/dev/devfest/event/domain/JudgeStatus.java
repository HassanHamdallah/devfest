package dev.devfest.event.domain;

/** A room manager's judgement of one team in one room; a missing judgement row means PENDING. */
public enum JudgeStatus {
  PENDING,
  PASS,
  FAIL
}
