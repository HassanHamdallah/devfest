package dev.devfest.event.web;

import dev.devfest.auth.AuthService;
import dev.devfest.event.dto.FinalTerminalBundleView;
import dev.devfest.event.service.EventFinalTerminalBundleService;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The final's terminal package, kept apart from every room's package: upload, summary, removal and a test download
 * (ADMIN), and the download of the built bundle by a MEMBER team whose final shows the case.
 */
@RestController
@RequestMapping("/api/v1/event")
public class EventFinalTerminalBundleController {

  private final EventFinalTerminalBundleService bundles;

  public EventFinalTerminalBundleController(EventFinalTerminalBundleService bundles) {
    this.bundles = bundles;
  }

  @PutMapping(value = "/admin/final/terminal-bundle", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  FinalTerminalBundleView upload(@RequestPart("file") MultipartFile file, @AuthenticationPrincipal AuthService.Principal principal)
    throws IOException {
    return bundles.upload(principal, file.getBytes());
  }

  @GetMapping("/admin/final/terminal-bundle")
  FinalTerminalBundleView view(@AuthenticationPrincipal AuthService.Principal principal) {
    return bundles.view(principal);
  }

  @DeleteMapping("/admin/final/terminal-bundle")
  FinalTerminalBundleView remove(@AuthenticationPrincipal AuthService.Principal principal) {
    return bundles.remove(principal);
  }

  /** The built bundle itself, for the admin's own test terminal. */
  @GetMapping("/admin/final/terminal-bundle/archive")
  ResponseEntity<byte[]> adminBundle(
    @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return TerminalBundleResponses.respond(bundles.adminBundle(principal, ifNoneMatch));
  }

  @GetMapping("/team/final/terminal-bundle")
  ResponseEntity<byte[]> teamBundle(
    @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
    @AuthenticationPrincipal AuthService.Principal principal
  ) {
    return TerminalBundleResponses.respond(bundles.teamBundle(principal, ifNoneMatch));
  }
}
