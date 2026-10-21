package com.soyoung.stocks;

import static com.soyoung.stocks.domain.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.soyoung.stocks.domain.Models.*;
import com.soyoung.stocks.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BacktestParityTest {
  @Test
  void matchesEightRustStrategyAndDateCases() throws Exception {
    JsonNode f = MAPPER.readTree(getClass().getResourceAsStream("/fixtures/rust-backtests.json"));
    List<Bar> bars = Arrays.asList(MAPPER.treeToValue(f.get("bars"), Bar[].class));
    for (JsonNode c : f.get("cases")) {
      BacktestRequest req = MAPPER.treeToValue(c.get("request"), BacktestRequest.class);
      JsonNode actual =
          MAPPER.valueToTree(
              new BacktestEngine().run(bars, InstrumentService.defaultRules("us"), req, "fixture"));
      JsonNode expected = c.get("result");
      for (String key :
          Arrays.asList(
              "totalReturn",
              "annualizedReturn",
              "maxDrawdown",
              "winRate",
              "profitLossRatio",
              "profitFactor",
              "endingCash",
              "endingEquity",
              "endingPositionValue",
              "benchmarkReturn",
              "sampleCount",
              "warmupSampleCount",
              "tradeCount",
              "completedTradeCount",
              "signalCount",
              "buySignalCount",
              "sellSignalCount",
              "executedBuyCount",
              "executedSellCount")) {
        JsonNode e = expected.get(key), a = actual.get(key);
        assertNotNull(e, key);
        assertNotNull(a, key);
        if (e.isNumber())
          assertEquals(
              e.asDouble(),
              a.asDouble(),
              1e-8 * Math.max(1, Math.abs(e.asDouble())),
              req.strategy + " " + key + " " + req.startDate);
        else assertEquals(e, a, key);
      }
      JsonNode ec = expected.path("equityCurve"), ac = actual.path("equityCurve");
      assertEquals(ec.size(), ac.size());
      for (int k = 0; k < ec.size(); k++)
        assertEquals(
            ec.get(k).path("value").asDouble(),
            ac.get(k).path("value").asDouble(),
            1e-6,
            req.strategy + " curve " + k);
    }
  }

  @Test
  void rejectsBadParametersAndDates() {
    BacktestEngine e = new BacktestEngine();
    BacktestRequest r = new BacktestRequest();
    r.parameters = map("fastPeriod", 20, "slowPeriod", 5);
    assertThrows(IllegalArgumentException.class, () -> e.validate(r));
    r.parameters.clear();
    r.startDate = "2026-12-01";
    r.endDate = "2026-01-01";
    assertThrows(IllegalArgumentException.class, () -> e.validate(r));
  }
}
