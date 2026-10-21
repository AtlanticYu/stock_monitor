package com.soyoung.stocks.service;

import static com.soyoung.stocks.domain.Json.*;
import static com.soyoung.stocks.service.IndicatorEngine.*;

import com.soyoung.stocks.domain.Models;
import com.soyoung.stocks.domain.Models.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Version 2 matches the desktop's long-only single-stock execution model. */
@Service
public class BacktestEngine {
  public void validate(BacktestRequest req) {
    if (!Double.isFinite(req.initialCapital)
        || req.initialCapital <= 0
        || !Double.isFinite(req.maxPosition)
        || req.maxPosition <= 0
        || req.maxPosition > 1) throw new IllegalArgumentException("资金必须大于0，仓位在 (0,1] 内");
    LocalDate start = req.startDate == null ? null : LocalDate.parse(req.startDate),
        end = req.endDate == null ? null : LocalDate.parse(req.endDate);
    if (start != null && end != null && start.isAfter(end))
      throw new IllegalArgumentException("开始日期不能晚于结束日期");
    for (Double rate : Arrays.asList(req.commissionRate, req.stampDutyRate, req.slippageRate))
      if (rate != null && (!Double.isFinite(rate) || rate < 0 || rate >= 1))
        throw new IllegalArgumentException("费率必须在 [0,1) 内");
    if (req.parameters == null) req.parameters = new LinkedHashMap<>();
    Map<String, Object> p = req.parameters;
    switch (req.strategy) {
      case "ma":
        if (integer(p, "fastPeriod", 5) >= integer(p, "slowPeriod", 20))
          throw new IllegalArgumentException("快速均线周期必须小于慢速周期");
        break;
      case "macd":
        if (integer(p, "fast", 12) >= integer(p, "slow", 26))
          throw new IllegalArgumentException("MACD 快线必须小于慢线");
        integer(p, "signal", 9);
        break;
      case "rsi":
        integer(p, "period", 14);
        double lo = number(p, "oversold", 30), hi = number(p, "overbought", 70);
        if (lo < 0 || hi > 100 || lo >= hi) throw new IllegalArgumentException("RSI 阈值无效");
        break;
      case "bollinger":
        integer(p, "period", 20);
        if (number(p, "std", 2) <= 0) throw new IllegalArgumentException("标准差倍数须大于0");
        break;
      default:
        throw new IllegalArgumentException("不支持的策略：" + req.strategy);
    }
  }

  public int lookback(BacktestRequest r) {
    switch (r.strategy) {
      case "ma":
        return integer(r.parameters, "slowPeriod", 20);
      case "macd":
        return integer(r.parameters, "slow", 26) + integer(r.parameters, "signal", 9);
      default:
        return integer(r.parameters, "period", r.strategy.equals("rsi") ? 14 : 20);
    }
  }

  public static boolean selected(Bar b, BacktestRequest r, String market) {
    LocalDate d = Models.date(b.time, market);
    return (r.startDate == null || !d.isBefore(LocalDate.parse(r.startDate)))
        && (r.endDate == null || !d.isAfter(LocalDate.parse(r.endDate)));
  }

  private static boolean cross(Double[] a, Double[] b, int i, boolean up) {
    return i > 0
        && a[i] != null
        && b[i] != null
        && a[i - 1] != null
        && b[i - 1] != null
        && (up ? a[i - 1] <= b[i - 1] && a[i] > b[i] : a[i - 1] >= b[i - 1] && a[i] < b[i]);
  }

  public static boolean allowed(
      Bar bar, double previous, boolean buy, Map<String, Object> rules, boolean enforce) {
    Object limit = rules.get("priceLimitRule");
    if (!enforce || limit == null) return true;
    Map<String, Object> l = object(limit);
    if (!Boolean.TRUE.equals(l.get("enabled")) || previous <= 0) return true;
    double p = number(l, "percent", 0.1);
    return buy ? bar.open < previous * (1 + p) - 1e-9 : bar.open > previous * (1 - p) + 1e-9;
  }

  public static double quantity(double cash, double price, double cap, Map<String, Object> rules) {
    double commission = number(rules, "commissionRate", 0.0003),
        q = cash * cap / (price * (1 + commission));
    return Boolean.TRUE.equals(rules.get("allowFractionalShares"))
        ? q
        : Math.floor(q / integer(rules, "lotSize", 1)) * integer(rules, "lotSize", 1);
  }

  public Map<String, Object> run(
      List<Bar> history, Map<String, Object> original, BacktestRequest req, String source) {
    validate(req);
    String market = String.valueOf(original.get("market"));
    Map<String, Object> rules = new LinkedHashMap<>(original);
    if (req.commissionRate != null) rules.put("commissionRate", req.commissionRate);
    if (req.stampDutyRate != null) rules.put("stampDutyRate", req.stampDutyRate);
    if (req.slippageRate != null) rules.put("slippageRate", req.slippageRate);
    if (req.allowFractionalShares != null)
      rules.put("allowFractionalShares", req.allowFractionalShares);
    List<Integer> targets = new ArrayList<>();
    for (int i = 0; i < history.size(); i++)
      if (selected(history.get(i), req, market)) targets.add(i);
    Map<String, Object> result = empty(req, rules, source);
    if (targets.isEmpty()) return result;
    int first = targets.get(0),
        end = targets.get(targets.size() - 1),
        warmup = Math.min(first, Math.max(0, lookback(req) - 1)),
        context = first - warmup;
    List<Bar> b = history.subList(context, end + 1);
    int start = warmup;
    result.put("sampleCount", targets.size());
    result.put("warmupSampleCount", warmup);
    result.put("startDate", Models.date(history.get(first).time, market).toString());
    result.put("endDate", Models.date(history.get(end).time, market).toString());
    if (b.stream().anyMatch(x -> !x.valid())) {
      result.put("dataQuality", "data-error");
      return result;
    }
    Double[] c = closes(b), buyValues = null, sellValues = null;
    Map<String, Object> p = req.parameters;
    if (req.strategy.equals("ma")) {
      buyValues = sma(c, integer(p, "fastPeriod", 5));
      sellValues = sma(c, integer(p, "slowPeriod", 20));
    }
    if (req.strategy.equals("macd")) {
      Double[][] m =
          macd(c, integer(p, "fast", 12), integer(p, "slow", 26), integer(p, "signal", 9));
      buyValues = m[0];
      sellValues = m[1];
    }
    if (req.strategy.equals("rsi")) buyValues = rsi(c, integer(p, "period", 14));
    if (req.strategy.equals("bollinger")) {
      int n = integer(p, "period", 20);
      double mult = number(p, "std", 2);
      Double[] mid = sma(c, n), sd = std(c, n);
      buyValues = combine(mid, sd, (a, z) -> a - mult * z);
      sellValues = combine(mid, sd, (a, z) -> a + mult * z);
    }
    double cash = req.initialCapital,
        q = 0,
        entryPrice = 0,
        entryCommission = 0,
        entrySlippage = 0,
        fees = 0,
        winAmount = 0,
        lossAmount = 0,
        holding = 0,
        peak = req.initialCapital,
        drawdown = 0;
    long entryTime = 0;
    int pending = 0, buys = 0, sells = 0, buySignals = 0, sellSignals = 0, wins = 0, losses = 0;
    List<Map<String, Object>> trades = new ArrayList<>(),
        completed = new ArrayList<>(),
        curve = new ArrayList<>();
    double commission = number(rules, "commissionRate", 0.0003),
        stamp = number(rules, "stampDutyRate", 0),
        slip = number(rules, "slippageRate", 0.0005);
    for (int i = start; i < b.size(); i++) {
      Bar bar = b.get(i);
      if (i > start && pending != 0) {
        if (pending == 1
            && q == 0
            && allowed(
                bar,
                b.get(i - 1).close,
                true,
                rules,
                !Boolean.FALSE.equals(req.enforcePriceLimit))) {
          double price = bar.open * (1 + slip);
          double shares = quantity(cash, price, req.maxPosition, rules),
              fee = shares * price * commission,
              cost = shares * price + fee;
          if (shares > 0 && cost <= cash + 1e-8) {
            cash -= cost;
            q = shares;
            entryPrice = price;
            entryTime = bar.time;
            entryCommission = fee;
            entrySlippage = q * (price - bar.open);
            fees += fee + entrySlippage;
            buys++;
            trades.add(
                map(
                    "time",
                    bar.time,
                    "side",
                    "buy",
                    "price",
                    price,
                    "quantity",
                    q,
                    "cash",
                    cash,
                    "reason",
                    req.strategy));
          }
        } else if (pending == -1
            && q > 0
            && allowed(
                bar,
                b.get(i - 1).close,
                false,
                rules,
                !Boolean.FALSE.equals(req.enforcePriceLimit))) {
          double price = bar.open * (1 - slip),
              gross = q * price,
              fee = gross * commission,
              tax = gross * stamp,
              s = q * (bar.open - price),
              proceeds = gross - fee - tax,
              net = proceeds - q * entryPrice - entryCommission,
              days = Math.max(0, (bar.time - entryTime) / 86400.0);
          cash += proceeds;
          if (net >= 0) {
            wins++;
            winAmount += net;
          } else {
            losses++;
            lossAmount -= net;
          }
          holding += days;
          fees += fee + tax + s;
          sells++;
          completed.add(
              map(
                  "entryTime",
                  entryTime,
                  "exitTime",
                  bar.time,
                  "entryPrice",
                  entryPrice,
                  "exitPrice",
                  price,
                  "quantity",
                  q,
                  "grossPnl",
                  q * (price - entryPrice),
                  "netPnl",
                  net,
                  "returnRate",
                  net / (q * entryPrice + entryCommission),
                  "holdingDays",
                  days,
                  "commission",
                  fee + entryCommission,
                  "stampDuty",
                  tax,
                  "slippage",
                  entrySlippage + s,
                  "exitReason",
                  req.strategy,
                  "closedByStrategy",
                  true));
          trades.add(
              map(
                  "time",
                  bar.time,
                  "side",
                  "sell",
                  "price",
                  price,
                  "quantity",
                  q,
                  "cash",
                  cash,
                  "reason",
                  req.strategy));
          q = 0;
        }
      }
      double equity = cash + q * bar.close;
      curve.add(map("time", bar.time, "value", equity));
      peak = Math.max(peak, equity);
      drawdown = Math.max(drawdown, (peak - equity) / peak);
      pending = 0;
      if (i < b.size() - 1) {
        if (req.strategy.equals("ma") || req.strategy.equals("macd")) {
          if (cross(buyValues, sellValues, i, true)) pending = 1;
          else if (cross(buyValues, sellValues, i, false)) pending = -1;
        } else if (req.strategy.equals("rsi")) {
          if (buyValues[i] != null) {
            if (buyValues[i] < number(p, "oversold", 30)) pending = 1;
            else if (buyValues[i] > number(p, "overbought", 70)) pending = -1;
          }
        } else {
          if (buyValues[i] != null && bar.close < buyValues[i]) pending = 1;
          else if (sellValues[i] != null && bar.close > sellValues[i]) pending = -1;
        }
        if (pending == 1) buySignals++;
        if (pending == -1) sellSignals++;
      }
    }
    Bar last = b.get(b.size() - 1);
    double position = q * last.close,
        equity = cash + position,
        total = equity / req.initialCapital - 1,
        days = Math.max(1, (last.time - b.get(start).time) / 86400.0);
    String quality = targets.size() < 2 || b.size() < lookback(req) ? "insufficient-data" : "valid";
    result.putAll(
        map(
            "totalReturn",
            total,
            "annualizedReturn",
            Math.pow(Math.max(0, 1 + total), 365 / days) - 1,
            "maxDrawdown",
            drawdown,
            "winRate",
            completed.isEmpty() ? 0 : (double) wins / completed.size(),
            "profitLossRatio",
            wins > 0 && losses > 0 ? (winAmount / wins) / (lossAmount / losses) : 0,
            "profitFactor",
            lossAmount > 0 ? winAmount / lossAmount : null,
            "tradeCount",
            trades.size(),
            "completedTradeCount",
            completed.size(),
            "signalCount",
            buySignals + sellSignals,
            "buySignalCount",
            buySignals,
            "sellSignalCount",
            sellSignals,
            "executedBuyCount",
            buys,
            "executedSellCount",
            sells,
            "averageHoldingDays",
            completed.isEmpty() ? 0 : holding / completed.size(),
            "endingCash",
            cash,
            "endingEquity",
            equity,
            "endingPositionValue",
            position,
            "benchmarkReturn",
            last.close / b.get(start).close - 1,
            "dataQuality",
            quality,
            "completedTrades",
            completed,
            "totalFees",
            fees,
            "equityCurve",
            curve,
            "trades",
            trades,
            "openPosition",
            q == 0
                ? null
                : map(
                    "entryTime",
                    entryTime,
                    "entryPrice",
                    entryPrice,
                    "currentPrice",
                    last.close,
                    "quantity",
                    q,
                    "marketValue",
                    position,
                    "unrealizedPnl",
                    q * (last.close - entryPrice) - entryCommission,
                    "holdingDays",
                    (last.time - entryTime) / 86400.0,
                    "entryCommission",
                    entryCommission,
                    "entrySlippage",
                    entrySlippage)));
    return result;
  }

  private static Map<String, Object> empty(
      BacktestRequest r, Map<String, Object> rules, String source) {
    return map(
        "totalReturn",
        0,
        "annualizedReturn",
        0,
        "maxDrawdown",
        0,
        "winRate",
        0,
        "profitLossRatio",
        0,
        "profitFactor",
        null,
        "tradeCount",
        0,
        "completedTradeCount",
        0,
        "signalCount",
        0,
        "buySignalCount",
        0,
        "sellSignalCount",
        0,
        "executedBuyCount",
        0,
        "executedSellCount",
        0,
        "averageHoldingDays",
        0,
        "endingCash",
        r.initialCapital,
        "endingEquity",
        r.initialCapital,
        "endingPositionValue",
        0,
        "benchmarkReturn",
        0,
        "sampleCount",
        0,
        "warmupSampleCount",
        0,
        "startDate",
        null,
        "endDate",
        null,
        "dataQuality",
        "insufficient-data",
        "parameters",
        r.parameters,
        "completedTrades",
        Collections.emptyList(),
        "openPosition",
        null,
        "totalFees",
        0,
        "calculationVersion",
        2,
        "equityCurve",
        Collections.emptyList(),
        "trades",
        Collections.emptyList(),
        "currency",
        rules.get("currency"),
        "market",
        rules.get("market"),
        "source",
        source,
        "rules",
        rules);
  }
}
