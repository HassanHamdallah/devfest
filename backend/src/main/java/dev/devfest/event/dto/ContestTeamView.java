package dev.devfest.event.dto;

/**
 * What a team may know about the Problem Solving room: the contest link while its timer is running or paused, and
 * whether the organizer has released its clue. Never a count, a point, a penalty, a place or an eligibility.
 */
public record ContestTeamView(long roomId, String contestUrl, boolean released) {}
