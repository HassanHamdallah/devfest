package dev.devfest.event.web;

import dev.devfest.auth.AuthService;
import dev.devfest.event.domain.TerminalBundleEtag;
import dev.devfest.event.dto.TerminalBundleDelivery;
import dev.devfest.event.dto.TerminalClockRequest;
import dev.devfest.event.dto.TerminalFileList;
import dev.devfest.event.service.EventTerminalBundleService;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * A room's terminal server package: upload and removal (room MANAGER or ADMIN) and the download of the built
 * bundle (the assigned MEMBER team, or the room's manager for "Test terminal").
 */
@RestController
@RequestMapping("/api/v1/event")
public class EventTerminalBundleController {

  private static final MediaType GZIP = MediaType.parseMediaType("application/gzip");

  private final EventTerminalBundleService bundles;

  public EventTerminalBundleController(EventTerminalBundleService bundles) {
    this.bundles = bundles;
  }

  @PutMapping(value = "/rooms/{roomId}/terminal-bundle", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  TerminalFileList upload(
    @PathVariable long roomId,
    @RequestPart("file") MultipartFile file,
    @AuthenticationPrincipal AuthService.Principal principal
  ) throws IOException {
    return bundles.upload(principal, roomId, file.getBytes());
  }

  @PutMapping("/rooms/{roomId}/terminal-bundle/clock")
  TerminalFileList setClock(
    @PathVariable long roomId,
    @RequestBody TerminalClockRequest request,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return bundles.setClock(principal, roomId, request.clock());
  }

  @DeleteMapping("/rooms/{roomId}/terminal-bundle")
  TerminalFileList remove(@PathVariable long roomId, @AuthenticationPrincipal AuthService.Principal principal) {
    return bundles.remove(principal, roomId);
  }

  @GetMapping("/rooms/{roomId}/terminal-bundle")
  ResponseEntity<byte[]> managerBundle(
    @PathVariable long roomId,
    @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return respond(bundles.managerBundle(principal, roomId, ifNoneMatch));
  }

  @GetMapping("/team/rooms/{roomId}/terminal-bundle")
  ResponseEntity<byte[]> teamBundle(
    @PathVariable long roomId,
    @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return respond(bundles.teamBundle(principal, roomId, ifNoneMatch));
  }

  /** 304 without a body when the caller's ETag is current; otherwise the bytes. Always private and revalidated. */
  private static ResponseEntity<byte[]> respond(TerminalBundleDelivery delivery) {
    ResponseEntity.BodyBuilder response = ResponseEntity
      .status(delivery.notModified() ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
      .header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
      .eTag(TerminalBundleEtag.of(delivery.sha256()));
    if (delivery.notModified()) return response.build();
    return response.contentType(GZIP).contentLength(delivery.archive().length).body(delivery.archive());
  }
}
