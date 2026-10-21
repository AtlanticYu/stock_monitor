package com.soyoung.stocks;

import static com.soyoung.stocks.domain.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.soyoung.stocks.domain.Models.*;
import com.soyoung.stocks.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MarketDataTest {
  @Test
  void parsesHistoricalColumnPayloadWithoutInventingVolume() {
    Instrument i = InstrumentService.identity("us-AAPL");
    Object data =
        map(
            "ResultCode",
            0,
            "Result",
            map(
                "newMarketData",
                map(
                    "keys",
                    Arrays.asList("time", "open", "high", "low", "close"),
                    "marketData",
                    "2024-03-11,100,110,90,105;2024-03-12,105,112,102,110")));
    Series s = MarketDataService.parse(node(data), i, "day");
    assertEquals(2, s.bars.size());
    assertNull(s.bars.get(0).volume);
    assertEquals("unknown", s.adjustment);
    assertTrue(s.bars.get(0).valid());
  }

  @Test
  void rejectsCaptchaInvalidCandlesAndUntrustedUrls() {
    Instrument i = InstrumentService.identity("hk-00700");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MarketDataService.parse(
                node(map("ResultCode", 0, "Result", map("isCaptchaEnabled", true))), i, "day"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MarketDataService.parse(
                node(
                    map(
                        "ResultCode",
                        0,
                        "Result",
                        map(
                            "marketData",
                            Collections.singletonList(
                                map(
                                    "time",
                                    "2024-01-01",
                                    "open",
                                    100,
                                    "high",
                                    90,
                                    "low",
                                    110,
                                    "close",
                                    105))))),
                i,
                "day"));
    for (String url :
        Arrays.asList(
            "http://finance.baidu.com/stock/us-aapl",
            "https://evil.example/stock/us-aapl",
            "https://finance.baidu.com:8443/stock/us-aapl",
            "https://finance.baidu.com/stock/us-aapl?url=http://127.0.0.1",
            "../../etc/passwd"))
      assertThrows(IllegalArgumentException.class, () -> InstrumentService.identity(url));
  }

  @Test
  void decodesBookAndTradesAndKeepsMissingFieldsNull() {
    Object data =
        map(
            "orderBook",
            map(
                "bids",
                Arrays.asList(Arrays.asList(100, 200), Arrays.asList(99, 300)),
                "asks",
                Collections.singletonList(Arrays.asList(101, 100))),
            "trades",
            Collections.singletonList(
                map("time", "1700000000000", "price", 100.5, "volume", 50, "direction", "buy")));
    List<Map<String, Object>> book = QuoteParser.orderBook(node(data));
    assertEquals(3, book.size());
    List<Object> trades = QuoteParser.trades(node(data));
    assertEquals(1, trades.size());
    assertEquals(1700000000L, ((Number) object(trades.get(0)).get("timestamp")).longValue());
    assertNull(object(trades.get(0)).get("amount"));
    assertEquals("buy", object(trades.get(0)).get("direction"));
  }
}
