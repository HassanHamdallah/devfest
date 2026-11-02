package dev.devfest.event.dto;

/** A setup problem on the admin board; {@code link} is the frontend route where it is fixed. */
public record AdminAlert(String code, String message, String link) {}
