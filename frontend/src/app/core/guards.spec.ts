import { TestBed } from "@angular/core/testing";
import { RedirectCommand, Router, UrlTree, provideRouter } from "@angular/router";
import { describe, expect, it } from "vitest";
import { Api } from "./api";
import { EventApi } from "./event/event-api";
import { loginGuard } from "./guards";
import { User } from "./models";
import { RoleHome } from "./role-home";

/** The login guard with the real RoleHome (the mapping the login page uses) and a fake session. */
function run(user: Pick<User, "role"> | null, roomId: number | null = 7, restored = user !== null) {
  const calls = { restore: 0 };
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      {
        provide: Api,
        useValue: {
          user: () => user,
          restore: async () => {
            calls.restore++;
            return restored;
          },
        },
      },
      { provide: EventApi, useValue: { home: async () => ({ roomId }) } },
    ],
  });
  const result = TestBed.runInInjectionContext(() => loginGuard({} as never, {} as never));
  return { result: Promise.resolve(result), calls, router: TestBed.inject(Router), roleHome: TestBed.inject(RoleHome) };
}

async function redirectOf(outcome: Promise<unknown>): Promise<{ url: string; replaceUrl: boolean | undefined }> {
  const value = await outcome;
  expect(value).toBeInstanceOf(RedirectCommand);
  const command = value as RedirectCommand;
  expect(command.redirectTo).toBeInstanceOf(UrlTree);
  return { url: command.redirectTo.toString(), replaceUrl: command.navigationBehaviorOptions?.replaceUrl };
}

describe("loginGuard", () => {
  it("sends a signed-in admin to the event control, replacing the /login history entry", async () => {
    const { result } = run({ role: "ADMIN" });
    expect(await redirectOf(result)).toEqual({ url: "/event/admin", replaceUrl: true });
  });

  it("sends a signed-in team member to the team home", async () => {
    const { result } = run({ role: "MEMBER" });
    expect(await redirectOf(result)).toEqual({ url: "/event/team", replaceUrl: true });
  });

  it("sends a signed-in room manager to their room, or to the room page when none is assigned", async () => {
    expect(await redirectOf(run({ role: "MANAGER" }, 7).result)).toEqual({ url: "/event/room/7", replaceUrl: true });
    TestBed.resetTestingModule();
    expect(await redirectOf(run({ role: "MANAGER" }, null).result)).toEqual({ url: "/event/room", replaceUrl: true });
  });

  it("goes exactly where RoleHome sends a user after signing in (no second mapping)", async () => {
    for (const role of ["ADMIN", "MEMBER", "MANAGER", "PARTICIPANT"] as const) {
      TestBed.resetTestingModule();
      const { result, roleHome } = run({ role });
      expect((await redirectOf(result)).url).toBe(await roleHome.url());
    }
  });

  it("shows the login form when signed out, after waiting for the same session restore as the other guards", async () => {
    const { result, calls } = run(null);
    expect(await result).toBe(true);
    expect(calls.restore).toBe(1);
  });

  it("shows the form when a stored session cannot be restored (expired or ended)", async () => {
    const { result } = run({ role: "ADMIN" }, 7, false);
    expect(await result).toBe(true);
  });
});
