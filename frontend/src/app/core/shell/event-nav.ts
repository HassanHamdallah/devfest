import { RoomCode } from "../event/event-models";
import { UserRole } from "../role-home";
import { NavIconName } from "./nav-icon";

export interface EventNavLink {
  path: string;
  label: string;
  /** Exact matching keeps "/event/admin" from lighting up on "/event/admin/teams". */
  exact: boolean;
  icon: NavIconName;
  /** Room links show the room colour next to the label. */
  roomCode?: RoomCode;
  /** Control shows the active alert count. */
  alerts?: boolean;
}

export interface EventNavGroup {
  /** Visible group heading; null for the role's main links. */
  heading: string | null;
  links: EventNavLink[];
}

export interface EventNavRoom {
  id: number;
  code: RoomCode;
  name: string;
}

export const ROLE_LABELS: Record<UserRole, string> = {
  ADMIN: "Organizer",
  MANAGER: "Room manager",
  MEMBER: "Team representative",
  PARTICIPANT: "Participant",
};

/** The admin's pages, in sidebar order; the quick jump lists the same entries. */
export const ADMIN_PAGES: readonly EventNavLink[] = [
  { path: "/event/admin", label: "Control", exact: true, icon: "control", alerts: true },
  { path: "/event/admin/teams", label: "Teams & accounts", exact: true, icon: "teams" },
  { path: "/event/admin/content", label: "Content", exact: true, icon: "content" },
  { path: "/event/admin/overrides", label: "Overrides", exact: true, icon: "overrides" },
  { path: "/event/results", label: "Results", exact: true, icon: "results" },
];

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
    return [
      { heading: null, links: [{ path: managerRoomPath, label: "My room", exact: false, icon: "room" }] },
    ];
  return [
    {
      heading: null,
      links: [
        { path: "/event/team", label: "Team home", exact: true, icon: "home" },
        { path: "/event/team/final", label: "Final", exact: true, icon: "final" },
      ],
    },
  ];
}

export function roomLink(room: EventNavRoom): EventNavLink {
  return {
    path: `/event/room/${room.id}`,
    label: `${room.name} room`,
    exact: true,
    icon: "room",
    roomCode: room.code,
  };
}

function adminGroups(rooms: readonly EventNavRoom[]): EventNavGroup[] {
  const groups: EventNavGroup[] = [{ heading: "Event", links: [...ADMIN_PAGES] }];
  if (rooms.length === 0) return groups;
  return [...groups, { heading: "Rooms", links: rooms.map(roomLink) }];
}
