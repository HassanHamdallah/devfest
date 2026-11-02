import { UserRole } from "../role-home";

export interface EventNavLink {
  path: string;
  label: string;
  /** Exact matching keeps "/event/admin" from lighting up on "/event/admin/teams". */
  exact: boolean;
}

export interface EventNavGroup {
  /** Visible group heading; null for the role's main links. */
  heading: string | null;
  links: EventNavLink[];
}

export interface EventNavRoom {
  id: number;
  name: string;
}

export const ROLE_LABELS: Record<UserRole, string> = {
  ADMIN: "Organizer",
  MANAGER: "Room manager",
  MEMBER: "Team representative",
  PARTICIPANT: "Participant",
};

export function eventHomePath(role: UserRole, managerRoomPath: string): string {
  if (role === "ADMIN") return "/event/admin";
  if (role === "MANAGER") return managerRoomPath;
  return "/event/team";
}

export function eventNavGroups(
  role: UserRole,
  managerRoomPath: string,
  adminRooms: readonly EventNavRoom[],
): EventNavGroup[] {
  if (role === "ADMIN") return adminGroups(adminRooms);
  if (role === "MANAGER")
    return [main([{ path: managerRoomPath, label: "My room", exact: false }])];
  return [
    main([
      { path: "/event/team", label: "Team home", exact: true },
      { path: "/event/team/final", label: "Final", exact: true },
    ]),
  ];
}

function adminGroups(rooms: readonly EventNavRoom[]): EventNavGroup[] {
  const groups = [
    main([
      { path: "/event/admin", label: "Control", exact: true },
      { path: "/event/admin/teams", label: "Teams & accounts", exact: true },
      { path: "/event/admin/content", label: "Content", exact: true },
      { path: "/event/admin/overrides", label: "Overrides", exact: true },
      { path: "/event/results", label: "Results", exact: true },
    ]),
  ];
  if (rooms.length === 0) return groups;
  const roomLinks = rooms.map((room) => ({
    path: `/event/room/${room.id}`,
    label: `${room.name} room`,
    exact: true,
  }));
  return [...groups, { heading: "Rooms", links: roomLinks }];
}

function main(links: EventNavLink[]): EventNavGroup {
  return { heading: null, links };
}
