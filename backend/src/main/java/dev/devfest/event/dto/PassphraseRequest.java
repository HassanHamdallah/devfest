package dev.devfest.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** An evidence file's passphrase, as typed or pasted (surrounding whitespace and case do not matter). */
public record PassphraseRequest(@NotBlank @Size(max = 200) String passphrase) {}
