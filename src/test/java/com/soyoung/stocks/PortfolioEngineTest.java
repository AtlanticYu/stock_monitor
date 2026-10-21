package com.soyoung.stocks;

import static com.soyoung.stocks.domain.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.soyoung.stocks.domain.Models.*;
import com.soyoung.stocks.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PortfolioEngineTest {
  PortfolioEngine engine = new PortfolioEngine();

  PortfolioEngine.Asset asset(String code) {
    Instrument i = InstrumentService.identity(code);
    i.id = 1;
    Series s = new Series();
    s.adjustment = "raw";
    s.source = "fixture";
    for (int k = 0; k < 260; k++) {
      double c = 100 + k * .1 + 5 * Math.sin(k / 9.);
      s.bars.add(new Bar(1700000000L + k * 86400, c, c + 2, c - 2, c, 100000.));
    }
    return new PortfolioEngine.Asset(i, s, InstrumentService.defaultRules(i.market));
  }

  Map<String, Object> request() {
    return map(
        "initialCapital",
        100000.,
        "minSamples",
        120,
        "priceBasis",
        "raw",
        "factorConfig",
        map("targetHoldings", 1, "maxPositionWeight", .8, "rebalanceFrequency", "monthly"),
        "transactionCostConfig",
        map("maxParticipationRate", .1));
  }

  @Test
  void wholeShareDustDoesNotBlockFutureRebalancingAndConservesCash() {
    Map<String, Object> r =
        engine.run(Collections.singletonList(asset("us-AAPL")), null, request());
    assertTrue(number(r, "rebalanceCount", 0) > 5);
    List<?> trades = (List<?>) r.get("trades");
    assertTrue(trades.size() > 5, "integer-share remainder must not block future orders");
    assertEquals(
        number(r, "endingEquity", 0),
        number(r, "endingCash", 0) + number(r, "endingPositionValue", 0),
        1e-7);
    for (Object t : trades) {
      assertTrue(number(object(t), "cash", -1) >= 0);
      assertEquals(0, number(object(t), "quantity", 0) % 1, 1e-8);
    }
    assertTrue(number(r, "totalFees", 0) > 0);
  }

  @Test
  void quarterlyRebalanceIsSupportedByTheWebForm() {
    Map<String, Object> req = request();
    object(req.get("factorConfig"));
    Map<String, Object> factor = object(req.get("factorConfig"));
    factor.put("rebalanceFrequency", "quarterly");
    req.put("factorConfig", factor);
    Map<String, Object> result = engine.run(Collections.singletonList(asset("us-AAPL")), null, req);
    assertEquals(3, number(result, "rebalanceCount", 0));
  }

  @Test
  void mixedCurrenciesAndWrongPriceBasisAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> engine.run(Arrays.asList(asset("us-AAPL"), asset("hk-00700")), null, request()));
    Map<String, Object> r = request();
    r.put("priceBasis", "qfq");
    assertThrows(
        IllegalArgumentException.class,
        () -> engine.run(Collections.singletonList(asset("us-AAPL")), null, r));
  }

  @Test
  void futureVolumeDoesNotAffectEarlierOpeningOrders() {
    PortfolioEngine.Asset a = asset("us-AAPL"), b = asset("us-AAPL");
    a.series.bars.get(200).volume = 1.;
    b.series.bars.get(200).volume = 1e12;
    Map<String, Object> ra = engine.run(Collections.singletonList(a), null, request()),
        rb = engine.run(Collections.singletonList(b), null, request());
    List<?> ta = (List<?>) ra.get("trades"), tb = (List<?>) rb.get("trades");
    long time = a.series.bars.get(200).time;
    List<Object> earlyA = new ArrayList<>(), earlyB = new ArrayList<>();
    for (Object t : ta) if (number(object(t), "time", 0) <= time) earlyA.add(t);
    for (Object t : tb) if (number(object(t), "time", 0) <= time) earlyB.add(t);
    assertEquals(write(earlyA), write(earlyB));
  }
}
