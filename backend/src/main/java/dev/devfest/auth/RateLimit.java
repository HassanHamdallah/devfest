package dev.devfest.auth;

import static dev.devfest.api.Errors.fail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RateLimit {

  private final JdbcTemplate db;

  public RateLimit(JdbcTemplate db) {
    this.db = db;
  }

  public void check(String key, int limit) {
    check(key, limit, "Too many requests. Please wait a minute.");
  }

  public void check(String key, int limit, String message) {
    Integer count = db.queryForObject(
      "INSERT INTO rate_bucket(key,window_at,count) VALUES(?,now(),1) ON CONFLICT(key) DO UPDATE SET count=CASE WHEN rate_bucket.window_at<now()-interval '1 minute' THEN 1 ELSE rate_bucket.count+1 END,window_at=CASE WHEN rate_bucket.window_at<now()-interval '1 minute' THEN now() ELSE rate_bucket.window_at END RETURNING count",
      Integer.class,
      key
    );
    if (count > limit) throw fail(429, message);
  }
}
