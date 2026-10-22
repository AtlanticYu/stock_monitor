package com.soyoung.stocks;

import static com.soyoung.stocks.domain.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.soyoung.stocks.storage.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"stocks.market-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("supabase")
class SupabaseWebTest {
  @Autowired MockMvc mvc;
  @Autowired ApplicationContext context;
  @MockBean SupabaseClient client;

  @BeforeEach
  void setup() {
    when(client.configuration())
        .thenReturn(
            map(
                "mode",
                "supabase",
                "url",
                "https://example.invalid/supabase",
                "anonKey",
                "public-key",
                "schema",
                "u_yuzhengxin"));
    for (String token : Arrays.asList("alice", "bob")) {
      String id =
          token.equals("alice")
              ? "11111111-1111-1111-1111-111111111111"
              : "22222222-2222-2222-2222-222222222222";
      when(client.authenticate(token))
          .thenReturn(new UserScope.Identity(id, token, System.currentTimeMillis() / 1000 + 300));
    }
    when(client.select(eq("instruments"), anyMap()))
        .thenAnswer(
            invocation ->
                Collections.singletonList(
                    map(
                        "id",
                        7,
                        "market",
                        "us",
                        "exchange_name",
                        "US",
                        "code",
                        "IXIC",
                        "name",
                        UserScope.owner(),
                        "page_url",
                        "https://finance.baidu.com/index/us-IXIC",
                        "currency",
                        "USD",
                        "enabled",
                        true,
                        "sort_order",
                        0,
                        "name_source",
                        "auto",
                        "created_at",
                        1,
                        "updated_at",
                        1)));
    when(client.authenticate("invalid")).thenThrow(new IllegalArgumentException("invalid"));
  }

  @Test
  void startsWithoutJdbcAndExposesOnlyPublicConfiguration() throws Exception {
    assertTrue(context.getBeansOfType(JdbcTemplate.class).isEmpty());
    mvc.perform(get("/api/storage"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.schema").value("u_yuzhengxin"));
    mvc.perform(get("/login"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("数据空间账号")));
    mvc.perform(get("/assets/supabase-sdk.js")).andExpect(status().isOk());
    mvc.perform(
            post("/api/commands/list_instruments").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void usesVerifiedBearerIdentityAndCleansThreadScopeBetweenUsers() throws Exception {
    for (String token : Arrays.asList("alice", "bob")) {
      String owner =
          token.equals("alice")
              ? "11111111-1111-1111-1111-111111111111"
              : "22222222-2222-2222-2222-222222222222";
      mvc.perform(
              post("/api/commands/list_instruments")
                  .header("Authorization", "Bearer " + token)
                  .contentType("application/json")
                  .content("{}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].name").value(owner));
      assertNull(UserScope.current());
    }
    mvc.perform(get("/api/session").header("Authorization", "Bearer alice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("11111111-1111-1111-1111-111111111111"));
    mvc.perform(get("/api/session").header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized());
    assertNull(UserScope.current());
  }
}
