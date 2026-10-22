package com.soyoung.stocks.storage;

import static com.soyoung.stocks.domain.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class SupabaseClientTest {
  private HttpServer server;
  private SupabaseClient client;
  private final Queue<String> responses = new ConcurrentLinkedQueue<>();
  private final List<Capture> requests = Collections.synchronizedList(new ArrayList<>());
  private static final String A = "11111111-1111-1111-1111-111111111111";
  private static final String B = "22222222-2222-2222-2222-222222222222";

  static final class Capture {
    String path, query, method, body;
    Headers headers;

    Capture(HttpExchange exchange) throws Exception {
      path = exchange.getRequestURI().getPath();
      query = exchange.getRequestURI().getRawQuery();
      method = exchange.getRequestMethod();
      headers = exchange.getRequestHeaders();
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[1024];
      int n;
      while ((n = exchange.getRequestBody().read(buf)) != -1) out.write(buf, 0, n);
      body = new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try {
            Capture capture = new Capture(exchange);
            requests.add(capture);
            String text = responses.poll();
            if (text == null) text = "[]";
            int code = text.startsWith("HTTP403:") ? 403 : 200;
            if (code == 403) text = text.substring(8);
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
          } catch (Exception e) {
            throw new RuntimeException(e);
          } finally {
            exchange.close();
          }
        });
    server.start();
    client =
        new SupabaseClient(
            "http://127.0.0.1:" + server.getAddress().getPort(), "public-test-key", "u_yuzhengxin");
  }

  @AfterEach
  void cleanup() {
    client.close();
    server.stop(0);
  }

  private UserScope.Identity user(String id) {
    return new UserScope.Identity(id, "user-token-" + id, System.currentTimeMillis() / 1000 + 60);
  }

  @Test
  void pinsSchemaOwnerAndUserTokenForBothReadsAndWrites() {
    responses.add("[{\"id\":7}]");
    responses.add("[]");
    UserScope.run(
        user(A),
        () -> client.select("instruments", map("select", "id", "limit", 1, "owner_id", "eq." + B)));
    UserScope.run(
        user(B),
        () ->
            client.upsert(
                "settings",
                "owner_id,setting_key",
                map("owner_id", A, "setting_key", "active_instrument", "setting_value", "7")));
    Capture read = requests.get(0), write = requests.get(1);
    assertEquals("u_yuzhengxin", read.headers.getFirst("Accept-Profile"));
    assertEquals("u_yuzhengxin", write.headers.getFirst("Content-Profile"));
    assertEquals("Bearer user-token-" + A, read.headers.getFirst("Authorization"));
    assertEquals("public-test-key", read.headers.getFirst("apikey"));
    assertTrue(read.query.contains("owner_id=eq." + A));
    assertFalse(read.query.contains(B));
    assertEquals(B, node(read(write.body)).path("owner_id").asText());
    assertEquals("resolution=merge-duplicates,return=minimal", write.headers.getFirst("Prefer"));
  }

  @Test
  void refusesAnonymousExpiredAndUnknownTableWithoutAnyRequest() {
    assertThrows(
        IllegalStateException.class, () -> client.select("instruments", Collections.emptyMap()));
    UserScope.run(
        new UserScope.Identity(A, "expired", 1),
        () ->
            assertThrows(
                IllegalStateException.class,
                () -> client.select("instruments", Collections.emptyMap())));
    UserScope.run(
        user(A),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> client.select("other_users", Collections.emptyMap())));
    assertTrue(requests.isEmpty());
  }

  @Test
  void paginatesWhenGatewayReturnsFewerRowsThanRequested() {
    responses.add("[{\"id\":1}]");
    responses.add("[{\"id\":2}]");
    responses.add("[]");
    List<Map<String, Object>> rows =
        UserScope.with(user(A), () -> client.select("instruments", map("order", "id.asc")));
    assertEquals(2, rows.size());
    assertTrue(requests.get(1).query.contains("offset=1"));
    assertTrue(requests.get(2).query.contains("offset=2"));
  }

  @Test
  void reportsForbiddenWithoutLeakingTokenOrResponseBody() {
    responses.add("HTTP403:{\"secret\":\"sensitive upstream detail\"}");
    IllegalStateException error =
        UserScope.with(
            user(A),
            () ->
                assertThrows(
                    IllegalStateException.class,
                    () -> client.select("instruments", map("limit", 1))));
    assertTrue(error.getMessage().contains("403"));
    assertFalse(error.getMessage().contains("sensitive"));
    assertFalse(error.getMessage().contains("user-token"));
  }

  @Test
  void scopesAlwaysRestoreEvenAfterFailure() {
    assertNull(UserScope.current());
    UserScope.run(
        user(A),
        () -> {
          assertEquals(A, UserScope.owner());
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  UserScope.run(
                      user(B),
                      () -> {
                        throw new IllegalArgumentException();
                      }));
          assertEquals(A, UserScope.owner());
        });
    assertNull(UserScope.current());
  }

  @Test
  void authenticatesAgainstAuthServiceAndUsesVerifiedUserId() {
    long exp = System.currentTimeMillis() / 1000 + 300;
    String token =
        "header."
            + Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(write(map("sub", B, "exp", exp)).getBytes(StandardCharsets.UTF_8))
            + ".signature";
    responses.add(write(map("id", A)));
    UserScope.Identity identity = client.authenticate(token);
    assertEquals(A, identity.userId);
    assertEquals(exp, identity.expiresAt);
    assertEquals("/auth/v1/user", requests.get(0).path);
    assertEquals("Bearer " + token, requests.get(0).headers.getFirst("Authorization"));
    assertSame(identity, client.authenticate(token));
    assertEquals(1, requests.size());
  }
}
