package dev.devfest.event.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The owners a package introduces. "root" is uid/gid 0; every other owner name gets a uid from 2000 in name
 * order, every other group name (and every owner's own group) a gid from 2000 in name order. The numbers depend
 * only on the names, so the same package always installs the same ids.
 */
public final class TerminalAccounts {

  public static final int FIRST_ID = 2000;
  public static final String ROOT = "root";
  /** POSIX portable user and group names, lower case. */
  public static final Pattern VALID_NAME = Pattern.compile("[a-z_][a-z0-9_-]{0,31}");

  public record User(String name, int uid, int gid) {}

  public record Group(String name, int gid) {}

  private final List<User> users;
  private final List<Group> groups;
  private final Map<String, Integer> uids = new TreeMap<>();
  private final Map<String, Integer> gids = new TreeMap<>();

  private TerminalAccounts(List<User> users, List<Group> groups) {
    this.users = users;
    this.groups = groups;
    uids.put(ROOT, 0);
    gids.put(ROOT, 0);
    users.forEach(user -> uids.put(user.name(), user.uid()));
    groups.forEach(group -> gids.put(group.name(), group.gid()));
  }

  /** @param owners owner names used by the package; @param groupNames group names used by the package */
  public static TerminalAccounts of(Collection<String> owners, Collection<String> groupNames) {
    TreeSet<String> userNames = new TreeSet<>(owners);
    userNames.remove(ROOT);
    TreeSet<String> allGroups = new TreeSet<>(groupNames);
    allGroups.addAll(userNames);
    allGroups.remove(ROOT);
    Map<String, Integer> gid = new TreeMap<>();
    int next = FIRST_ID;
    for (String group : allGroups) gid.put(group, next++);
    List<Group> groups = allGroups.stream().map(name -> new Group(name, gid.get(name))).toList();
    next = FIRST_ID;
    List<User> users = new java.util.ArrayList<>();
    for (String name : userNames) users.add(new User(name, next++, gid.get(name)));
    return new TerminalAccounts(List.copyOf(users), groups);
  }

  public List<User> users() {
    return users;
  }

  public List<Group> groups() {
    return groups;
  }

  public int uid(String owner) {
    return uids.get(owner);
  }

  public int gid(String group) {
    return gids.get(group);
  }

  /** Lines to append to /etc/passwd (empty when the package adds no owner). */
  public String passwdText() {
    StringBuilder text = new StringBuilder();
    for (User user : users) {
      text.append(user.name()).append(":x:").append(user.uid()).append(':').append(user.gid());
      text.append(':').append(user.name()).append(":/:/bin/sh\n");
    }
    return text.toString();
  }

  /** Lines to append to /etc/group (empty when the package adds no group). */
  public String groupText() {
    StringBuilder text = new StringBuilder();
    for (Group group : groups) text.append(group.name()).append(":x:").append(group.gid()).append(":\n");
    return text.toString();
  }

  /** The owner names for display and audit: "rami,tech9" (empty when only root owns files). */
  public String ownersText() {
    return String.join(",", users.stream().map(User::name).toList());
  }
}
