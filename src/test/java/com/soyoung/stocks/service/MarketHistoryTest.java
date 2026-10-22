package com.soyoung.stocks.service;

import static com.soyoung.stocks.domain.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.soyoung.stocks.domain.Models.*;
import com.soyoung.stocks.repository.StockRepository;
import java.nio.charset.StandardCharsets;
import java.util.*;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;

class MarketHistoryTest {
  private final Instrument instrument =
      InstrumentService.identity("https://finance.baidu.com/stock/us-nvda");

  @Test
  void usesNumericKlineTypesAndKeepsIntradayParametersSeparate() {
    String[] periods = {"day", "week", "month", "year"};
    for (int n = 0; n < periods.length; n++) {
      HttpUrl url = MarketDataService.historyUrl(instrument, periods[n]);
      assertEquals(String.valueOf(n + 1), url.queryParameter("ktype"));
      assertEquals("NVDA", url.queryParameter("code"));
      assertEquals("quotation_kline_us", url.queryParameter("group"));
      assertEquals("string", url.queryParameter("pointType"));
    }
    HttpUrl minute = MarketDataService.historyUrl(instrument, "intraday");
    assertNull(minute.queryParameter("ktype"));
    assertEquals("min", minute.queryParameter("eprop"));
    assertEquals("minute", minute.queryParameter("chartType"));
    assertEquals("quotation_minute_us", minute.queryParameter("group"));
  }

  @Test
  void indexHistoryAndRealtimeSubscriptionsUseIndexFinanceType() {
    Instrument index = InstrumentService.identity("https://finance.baidu.com/index/us-IXIC");
    for (String period : Arrays.asList("intraday", "day", "week", "month", "year")) {
      HttpUrl url = MarketDataService.historyUrl(index, period);
      assertEquals("index", url.queryParameter("financeType"));
      assertEquals("IXIC", url.queryParameter("code"));
      assertEquals("us", url.queryParameter("stock_type"));
      assertEquals(
          "quotation_" + (period.equals("intraday") ? "minute" : "kline") + "_us",
          url.queryParameter("group"));
      assertEquals(
          "stock", MarketDataService.historyUrl(instrument, period).queryParameter("financeType"));
    }
    com.fasterxml.jackson.databind.JsonNode subscription =
        node(MarketDataService.subscription(index));
    assertEquals("subscribe", subscription.path("method").asText());
    assertEquals("index", subscription.path("items").get(0).path("financeType").asText());
    assertEquals("IXIC", subscription.path("items").get(0).path("code").asText());
    assertEquals(
        "stock",
        node(MarketDataService.subscription(instrument))
            .path("items")
            .get(0)
            .path("financeType")
            .asText());
  }

  @Test
  void parsesActualBaiduHistoricalColumnShape() throws Exception {
    byte[] fixture;
    try (java.io.InputStream stream =
        getClass().getResourceAsStream("/fixtures/baidu-nvda-history.json")) {
      assertNotNull(stream);
      fixture = org.springframework.util.StreamUtils.copyToByteArray(stream);
    }
    Series series = MarketDataService.parseResponse(200, fixture, instrument, "day");
    assertEquals(2, series.bars.size());
    assertEquals(216.470, series.bars.get(1).close, 0.000001);
    assertEquals(79609078.0, series.bars.get(1).volume);
    assertEquals(
        "2026-10-20",
        com.soyoung.stocks.domain.Models.date(series.bars.get(1).time, "us").toString());
  }

  @Test
  void explainsCaptchaOnHttp403AndHandlesNonJsonFailure() {
    byte[] body =
        write(map("ResultCode", 0, "Result", map("code", 403, "isCaptchaEnabled", true)))
            .getBytes(StandardCharsets.UTF_8);
    Exception captcha =
        assertThrows(
            IllegalArgumentException.class,
            () -> MarketDataService.parseResponse(403, body, instrument, "day"));
    assertTrue(captcha.getMessage().contains("验证码"));
    Exception forbidden =
        assertThrows(
            IllegalStateException.class,
            () ->
                MarketDataService.parseResponse(
                    403,
                    "<html>Forbidden</html>".getBytes(StandardCharsets.UTF_8),
                    instrument,
                    "day"));
    assertTrue(forbidden.getMessage().contains("HTTP 403"));
  }

  @Test
  void dailyFailureDoesNotPreventIntradayAndWarningSurvivesChartReload() throws Exception {
    StockRepository db = mock(StockRepository.class);
    EventHub hub = new EventHub();
    MarketDataService market =
        spy(new MarketDataService(db, hub, mock(AnalysisService.class), true));
    instrument.id = 12;
    try {
      doThrow(new IllegalArgumentException("数据源要求验证码")).when(market).fetch(instrument, "day");
      doReturn(new Series()).when(market).fetch(instrument, "intraday");
      market.refreshHistory(instrument);
      verify(market).fetch(instrument, "intraday");
      assertTrue(market.chartWarning(instrument, "day").contains("验证码"));
      assertTrue(market.chartWarning(instrument, "week").contains("验证码"));
      assertEquals("", market.chartWarning(instrument, "intraday"));
      assertEquals("error", market.health(instrument).get("state"));
      doReturn(new Series()).when(market).fetch(instrument, "day");
      market.refreshHistory(instrument);
      assertEquals("", market.chartWarning(instrument, "day"));
      assertNotEquals("error", market.health(instrument).get("state"));
    } finally {
      market.close();
    }
  }

  @Test
  void intradayFailureDoesNotMarkDailyChartAsFailed() throws Exception {
    StockRepository db = mock(StockRepository.class);
    Series cached = new Series();
    cached.fetchedAtMs = System.currentTimeMillis();
    when(db.series(instrument.id, "day", false)).thenReturn(cached);
    MarketDataService market =
        spy(new MarketDataService(db, new EventHub(), mock(AnalysisService.class), true));
    try {
      doReturn(cached).when(market).fetch(instrument, "day");
      doThrow(new IllegalArgumentException("分时请求失败")).when(market).fetch(instrument, "intraday");
      market.refreshHistory(instrument);
      assertEquals("", market.chartWarning(instrument, "day"));
      assertFalse(market.chartWarning(instrument, "intraday").isEmpty());
      assertEquals("partial", market.health(instrument).get("state"));
    } finally {
      market.close();
    }
  }
}
