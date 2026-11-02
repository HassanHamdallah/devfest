package dev.devfest.event.repository;

/** A MANAGER or MEMBER account with the team it represents or the room it manages. */
public record EventAccountRecord(
  long id,
  String email,
  String name,
  String role,
  boolean active,
  Long teamId,
  Long roomId
) {
  public boolean isManager() {
    return EventAccountRepository.MANAGER.equals(role);
  }

  public boolean isMember() {
    return EventAccountRepository.MEMBER.equals(role);
  }
}
