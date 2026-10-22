import { Component, inject, signal, OnInit } from "@angular/core";
import { RouterLink } from "@angular/router";
import { FormsModule } from "@angular/forms";
import { DatePipe } from "@angular/common";
import { Api, message } from "../../core/api";
import { UiButton } from "../../shared/ui/ui-button/ui-button";
import { UiCard } from "../../shared/ui/ui-card/ui-card";
import { UiInput } from "../../shared/ui/ui-input/ui-input";
import { UiSelect } from "../../shared/ui/ui-select/ui-select";
import { Account, INV, Room, Settings, Team } from "./models";
@Component({
  imports: [
    RouterLink,
    FormsModule,
    DatePipe,
    UiButton,
    UiCard,
    UiInput,
    UiSelect,
  ],
  templateUrl: "./overview.html",
  styleUrl: "./investigation.css",
})
export class InvestigationOverview implements OnInit {
  api = inject(Api);
  rooms = signal<Room[]>([]);
  teams = signal<Team[]>([]);
  accounts = signal<Account[]>([]);
  settings = signal<Settings | null>(null);
  issues = signal<string[]>([]);
  error = signal("");
  success = signal("");
  busy = signal(false);
  events = signal<
    {
      id: number;
      actor_id: number | null;
      action: string;
      target: string;
      created_at: string;
    }[]
  >([]);
  health = signal("Checking");
  room = { name: "", archived: false };
  roomId: number | null = null;
  team = { name: "", roomId: "", representativeId: "", archived: false };
  teamId: number | null = null;
  account = { name: "", email: "", password: "", role: "MEMBER" };
  resetId = "";
  resetPassword = "";
  assignmentRoom = "";
  managerIds: number[] = [];
  roles = [
    { value: "MEMBER", label: "Member representative" },
    { value: "MANAGER", label: "Room manager" },
  ];
  get admin() {
    return this.api.user()?.role === "ADMIN";
  }
  get roomOptions() {
    return this.rooms().map((r) => ({ value: String(r.id), label: r.name }));
  }
  get representatives() {
    return this.accounts()
      .filter((a) => a.role === "MEMBER" && a.active)
      .map((a) => ({ value: String(a.id), label: a.name }));
  }
  get managers() {
    return this.accounts().filter((a) => a.role === "MANAGER" && a.active);
  }
  get accountOptions() {
    return this.accounts().map((a) => ({
      value: String(a.id),
      label: a.name + " · " + a.role,
    }));
  }
  ngOnInit() {
    void this.action(() => this.load());
  }
  async load() {
    const [rooms, teams, settings] = await Promise.all([
      this.api.request<Room[]>("GET", INV + "/rooms"),
      this.api.request<Team[]>("GET", INV + "/teams"),
      this.api.request<Settings>("GET", INV + "/settings"),
    ]);
    this.rooms.set(rooms);
    this.teams.set(teams);
    this.settings.set(settings);
    if (this.admin) {
      const [accounts, issues, events, health] = await Promise.all([
        this.api.request<Account[]>("GET", INV + "/accounts"),
        this.api.request<string[]>("GET", INV + "/preflight"),
        this.api.request<
          {
            id: number;
            actor_id: number | null;
            action: string;
            target: string;
            created_at: string;
          }[]
        >("GET", INV + "/audit"),
        this.api.request<{ status: string }>("GET", "/health"),
      ]);
      this.accounts.set(accounts);
      this.issues.set(issues);
      this.events.set(events);
      this.health.set(health.status);
    }
  }
  async action(work: () => Promise<unknown>, success = "") {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set("");
    this.success.set("");
    try {
      await work();
      this.success.set(success);
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.busy.set(false);
    }
  }
  saveRoom() {
    void this.action(async () => {
      await this.api.request(
        this.roomId ? "PUT" : "POST",
        INV + "/rooms" + (this.roomId ? "/" + this.roomId : ""),
        this.room,
      );
      this.roomId = null;
      this.room = { name: "", archived: false };
      await this.load();
    }, "Room saved.");
  }
  editRoom(r: Room) {
    this.roomId = r.id;
    this.room = { name: r.name, archived: r.archived };
  }
  saveTeam() {
    void this.action(async () => {
      await this.api.request(
        this.teamId ? "PUT" : "POST",
        INV + "/teams" + (this.teamId ? "/" + this.teamId : ""),
        {
          ...this.team,
          roomId: Number(this.team.roomId),
          representativeId: this.team.representativeId
            ? Number(this.team.representativeId)
            : null,
        },
      );
      this.teamId = null;
      this.team = {
        name: "",
        roomId: "",
        representativeId: "",
        archived: false,
      };
      await this.load();
    }, "Team saved.");
  }
  editTeam(t: Team) {
    this.teamId = t.id;
    this.team = {
      name: t.name,
      roomId: String(t.roomId),
      representativeId: t.representativeId ? String(t.representativeId) : "",
      archived: t.archived,
    };
  }
  createAccount() {
    void this.action(async () => {
      await this.api.request("POST", INV + "/accounts", this.account);
      this.account = { name: "", email: "", password: "", role: "MEMBER" };
      await this.load();
    }, "Account created.");
  }
  toggle(a: Account) {
    void this.action(async () => {
      await this.api.request("PATCH", INV + "/accounts/" + a.id, {
        active: !a.active,
      });
      await this.load();
    }, "Account updated.");
  }
  reset() {
    void this.action(async () => {
      await this.api.request(
        "POST",
        INV + "/accounts/" + this.resetId + "/password",
        { password: this.resetPassword },
      );
      this.resetPassword = "";
    }, "Password reset; previous sessions revoked.");
  }
  selectRoom() {
    this.managerIds = [
      ...(this.rooms().find((r) => r.id === Number(this.assignmentRoom))
        ?.managerIds || []),
    ];
  }
  checkManager(id: number, checked: boolean) {
    this.managerIds = checked
      ? [...this.managerIds, id]
      : this.managerIds.filter((x) => x !== id);
  }
  managerChanged(id: number, event: Event) {
    if (event.target instanceof HTMLInputElement)
      this.checkManager(id, event.target.checked);
  }
  assign() {
    void this.action(async () => {
      await this.api.request(
        "PUT",
        INV + "/rooms/" + this.assignmentRoom + "/managers",
        { managerIds: this.managerIds },
      );
      await this.load();
    }, "Assignments saved.");
  }
  confirmRules() {
    void this.action(async () => {
      await this.api.request("POST", INV + "/settings/confirm-rules", {});
      await this.load();
    }, "Rules confirmed.");
  }
  publish() {
    void this.action(async () => {
      await this.api.request("PUT", INV + "/settings/publication", {
        published: !this.settings()?.resultsPublished,
      });
      await this.load();
    }, "Publication updated.");
  }
  download() {
    void this.action(async () => {
      const data = await this.api.request<unknown>("GET", INV + "/export");
      const url = URL.createObjectURL(
        new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }),
      );
      const a = document.createElement("a");
      a.href = url;
      a.download = "devfest-investigation.json";
      a.click();
      URL.revokeObjectURL(url);
    });
  }
  roomName(id: number) {
    return this.rooms().find((r) => r.id === id)?.name || String(id);
  }
}
