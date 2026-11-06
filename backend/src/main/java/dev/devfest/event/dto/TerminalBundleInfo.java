package dev.devfest.event.dto;

/**
 * What a terminal needs to know about its room's server package before it downloads it: the digest (also the
 * ETag of the download), the size on the wire, the lines to append to /etc/passwd and /etc/group, and the terminal
 * clock the setup sets (null = real time). Nothing else about the package or its uploader reaches a team.
 */
public record TerminalBundleInfo(String sha256, int archiveBytes, String etcPasswd, String etcGroup, java.time.Instant clock) {}
