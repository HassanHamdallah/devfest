package dev.devfest.event.dto;

/** The screen revision every event page polls; it changes whenever anything visible changes. */
public record RevisionView(long revision) {}
