package com.soyoung.stocks;

import static com.soyoung.stocks.domain.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.soyoung.stocks.domain.Models.*;
import com.soyoung.stocks.repository.StockRepository;
import com.soyoung.stocks.service.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:stocks;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "stocks.market-enabled=false",
      "stocks.archive-dir=${java.io.tmpdir}/stocks-platform-test-archives",
      "spring.security.user.password=integration-secret"
    })
@AutoConfigureMockMvc
@Transactional
class WebIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired StockRepository db;
  @Autowired CommandService commands;
  @Autowired ArchiveService archives;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

  @Test
  void requiresLoginAndCsrf() throws Exception {
    mvc.perform(get("/api/session")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/session").with(httpBasic("admin", "integration-secret")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.csrfToken").isString());
    mvc.perform(post("/api/commands/list_instruments").with(user("admin")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/commands/list_instruments")
                .with(user("admin"))
                .with(csrf())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void stringCommandsAreValidJsonAndLoginIsContextAware() throws Exception {
    mvc.perform(
            post("/api/commands/database_path")
                .with(user("admin"))
                .with(csrf())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(jsonPath("$").value("MySQL · 服务端持久化"));
    mvc.perform(
            post("/api/commands/check_database_integrity")
                .with(user("admin"))
                .with(csrf())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").value("ok"));
    mvc.perform(get("/login"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("行情工作台")));
  }

  @Test
  void importsRealBarsRunsBacktestAndPersistsResults() throws Exception {
    Instrument i =
        (Instrument) commands.execute("add_instrument", map("pageUrl", "us-AAPL", "name", "Apple"));
    Series s = fixture();
    mvc.perform(
            post("/api/instruments/" + i.id + "/series")
                .with(user("admin"))
                .with(csrf())
                .contentType("application/json")
                .content(write(s)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.imported").value(260));
    mvc.perform(
            post("/api/commands/run_backtest")
                .with(user("admin"))
                .with(csrf())
                .contentType("application/json")
                .content(write(map("request", map("instrumentId", i.id, "strategy", "ma")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dataQuality").value("valid"))
        .andExpect(jsonPath("$.id").isNumber());
    assertEquals(1, db.backtests(i.id).size());
    assertEquals(260, db.series(i.id, "day", false).bars.size());
    assertEquals(
        "America/New_York",
        object(commands.execute("load_chart", map("instrumentId", i.id, "period", "day")))
            .get("marketTimezone"));
    assertNotNull(commands.execute("load_realtime_analysis", map("instrumentId", i.id)));
  }

  @Test
  void invalidBatchPreservesOldDataAndAdjustmentChangeReplacesHistory() {
    Instrument i = (Instrument) commands.execute("add_instrument", map("pageUrl", "us-MSFT"));
    Series s = fixture();
    db.saveSeries(i, s);
    Series revised = fixture();
    revised.bars = new ArrayList<>(revised.bars.subList(200, 260));
    revised.adjustment = "qfq";
    revised.fetchedAtMs = s.fetchedAtMs + 1;
    db.saveSeries(i, revised);
    assertEquals(60, db.series(i.id, "day", false).bars.size());
    Series bad = fixture();
    bad.bars.get(0).high = 0;
    assertThrows(IllegalArgumentException.class, () -> db.saveSeries(i, bad));
    assertEquals(60, db.series(i.id, "day", false).bars.size());
  }

  @Test
  void logicalArchiveRoundTripAndMalformedArchiveRejected() {
    Instrument i = (Instrument) commands.execute("add_instrument", map("pageUrl", "us-IBM"));
    db.saveSeries(i, fixture());
    Map<String, Object> archive = archives.export();
    db.deleteInstrument(i.id);
    archives.restore(archive);
    assertEquals("IBM", db.instrument(i.id).code);
    assertEquals(260, db.series(i.id, "day", false).bars.size());
    Map<String, Object> malformed =
        map(
            "format",
            "stocks-monitor-platform",
            "version",
            1,
            "tables",
            map("instruments", Collections.emptyList()));
    assertThrows(IllegalArgumentException.class, () -> archives.restore(malformed));
    assertEquals("IBM", db.instrument(i.id).code);
  }

  @Test
  void retentionUsesMillisecondsForQuotesAndSecondsForBars() {
    Instrument recent = (Instrument) commands.execute("add_instrument", map("pageUrl", "us-FRESH")),
        old = (Instrument) commands.execute("add_instrument", map("pageUrl", "us-OLD"));
    db.saveQuote(recent.id, map("instrumentId", recent.id, "price", 100));
    db.saveQuote(old.id, map("instrumentId", old.id, "price", 100));
    jdbc.update(
        "UPDATE quotes SET received_at=? WHERE instrument_id=?",
        System.currentTimeMillis() - 2 * 86400000L,
        old.id);
    db.saveSeries(recent, fixture());
    db.cleanup(1);
    assertNotNull(db.quote(recent.id));
    assertNull(db.quote(old.id));
    assertNull(db.series(recent.id, "day", false));
  }

  static Series fixture() {
    Series s = new Series();
    s.period = "day";
    s.adjustment = "raw";
    s.source = "test-fixture";
    s.fetchedAtMs = System.currentTimeMillis();
    for (int k = 0; k < 260; k++) {
      double c = 90 + k * .1 + Math.sin(k / 4.7) * 5;
      s.bars.add(new Bar(1700000000L + k * 86400, c, c + 2, c - 2, c, 10000.));
    }
    return s;
  }
}
