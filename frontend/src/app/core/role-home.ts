import { Injectable, inject } from "@angular/core";
import { Api } from "./api";
import { EventApi } from "./event/event-api";
import { User } from "./models";

export type UserRole = User["role"];

export const EVENT_ROLES: readonly UserRole[] = ["ADMIN", "MANAGER", "MEMBER"];

export const isEventRole = (role: UserRole) => EVENT_ROLES.includes(role);

/** Where each role lands after login and on '' / unknown URLs (EVENT-CONTRACT §5). */
@Injectable({ providedIn: "root" })
export class RoleHome {
  private readonly api = inject(Api);
  private readonly eventApi = inject(EventApi);

  async url(): Promise<string> {
    switch (this.api.user()?.role) {
      case "ADMIN":
        return "/event/admin";
      case "MANAGER":
        return this.managerRoomUrl();
      case "MEMBER":
        return "/event/team";
      case "PARTICIPANT":
        return "/contest";
      default:
        return "/login";
    }
  }

  /** /event/room/<id>; plain /event/room (which explains "no room assigned" or offers a retry) otherwise. */
  async managerRoomUrl(): Promise<string> {
    try {
      const home = await this.eventApi.home();
      return home.roomId === null
        ? "/event/room"
        : `/event/room/${home.roomId}`;
    } catch {
      return "/event/room";
    }
  }
}
