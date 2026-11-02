package dev.devfest.api;

import dev.devfest.event.domain.EventRuleException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class Errors {

  public static class ApiException extends RuntimeException {

    public final int status;

    public ApiException(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  public static ApiException fail(int status, String message) {
    return new ApiException(status, message);
  }

  @ExceptionHandler(ApiException.class)
  ProblemDetail api(ApiException e) {
    return ProblemDetail.forStatusAndDetail(
      HttpStatusCode.valueOf(e.status),
      e.getMessage()
    );
  }

  @ExceptionHandler(dev.devfest.auth.SessionEndedException.class)
  ProblemDetail sessionEnded(dev.devfest.auth.SessionEndedException e) {
    var p = api(e);
    p.setProperty("reason", e.reason.name());
    return p;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail validation(MethodArgumentNotValidException e) {
    var p = ProblemDetail.forStatusAndDetail(
      HttpStatus.BAD_REQUEST,
      "Please check the form fields."
    );
    var fields = new java.util.TreeMap<String, String>();
    e.getBindingResult()
      .getFieldErrors()
      .forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
    p.setProperty("errors", fields);
    return p;
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
  })
  ProblemDetail invalid(Exception e) {
    return ProblemDetail.forStatusAndDetail(
      HttpStatus.BAD_REQUEST,
      "Invalid request."
    );
  }

  @ExceptionHandler(EventRuleException.class)
  ProblemDetail eventRule(EventRuleException e) {
    return ProblemDetail.forStatusAndDetail(
      HttpStatus.BAD_REQUEST,
      e.getMessage()
    );
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  ProblemDetail missingHeader(MissingRequestHeaderException e) {
    return ProblemDetail.forStatusAndDetail(
      HttpStatus.BAD_REQUEST,
      "Missing required header: " + e.getHeaderName()
    );
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ProblemDetail missingParameter(MissingServletRequestParameterException e) {
    return ProblemDetail.forStatusAndDetail(
      HttpStatus.BAD_REQUEST,
      "Missing required parameter: " + e.getParameterName()
    );
  }

  @ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
  ProblemDetail notFound(Exception e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Not found.");
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ProblemDetail methodNotAllowed(HttpRequestMethodNotSupportedException e) {
    return ProblemDetail.forStatusAndDetail(
      HttpStatus.METHOD_NOT_ALLOWED,
      "Method not allowed."
    );
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ProblemDetail conflict(Exception e) {
    return ProblemDetail.forStatusAndDetail(
      HttpStatus.CONFLICT,
      "This change conflicts with an existing record."
    );
  }

  @ExceptionHandler(AccessDeniedException.class)
  ProblemDetail denied(Exception e) {
    return ProblemDetail.forStatusAndDetail(
      HttpStatus.FORBIDDEN,
      "Access denied."
    );
  }

  /** A live event stream whose browser went away: nothing can be written back and nothing went wrong. */
  @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestNotUsableException.class)
  void clientGone() {
    // Intentionally empty; the stream registry already dropped the stream.
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception e, jakarta.servlet.http.HttpServletRequest request) {
    String incident=java.util.UUID.randomUUID().toString();
    org.slf4j.LoggerFactory.getLogger(Errors.class).error(
      "Request failed incident={} method={} path={} exception={}",
      incident, request.getMethod(), request.getRequestURI(), e.getClass().getSimpleName()
    );
    return ProblemDetail.forStatusAndDetail(
      HttpStatus.INTERNAL_SERVER_ERROR,
      "The request could not be completed. Please retry. Incident: " + incident
    );
  }
}
