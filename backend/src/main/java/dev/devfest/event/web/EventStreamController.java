package dev.devfest.event.web;

import dev.devfest.auth.AuthService;
import dev.devfest.event.dto.StreamTicketView;
import dev.devfest.event.service.EventStreamRegistry;
import dev.devfest.event.service.StreamTicketService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live revision push (Server-Sent Events). A signed-in page asks for a ticket with its bearer token, then opens
 * the stream with that ticket, because EventSource cannot send an Authorization header.
 */
@RestController
@RequestMapping("/api/v1/event")
public class EventStreamController {

  private final StreamTicketService tickets;
  private final EventStreamRegistry streams;

  public EventStreamController(StreamTicketService tickets, EventStreamRegistry streams) {
    this.tickets = tickets;
    this.streams = streams;
  }

  @PostMapping("/stream-ticket")
  StreamTicketView ticket(@AuthenticationPrincipal AuthService.Principal principal) {
    return tickets.issue(principal);
  }

  /** Public in SecurityConfig: the ticket is the credential. X-Accel-Buffering stops nginx from buffering. */
  @GetMapping("/stream")
  ResponseEntity<SseEmitter> stream(@RequestParam(required = false) String ticket) {
    SseEmitter emitter = streams.open(tickets.consume(ticket));
    return ResponseEntity.ok()
      .header(HttpHeaders.CACHE_CONTROL, "no-cache")
      .header("X-Accel-Buffering", "no")
      .body(emitter);
  }
}
