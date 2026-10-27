package dev.devfest.event.dto;

/** A single-use ticket that opens one live event stream (EventSource cannot send the Authorization header). */
public record StreamTicketView(String ticket, long expiresInSeconds) {}
