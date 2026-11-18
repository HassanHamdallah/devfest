package dev.devfest.event.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The room's phone number answer: up to 40 characters; empty clears it. */
public record PhoneNumberRequest(@NotNull @Size(max = 40) String phoneNumber) {}
