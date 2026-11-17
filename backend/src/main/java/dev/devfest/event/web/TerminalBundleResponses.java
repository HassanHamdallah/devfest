package dev.devfest.event.web;

import dev.devfest.event.domain.TerminalBundleEtag;
import dev.devfest.event.dto.TerminalBundleDelivery;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The HTTP shape of a terminal package download (a room's or the final's): ETag, 304 and private revalidation. */
final class TerminalBundleResponses {

  private static final MediaType GZIP = MediaType.parseMediaType("application/gzip");

  private TerminalBundleResponses() {}

  /** 304 without a body when the caller's ETag is current; otherwise the bytes. Always private and revalidated. */
  static ResponseEntity<byte[]> respond(TerminalBundleDelivery delivery) {
    ResponseEntity.BodyBuilder response = ResponseEntity
      .status(delivery.notModified() ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
      .header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
      .eTag(TerminalBundleEtag.of(delivery.sha256()));
    if (delivery.notModified()) return response.build();
    return response.contentType(GZIP).contentLength(delivery.archive().length).body(delivery.archive());
  }
}
