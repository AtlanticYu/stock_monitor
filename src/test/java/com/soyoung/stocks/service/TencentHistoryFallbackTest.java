package com.soyoung.stocks.service;

import static com.soyoung.stocks.domain.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.soyoung.stocks.domain.Models.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TencentHistoryFallbackTest {
  private final Instrument instrument = InstrumentService.identity("us-NVDA");

  @Test
  void parsesActualTencentHistoryWithoutConfusingCloseAndHigh() throws Exception {
    JsonNode fixture;
    try (java.io.InputStream stream =
        getClass().getResourceAsStream("/fixtures/tencent-nvda-history.json")) {
      assertNotNull(stream);
      fixture = MAPPER.readTree(stream);
    }
    Series series = TencentHistoryFallback.parse(fixture, "usNVDA.OQ", instrument);
    assertEquals(2, series.bars.size());
    Bar last = series.bars.get(1);
    assertEquals(215.980, last.open, 0.000001);
    assertEquals(216.470, last.close, 0.000001);
    assertEquals(218.460, last.high, 0.000001);
    assertEquals(212.770, last.low, 0.000001);
    assertEquals(79609078.0, last.volume);
    assertEquals("unknown", series.adjustment);
    assertEquals("腾讯美股历史接口", series.source);
  }

  @Test
  void resolvesExchangeSuffixFromMetadataAndRejectsUnrelatedSymbol() {
    JsonNode data =
        node(
            map(
                "data",
                map("usNVDA", map("qt", map("usNVDA", Arrays.asList("delay", "英伟达", "NVDA.OQ"))))));
    assertEquals("usNVDA.OQ", TencentHistoryFallback.canonicalSymbol(data, "usNVDA", "NVDA"));
    JsonNode unrelated =
        node(
            map(
                "data",
                map(
                    "usNVDA",
                    map("qt", map("usNVDA", Arrays.asList("delay", "other", "MSFT.OQ"))))));
    assertEquals("usNVDA", TencentHistoryFallback.canonicalSymbol(unrelated, "usNVDA", "NVDA"));
  }

  @Test
  void preservesExplicitAdjustmentAndMissingVolume() {
    JsonNode data =
        node(
            map(
                "code",
                0,
                "data",
                map(
                    "usNVDA.OQ",
                    map(
                        "qfqday",
                        Collections.singletonList(
                            Arrays.asList(
                                "2026-10-20", "215.98", "216.47", "218.46", "212.77"))))));
    Series series = TencentHistoryFallback.parse(data, "usNVDA.OQ", instrument);
    assertEquals("qfq", series.adjustment);
    assertNull(series.bars.get(0).volume);
  }

  @Test
  void rejectsWrongInstrumentBusinessErrorsAndInvalidCandles() {
    assertThrows(
        IllegalArgumentException.class,
        () -> TencentHistoryFallback.parse(node(map("code", 0)), "usAAPL.OQ", instrument));
    assertThrows(
        IllegalArgumentException.class,
        () -> TencentHistoryFallback.parse(node(map("code", 1)), "usNVDA.OQ", instrument));
    JsonNode data =
        node(
            map(
                "code",
                0,
                "data",
                map(
                    "usNVDA.OQ",
                    map(
                        "day",
                        Collections.singletonList(
                            Arrays.asList(
                                "2026-10-20", "215.98", "216.47", "200", "212.77", "5"))))));
    assertThrows(
        IllegalArgumentException.class,
        () -> TencentHistoryFallback.parse(data, "usNVDA.OQ", instrument));
  }

  private JsonNode fixture(String symbol) throws Exception {
    try (java.io.InputStream stream =
        getClass().getResourceAsStream("/fixtures/tencent-" + symbol + "-history.json")) {
      assertNotNull(stream);
      return MAPPER.readTree(stream);
    }
  }

  @Test
  void mapsHongKongAndBothMainlandExchangesWithoutLosingLeadingZeros() {
    assertEquals(
        "hk01810", TencentHistoryFallback.baseSymbol(InstrumentService.identity("hk-01810")));
    assertEquals(
        "sh688825", TencentHistoryFallback.baseSymbol(InstrumentService.identity("sh-688825")));
    assertEquals(
        "sz000001", TencentHistoryFallback.baseSymbol(InstrumentService.identity("sz-000001")));
    assertEquals(
        "sh688825", TencentHistoryFallback.baseSymbol(InstrumentService.identity("ab-688825")));
    assertEquals(
        "sz000001", TencentHistoryFallback.baseSymbol(InstrumentService.identity("ab-000001")));
    assertFalse(TencentHistoryFallback.supports(InstrumentService.identity("ab-920001")));
    assertFalse(TencentHistoryFallback.supports(InstrumentService.identity("ab-830001")));
  }

  @Test
  void parsesActualXiaomiHistoryAndKeepsShareVolumeDespiteBoardLotSize() throws Exception {
    Instrument xiaomi = InstrumentService.identity("https://finance.baidu.com/stock/hk-01810");
    Series series = TencentHistoryFallback.parse(fixture("hk01810"), "hk01810", xiaomi);
    assertEquals(2, series.bars.size());
    Bar last = series.bars.get(1);
    assertEquals(27.280, last.open, 0.000001);
    assertEquals(26.740, last.close, 0.000001);
    assertEquals(27.760, last.high, 0.000001);
    assertEquals(26.680, last.low, 0.000001);
    assertEquals(98029733.0, last.volume);
    assertEquals("2026-10-21", com.soyoung.stocks.domain.Models.date(last.time, "hk").toString());
    assertEquals("腾讯港股历史接口", series.source);
  }

  @Test
  void normalizesMainlandLotsAndShareVolumeSeparately() throws Exception {
    Series sh =
        TencentHistoryFallback.parse(
            fixture("sh688825"), "sh688825", InstrumentService.identity("ab-688825"));
    Series sz =
        TencentHistoryFallback.parse(
            fixture("sz000001"), "sz000001", InstrumentService.identity("sz-000001"));
    assertEquals(202835983.0, sh.bars.get(1).volume);
    assertEquals(66541500.0, sz.bars.get(1).volume);
    assertEquals(41.12, sh.bars.get(1).close, 0.000001);
    assertEquals(11.41, sz.bars.get(1).close, 0.000001);
    assertEquals("qfq", sz.adjustment);
    assertEquals("unknown", sh.adjustment);
    assertEquals("腾讯A 股历史接口", sh.source);
  }

  @Test
  void leavesMainlandVolumeUnknownWhenSnapshotCannotConfirmItsUnit() {
    JsonNode root =
        node(
            map(
                "code",
                0,
                "data",
                map(
                    "sz000001",
                    map(
                        "qfqday",
                        Collections.singletonList(
                            Arrays.asList(
                                "2026-10-21", "11.44", "11.41", "11.51", "11.39", "100"))))));
    Series series =
        TencentHistoryFallback.parse(root, "sz000001", InstrumentService.identity("sz-000001"));
    assertNull(series.bars.get(0).volume);
    assertTrue(series.note.contains("成交量单位无法确认"));
  }

  @Test
  void rejectsCrossMarketSymbolsEvenWhenNumericCodesLookSimilar() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TencentHistoryFallback.parse(
                fixture("hk01810"), "us01810", InstrumentService.identity("hk-01810")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TencentHistoryFallback.parse(
                fixture("sz000001"), "sh000001", InstrumentService.identity("sz-000001")));
  }

  @Test
  void fallsBackToDayRowsWhenAdjustedArrayIsPresentButEmpty() throws Exception {
    JsonNode root = fixture("hk01810");
    ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("data").path("hk01810"))
        .putArray("qfqday");
    Series series =
        TencentHistoryFallback.parse(root, "hk01810", InstrumentService.identity("hk-01810"));
    assertEquals(2, series.bars.size());
    assertEquals("unknown", series.adjustment);
  }
}
