import { ChangeDetectionStrategy, Component, inject } from "@angular/core";
import { EventApi } from "../../../core/event/event-api";
import { reloadOnRevisionChange } from "../../../core/event/revision-sync";
import { UiAlert } from "../../../shared/ui/ui-alert/ui-alert";
import { UiPageState } from "../../../shared/ui/ui-page-state/ui-page-state";
import { UiStatusChip } from "../../../shared/ui/ui-status-chip/ui-status-chip";
import { CaseForm } from "./content/case-form";
import { READ_ONLY_REASON } from "./content/content-view";
import { RoomContentForm } from "./content/room-content-form";
import { SuspectsSection } from "./content/suspects-section";
import { RemoteData } from "./shared/remote-data";

/** The case content from GET /admin/content: message, suspects, solution and each room's clue and recovery. */
@Component({
  imports: [
    CaseForm,
    RoomContentForm,
    SuspectsSection,
    UiAlert,
    UiPageState,
    UiStatusChip,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<header class="event-page-header">
      <h1>Event content</h1>
      @if (content.data(); as data) {
        <div class="event-page-header__meta">
          @if (data.readOnly) {
            <ui-status-chip variant="warning">Read-only</ui-status-chip>
          } @else {
            <ui-status-chip variant="neutral">Editable</ui-status-chip>
          }
        </div>
      }
    </header>
    <ui-page-state
      [loading]="content.loading()"
      loadingLabel="Loading event content…"
      [error]="content.blockingError()"
      (retry)="content.retry()"
    />
    @if (content.data(); as data) {
      <div class="event-stack">
        @if (data.readOnly) {
          <ui-alert variant="info"
            >{{ readOnlyReason }} Teams already play with this content, so
            nothing here can change any more.</ui-alert
          >
        }
        @if (content.staleError(); as error) {
          <ui-alert variant="warning"
            >Showing the last loaded content. {{ error }}</ui-alert
          >
        }
        <app-case-form [content]="data" (changed)="reload()" />
        <app-suspects-section [content]="data" (changed)="reload()" />
        <section aria-labelledby="room-content-heading">
          <h2 id="room-content-heading">Room clues and recovery</h2>
          <div class="event-grid">
            @for (room of data.rooms; track room.roomId) {
              <app-room-content-form
                [room]="room"
                [readOnly]="data.readOnly"
                (changed)="reload()"
              />
            }
          </div>
        </section>
      </div>
    }`,
  styles: `
    h2 {
      margin: 0 0 var(--space-3);
    }
  `,
})
export class EventAdminContentPage {
  private readonly eventApi = inject(EventApi);
  protected readonly content = new RemoteData(() =>
    this.eventApi.adminContent(),
  );
  protected readonly readOnlyReason = READ_ONLY_REASON;

  constructor() {
    reloadOnRevisionChange(() => this.reload());
    this.reload();
  }

  protected reload(): void {
    void this.content.load();
  }
}
