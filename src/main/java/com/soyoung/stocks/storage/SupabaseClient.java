package com.soyoung.stocks.storage;

import static com.soyoung.stocks.domain.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/** PostgREST transport. Never uses the anonymous key as a logged-in user's identity. */
@Component
@Profile("supabase")
public class SupabaseClient implements AutoCloseable {
  public static final List<String> TABLES =
      Collections.unmodifiableList(
          Arrays.asList(
              "instruments",
              "settings",
              "series",
              "quotes",
              "strategies",
              "backtests",
              "research_runs",
              "alert_profiles",
              "analysis_events",
              "backups"));
  private final String base, key, schema;
  private final OkHttpClient http =
      new OkHttpClient.Builder()
          .connectTimeout(8, TimeUnit.SECONDS)
          .readTimeout(20, TimeUnit.SECONDS)
          .callTimeout(30, TimeUnit.SECONDS)
          .followRedirects(false)
          .build();
  private final ConcurrentMap<String, UserScope.Identity> users = new ConcurrentHashMap<>();

  public SupabaseClient(
      @Value("${stocks.supabase.url}") String base,
      @Value("${stocks.supabase.anon-key}") String key,
      @Value("${stocks.supabase.schema}") String schema) {
    this.base = base.replaceAll("/+$", "");
    this.key = key;
    this.schema = schema;
  }

  public Map<String, Object> configuration() {
    return map("mode", "supabase", "url", base, "anonKey", key, "schema", schema);
  }

  public UserScope.Identity authenticate(String token) {
    long now = System.currentTimeMillis() / 1000;
    // Only cache the current token for an identity. Rotation invalidates an old token in this
    // cache.
    for (UserScope.Identity identity : users.values())
      if (identity.token.equals(token) && identity.expiresAt > now) return identity;
    Request request =
        new Request.Builder()
            .url(base + "/auth/v1/user")
            .header("apikey", key)
            .header("Authorization", "Bearer " + token)
            .build();
    try (Response response = http.newCall(request).execute()) {
      if (!response.isSuccessful() || response.body() == null)
        throw new IllegalArgumentException("Supabase 登录无效");
      JsonNode user = MAPPER.readTree(response.body().string());
      // Auth has verified the signature. Decode only the expiration, never trust an unverified sub.
      String[] parts = token.split("\\.");
      long exp = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1])).path("exp").asLong();
      if (exp <= now) throw new IllegalArgumentException("Supabase 登录已过期");
      UserScope.Identity identity = new UserScope.Identity(user.path("id").asText(), token, exp);
      users.put(identity.userId, identity);
      return identity;
    } catch (IOException | IndexOutOfBoundsException e) {
      throw new IllegalArgumentException("无法验证 Supabase 登录，请稍后重试");
    }
  }

  public void forUsers(Runnable action) {
    long now = System.currentTimeMillis() / 1000;
    users.values().removeIf(i -> i.expiresAt <= now);
    for (UserScope.Identity identity : users.values()) {
      try {
        UserScope.run(identity, action);
      } catch (RuntimeException ignored) {
        /* One user's failed refresh must not block other users. */
      }
    }
  }

  public UserScope.Identity latest(UserScope.Identity identity) {
    return identity == null ? null : users.getOrDefault(identity.userId, identity);
  }

  public List<Map<String, Object>> select(String table, Map<String, Object> query) {
    List<Map<String, Object>> result = new ArrayList<>();
    int offset = 0;
    // PostgREST installations may impose a response limit lower than our requested page size.
    while (true) {
      Map<String, Object> page = new LinkedHashMap<>(query);
      int maximum =
          page.containsKey("limit") ? ((Number) page.get("limit")).intValue() : Integer.MAX_VALUE;
      if (result.size() >= maximum) break;
      page.put("offset", offset);
      page.put("limit", Math.min(500, maximum - result.size()));
      List<Map<String, Object>> rows = request("GET", table, page, null, null);
      result.addAll(rows);
      offset += rows.size();
      if (rows.isEmpty()) break;
      if (result.size() > 50000) throw new IllegalStateException("Supabase 查询超过应用行数限制");
    }
    return result;
  }

  public List<Map<String, Object>> request(
      String method, String table, Map<String, Object> query, Object body, String preference) {
    if (!TABLES.contains(table)) throw new IllegalArgumentException("无效数据表");
    UserScope.Identity identity = requiredIdentity();
    HttpUrl.Builder url =
        Objects.requireNonNull(HttpUrl.parse(base + "/rest/v1/stocks_" + table)).newBuilder();
    if (query != null)
      query.forEach(
          (k, v) -> {
            if (!"owner_id".equals(k)) url.addQueryParameter(k, String.valueOf(v));
          });
    url.addQueryParameter("owner_id", "eq." + identity.userId);
    Object payload = body;
    if (body instanceof Map) {
      Map<String, Object> row = new LinkedHashMap<>(object(body));
      row.put("owner_id", identity.userId);
      payload = row;
    }
    RequestBody data =
        payload == null
            ? null
            : RequestBody.create(MediaType.parse("application/json"), write(payload));
    Request.Builder request =
        new Request.Builder()
            .url(url.build())
            .method(method, data)
            .header("apikey", key)
            .header("Authorization", "Bearer " + identity.token)
            .header("Accept-Profile", schema)
            .header("Content-Profile", schema)
            .header("Accept", "application/json");
    if (preference != null) request.header("Prefer", preference);
    try (Response response = http.newCall(request.build()).execute()) {
      if (!response.isSuccessful()) {
        if (response.code() == 409) throw new DataIntegrityViolationException("记录重复或数据不符合约束");
        String detail =
            response.code() == 401
                ? "登录已过期，请重新登录"
                : response.code() == 403 ? "网关或 RLS 拒绝访问；请检查公钥授权、登录状态和用户策略" : "数据空间请求失败";
        throw new IllegalStateException("Supabase HTTP " + response.code() + "：" + detail);
      }
      if (response.body() == null) return Collections.emptyList();
      okio.BufferedSource stream = response.body().source();
      stream.request(16 * 1024 * 1024 + 1L);
      if (stream.buffer().size() > 16 * 1024 * 1024)
        throw new IllegalStateException("数据响应超过 16 MB");
      String text = stream.readUtf8();
      if (text.trim().isEmpty()) return Collections.emptyList();
      JsonNode root = MAPPER.readTree(text);
      if (!root.isArray()) throw new IllegalStateException("Supabase 返回格式无效");
      List<Map<String, Object>> rows = new ArrayList<>();
      for (JsonNode row : root) rows.add(object(MAPPER.convertValue(row, Map.class)));
      return rows;
    } catch (IOException e) {
      throw new IllegalStateException("Supabase 网络请求失败，请稍后重试");
    }
  }

  private UserScope.Identity requiredIdentity() {
    UserScope.Identity current = UserScope.current();
    if (current == null) throw new IllegalStateException("请先登录数据空间");
    UserScope.Identity latest = latest(current);
    if (latest.expiresAt <= System.currentTimeMillis() / 1000)
      throw new IllegalStateException("Supabase 登录已过期");
    return latest;
  }

  @javax.annotation.PreDestroy
  public void close() {
    http.dispatcher().executorService().shutdownNow();
    http.connectionPool().evictAll();
  }

  public Map<String, Object> insert(String table, Map<String, Object> row) {
    List<Map<String, Object>> result =
        request("POST", table, Collections.emptyMap(), row, "return=representation");
    if (result.size() != 1) throw new IllegalStateException("Supabase 未返回保存结果");
    return result.get(0);
  }

  public void upsert(String table, String conflict, Map<String, Object> row) {
    request(
        "POST",
        table,
        map("on_conflict", conflict),
        row,
        "resolution=merge-duplicates,return=minimal");
  }

  public int delete(String table, Map<String, Object> query) {
    return request("DELETE", table, query, null, "return=representation").size();
  }
}
