package dev.devfest.judge;

import dev.devfest.contest.ProblemData;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

@Component
public class Judge0Client implements JudgeClient {

  private final HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build();
  private final ObjectMapper json;
  private final String url, key, header, host;
  private final Set<ProblemData.Language> verified =
    java.util.concurrent.ConcurrentHashMap.newKeySet();

  public Judge0Client(
    ObjectMapper json,
    @Value("${app.judge-url}") String url,
    @Value("${app.judge-key}") String key,
    @Value("${app.judge-auth-header}") String header,
    @Value("${app.judge-host}") String host
  ) {
    this.json = json;
    this.url = url.replaceAll("/+$", "");
    this.key = key;
    this.header = header;
    this.host = host;
    if (
      !url.isBlank() && !URI.create(url).getScheme().equals("https")
    ) throw new IllegalArgumentException("Judge0 must use HTTPS");
  }

  private JsonNode request(String path, Object body) {
    if (url.isBlank()) throw new IllegalStateException(
      "Judge0 is not configured"
    );
    try {
      var b = HttpRequest.newBuilder(URI.create(url + path))
        .timeout(Duration.ofSeconds(12))
        .header("Accept", "application/json");
      if (!key.isBlank()) b.header(header, key);
      if (!host.isBlank()) b.header("X-RapidAPI-Host", host);
      if (body != null) b.header("Content-Type", "application/json").POST(
        HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))
      );
      else b.GET();
      var response = boundedResponse(http, b.build());
      if (response.statusCode() / 100 != 2) throw new IllegalStateException(
        "Judge0 HTTP " + response.statusCode()
      );
      return json.readTree(response.body());
    } catch (Exception e) {
      throw new IllegalStateException("Execution service unavailable", e);
    }
  }

  // Bound the entire body, not only response headers: a stalled stream must release the worker.
  static HttpResponse<byte[]> boundedResponse(HttpClient client, HttpRequest request) throws Exception {
    var future = client.sendAsync(request,
      HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 1048576));
    try {
      return future.get(request.timeout().orElse(Duration.ofSeconds(12)).toMillis(),
        java.util.concurrent.TimeUnit.MILLISECONDS);
    } finally {
      if (!future.isDone()) future.cancel(true);
    }
  }

  public void verifyRuntime(ProblemData.Language language) {
    if (verified.contains(language)) return;
    var r = request("/languages/" + language.providerId, null);
    if (
      !language.runtime.equals(r.path("name").asText())
    ) throw new IllegalStateException("Execution runtime mismatch");
    verified.add(language);
  }

  public String dispatch(
    String source,
    int languageId,
    String input,
    double cpu,
    int memory
  ) {
    var b = new LinkedHashMap<String, Object>();
    b.put("source_code", encode(source));
    b.put("stdin", encode(input));
    b.put("language_id", languageId);
    b.put("cpu_time_limit", cpu);
    b.put("cpu_extra_time", 0.5);
    b.put("wall_time_limit", 10);
    b.put("memory_limit", memory);
    b.put("stack_limit", 65536);
    b.put("max_processes_and_or_threads", 64);
    b.put("max_file_size", 64);
    b.put("enable_network", false);
    b.put("number_of_runs", 1);
    b.put("redirect_stderr_to_stdout", false);
    var token = request("/submissions?base64_encoded=true&wait=false", b)
      .path("token")
      .asText();
    if (!token.matches("[a-zA-Z0-9-]{10,80}")) throw new IllegalStateException(
      "Missing execution token"
    );
    return token;
  }

  public Result poll(String token) {
    var r = request(
      "/submissions/" +
        token +
        "?base64_encoded=true&fields=status,stdout,stderr,compile_output,message,memory",
      null
    );
    return new Result(
      r.path("status").path("id").asInt(),
      decode(r, "stdout"),
      decode(r, "stderr"),
      decode(r, "compile_output"),
      r.path("message").asText(""),
      r.path("memory").isNumber() ? r.path("memory").asLong() : null
    );
  }

  private static String encode(String s) {
    return Base64.getEncoder().encodeToString(
      s.getBytes(StandardCharsets.UTF_8)
    );
  }

  private static String decode(JsonNode n, String k) {
    var v = n.get(k);
    if (v == null || v.isNull()) return "";
    byte[] bytes = Base64.getDecoder().decode(v.asText());
    if (bytes.length > 65536) throw new IllegalStateException(
      "Output limit exceeded"
    );
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
