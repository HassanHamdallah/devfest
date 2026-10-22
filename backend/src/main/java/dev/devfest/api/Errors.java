package dev.devfest.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

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
