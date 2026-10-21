package com.soyoung.stocks.service;

import static com.soyoung.stocks.domain.Json.*;

import com.soyoung.stocks.domain.Models;
import com.soyoung.stocks.domain.Models.*;
import java.time.*;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Calendar-aligned factor simulation; no current-day volume used for opening fills. */
@Service
public class PortfolioEngine {
  public static class Asset {
    public Instrument instrument;
    public Series series;
    public Map<String, Object> rules;

    public Asset(Instrument i, Series s, Map<String, Object> r) {
      instrument = i;
      series = s;
      rules = r;
    }
  }

  private void validate(Map<String, Object> req, List<Asset> assets) {
    if (assets.isEmpty() || assets.size() > 100)
      throw new IllegalArgumentException("组合需要 1 至 100 只股票");
    String currency = assets.get(0).instrument.currency, market = assets.get(0).instrument.market;
    for (Asset a : assets) {
      if (!currency.equals(a.instrument.currency))
        throw new IllegalArgumentException("组合不支持混合币种，请按币种分组研究");
      if (!market.equals(a.instrument.market)
          && !(market.startsWith("cn") && a.instrument.market.startsWith("cn")))
        throw new IllegalArgumentException("组合需要同一交易时区和市场");
      if (a.series == null || a.series.bars.stream().anyMatch(b -> !b.valid()))
        throw new IllegalArgumentException(a.instrument.name + " 缺少有效日 K");
    }
    double capital = number(req, "initialCapital", 100000);
    if (capital <= 0 || capital > 1e15) throw new IllegalArgumentException("初始资金超出范围");
    Map<String, Object> f = object(req.get("factorConfig"));
    double sum = 0;
    for (String key : factorKeys) {
      double w = number(f, key, .2);
      if (w < 0 || w > 1) throw new IllegalArgumentException("因子权重须在 [0,1] 内");
      sum += w;
    }
    if (sum <= 0) throw new IllegalArgumentException("至少一个因子权重大于0");
    double cap = number(f, "maxPositionWeight", .2);
    if (cap <= 0 || cap > 1) throw new IllegalArgumentException("单股仓位须在 (0,1] 内");
    integer(f, "targetHoldings", 5);
    String frequency = String.valueOf(f.getOrDefault("rebalanceFrequency", "monthly"));
    if (!Arrays.asList("daily", "weekly", "monthly", "quarterly").contains(frequency))
      throw new IllegalArgumentException("不支持的再平衡频率");
    Map<String, Object> cost = object(req.get("transactionCostConfig"));
    double part = number(cost, "maxParticipationRate", .1);
    if (part <= 0 || part > 1) throw new IllegalArgumentException("成交量参与率须在 (0,1] 内");
    for (String key : Arrays.asList("commissionRate", "stampDutyRate", "slippageRate"))
      if (cost.get(key) != null && (number(cost, key, 0) < 0 || number(cost, key, 0) >= 1))
        throw new IllegalArgumentException("交易费率无效");
    double spread = number(cost, "spreadBps", 0);
    if (spread < 0 || spread > 1000) throw new IllegalArgumentException("价差须在 0 至 1000 基点内");
    double train = number(req, "trainRatio", .6), val = number(req, "validationRatio", .2);
    if (train <= 0 || val <= 0 || train + val >= 1)
      throw new IllegalArgumentException("训练/验证区间比例无效");
    String start = (String) req.get("startDate"), end = (String) req.get("endDate");
    if (start != null && end != null && LocalDate.parse(start).isAfter(LocalDate.parse(end)))
      throw new IllegalArgumentException("开始日期晚于结束日期");
    String basis = String.valueOf(req.getOrDefault("priceBasis", "unknown"));
    if (!basis.equals("unknown"))
      for (Asset a : assets)
        if (!basis.equals(a.series.adjustment))
          throw new IllegalArgumentException(a.instrument.name + " 价格口径不匹配：" + a.series.adjustment);
  }

  private static final String[] factorKeys = {
    "trendWeight",
    "momentumWeight",
    "volatilityWeight",
    "liquidityWeight",
    "volumeConfirmationWeight"
  };

  private static double mean(double[] x) {
    return Arrays.stream(x).average().orElse(0);
  }

  private static double sd(double[] x) {
    double m = mean(x);
    return Math.sqrt(Arrays.stream(x).map(v -> (v - m) * (v - m)).average().orElse(0));
  }

  private static double[] raw(List<Bar> b, int k) {
    if (k < 60) return null;
    for (int j = k - 60; j <= k; j++) if (!b.get(j).valid() || b.get(j).volume == null) return null;
    double avg = 0, vol = 0, shortVol = 0;
    double[] returns = new double[20];
    for (int j = k - 19; j <= k; j++) {
      avg += b.get(j).close / 20;
      vol += b.get(j).volume / 20;
      if (j > k - 5) shortVol += b.get(j).volume / 5;
      returns[j - (k - 19)] = b.get(j).close / b.get(j - 1).close - 1;
    }
    Bar last = b.get(k);
    return new double[] {
      last.close / avg - 1,
      last.close / b.get(k - 20).close - 1,
      -sd(returns),
      Math.log1p(vol * last.close),
      vol > 0 ? shortVol / vol - 1 : 0
    };
  }

  public Map<String, Object> run(List<Asset> assets, Asset benchmark, Map<String, Object> req) {
    validate(req, assets);
    Map<String, Object> factor = object(req.get("factorConfig")),
        cost = object(req.get("transactionCostConfig"));
    String market = assets.get(0).instrument.market;
    List<Map<LocalDate, Integer>> indices = new ArrayList<>();
    TreeSet<LocalDate> dates = null;
    for (Asset a : assets) {
      Map<LocalDate, Integer> m = new HashMap<>();
      for (int k = 0; k < a.series.bars.size(); k++)
        m.put(Models.date(a.series.bars.get(k).time, market), k);
      indices.add(m);
      if (dates == null) dates = new TreeSet<>(m.keySet());
      else dates.retainAll(m.keySet());
    }
    final String start = (String) req.get("startDate"), end = (String) req.get("endDate");
    List<LocalDate> calendar =
        dates.stream()
            .filter(
                d ->
                    (start == null || !d.isBefore(LocalDate.parse(start)))
                        && (end == null || !d.isAfter(LocalDate.parse(end))))
            .collect(Collectors.toList());
    int min = integer(req, "minSamples", 120);
    if (calendar.size() < min)
      throw new IllegalArgumentException(
          "共同交易日期仅 " + calendar.size() + " 条，至少需要 " + min + " 条；可先校验数据");
    if (calendar.size() > 20000) throw new IllegalArgumentException("研究样本超出限制");
    int n = assets.size();
    double[] quantities = new double[n],
        basis = new double[n],
        target = new double[n],
        scores = new double[n];
    double capital = number(req, "initialCapital", 100000),
        cash = capital,
        totalFees = 0,
        totalSlippage = 0,
        turnover = 0;
    int rebalances = 0;
    List<Map<String, Object>> curve = new ArrayList<>(),
        trades = new ArrayList<>(),
        holdings = new ArrayList<>();
    String frequency = String.valueOf(factor.getOrDefault("rebalanceFrequency", "monthly"));
    double[] weights = new double[5];
    double weightSum = 0;
    for (int k = 0; k < 5; k++) {
      weights[k] = number(factor, factorKeys[k], .2);
      weightSum += weights[k];
    }
    for (int k = 0; k < 5; k++) weights[k] /= weightSum;
    for (int day = 0; day < calendar.size(); day++) {
      LocalDate date = calendar.get(day);
      boolean rebalance = day > 0 && changed(calendar.get(day - 1), date, frequency);
      double[] opens = new double[n], closes = new double[n];
      for (int a = 0; a < n; a++) {
        Bar b = assets.get(a).series.bars.get(indices.get(a).get(date));
        opens[a] = b.open;
        closes[a] = b.close;
      }
      if (rebalance) {
        List<double[]> raw = new ArrayList<>();
        List<Integer> candidates = new ArrayList<>();
        for (int a = 0; a < n; a++) {
          int k = indices.get(a).get(calendar.get(day - 1));
          double[] features = raw(assets.get(a).series.bars, k);
          if (features != null) {
            candidates.add(a);
            raw.add(features);
          }
        }
        Arrays.fill(scores, Double.NaN);
        Arrays.fill(target, 0);
        for (int z = 0; z < candidates.size(); z++) {
          double score = 0;
          for (int f = 0; f < 5; f++) {
            final int col = f;
            double[] values = raw.stream().mapToDouble(x -> x[col]).toArray();
            double sigma = sd(values);
            score += weights[f] * (sigma == 0 ? 0 : (raw.get(z)[f] - mean(values)) / sigma);
          }
          scores[candidates.get(z)] = score;
        }
        candidates.sort((a, b) -> Double.compare(scores[b], scores[a]));
        int count = Math.min(integer(factor, "targetHoldings", 5), candidates.size());
        double maxWeight = number(factor, "maxPositionWeight", .2);
        for (int a = 0; a < count; a++)
          target[candidates.get(a)] = Math.min(maxWeight, 1.0 / count);
        double equity = cash;
        for (int a = 0; a < n; a++) equity += quantities[a] * opens[a];
        double[] desired = new double[n];
        for (int a = 0; a < n; a++) desired[a] = equity * target[a] / opens[a];
        // One bounded rebalance. Unfillable or sub-lot remainder expires before the next signal.
        for (int side = 0; side < 2; side++)
          for (int a = 0; a < n; a++) {
            Asset asset = assets.get(a);
            Map<String, Object> rules = new LinkedHashMap<>(asset.rules);
            for (String key : Arrays.asList("commissionRate", "stampDutyRate", "slippageRate"))
              if (cost.get(key) != null) rules.put(key, cost.get(key));
            boolean fractional = Boolean.TRUE.equals(req.get("allowFractionalShares"));
            double lot = fractional ? 0 : integer(rules, "lotSize", 1);
            boolean buy = side == 1;
            double delta = desired[a] - quantities[a];
            if (buy ? delta <= 1e-9 : delta >= -1e-9) continue;
            int k = indices.get(a).get(date);
            Bar bar = asset.series.bars.get(k);
            Bar previous = k > 0 ? asset.series.bars.get(k - 1) : null;
            if (previous == null
                || !BacktestEngine.allowed(
                    bar,
                    previous.close,
                    buy,
                    rules,
                    !Boolean.FALSE.equals(req.get("enforcePriceLimit")))) continue;
            double available =
                previous.volume == null
                    ? 0
                    : previous.volume * number(cost, "maxParticipationRate", .1);
            double q = Math.min(Math.abs(delta), available);
            double
                slip = number(rules, "slippageRate", .0005) + number(cost, "spreadBps", 0) / 20000,
                price = opens[a] * (1 + (buy ? slip : -slip));
            double commission = number(rules, "commissionRate", .0003),
                stamp = buy ? 0 : number(rules, "stampDutyRate", 0);
            if (buy) q = Math.min(q, cash / (price * (1 + commission)));
            else q = Math.min(q, quantities[a]);
            if (lot > 0) q = Math.floor((q + 1e-9) / lot) * lot;
            if (q < 1e-9) continue;
            double notional = q * price,
                fees = notional * (commission + stamp),
                slippage = q * Math.abs(price - opens[a]);
            double realized = buy ? 0 : (price - basis[a]) * q - fees;
            if (buy) {
              basis[a] = (basis[a] * quantities[a] + notional + fees) / (quantities[a] + q);
              cash -= notional + fees;
              quantities[a] += q;
            } else {
              cash += notional - fees;
              quantities[a] -= q;
            }
            if (cash < -1e-6) throw new IllegalStateException("回测资金守恒校验失败");
            cash = Math.max(0, cash);
            totalFees += fees;
            totalSlippage += slippage;
            turnover += notional;
            trades.add(
                map(
                    "instrumentId",
                    asset.instrument.id,
                    "date",
                    date.toString(),
                    "time",
                    bar.time,
                    "side",
                    buy ? "buy" : "sell",
                    "quantity",
                    q,
                    "price",
                    price,
                    "fees",
                    fees,
                    "slippage",
                    slippage,
                    "realizedPnl",
                    realized,
                    "cash",
                    cash));
          }
        rebalances++;
      }
      double position = 0;
      for (int a = 0; a < n; a++) position += quantities[a] * closes[a];
      double equity = cash + position;
      curve.add(
          map(
              "date",
              date.toString(),
              "time",
              date.atTime(12, 0).atZone(Models.zone(market)).toEpochSecond(),
              "equity",
              equity,
              "cash",
              cash,
              "positionValue",
              position));
      if (rebalance || day == calendar.size() - 1)
        for (int a = 0; a < n; a++)
          if (quantities[a] > 0) {
            Instrument i = assets.get(a).instrument;
            holdings.add(
                map(
                    "instrumentId",
                    i.id,
                    "name",
                    i.name,
                    "code",
                    i.code,
                    "market",
                    i.market,
                    "date",
                    date.toString(),
                    "factorScore",
                    Double.isFinite(scores[a]) ? scores[a] : null,
                    "targetWeight",
                    target[a],
                    "actualWeight",
                    quantities[a] * closes[a] / equity,
                    "price",
                    closes[a],
                    "quantity",
                    quantities[a],
                    "marketValue",
                    quantities[a] * closes[a],
                    "contribution",
                    (closes[a] - basis[a]) * quantities[a],
                    "dataQuality",
                    "valid"));
          }
    }
    List<String> warnings =
        new ArrayList<>(
            Arrays.asList(
                "仅对共同交易日期模拟；缺失日期不填造价格。停牌和公司行动需在数据导入前核实。",
                "开盘成交量上限使用前一交易日已知成交量；未成交余量在本次再平衡后失效。",
                "训练/验证/测试为同一预设策略曲线的分段描述，不代表拟合或独立样本外检验。",
                "Java 组合引擎 v2 修正了原整股余量阻塞和当日成交量前视，结果不与旧组合版本逐笔等同。"));
    List<Object> metrics = metrics(curve, capital);
    double ending = number(curve.get(curve.size() - 1), "equity", capital);
    metrics.add(metric("tradeCount", trades.size(), curve.size()));
    metrics.add(metric("turnover", turnover / capital, curve.size()));
    addBenchmark(metrics, curve, benchmark, market);
    List<Object> segments = new ArrayList<>();
    int train = (int) (curve.size() * number(req, "trainRatio", .6)),
        val = train + (int) (curve.size() * number(req, "validationRatio", .2));
    int[] edges = {0, train, val, curve.size()};
    String[] names = {"训练区间（描述）", "验证区间（描述）", "测试区间（描述）"};
    for (int z = 0; z < 3; z++)
      if (edges[z + 1] > edges[z]) {
        List<Map<String, Object>> part = curve.subList(edges[z], edges[z + 1]);
        double initial =
            edges[z] == 0 ? capital : number(curve.get(edges[z] - 1), "equity", capital);
        segments.add(
            map(
                "name",
                names[z],
                "startDate",
                part.get(0).get("date"),
                "endDate",
                part.get(part.size() - 1).get("date"),
                "metrics",
                metrics(part, initial),
                "equityCurve",
                part));
      }
    Map<String, Object> snapshot =
        map(
            "startDate",
            calendar.get(0).toString(),
            "endDate",
            calendar.get(calendar.size() - 1).toString(),
            "instrumentCount",
            n,
            "priceBasis",
            req.getOrDefault("priceBasis", "unknown"),
            "preferredSource",
            assets.stream().map(a -> a.series.source).distinct().collect(Collectors.joining(" / ")),
            "currency",
            assets.get(0).instrument.currency,
            "instruments",
            assets.stream()
                .map(
                    a ->
                        map(
                            "id",
                            a.instrument.id,
                            "fetchedAtMs",
                            a.series.fetchedAtMs,
                            "barCount",
                            a.series.bars.size(),
                            "adjustment",
                            a.series.adjustment))
                .collect(Collectors.toList()));
    return map(
        "calculationVersion",
        2,
        "metrics",
        metrics,
        "equityCurve",
        curve,
        "holdings",
        holdings,
        "trades",
        trades,
        "segments",
        segments,
        "warnings",
        warnings,
        "dataSnapshot",
        snapshot,
        "endingEquity",
        ending,
        "endingCash",
        cash,
        "endingPositionValue",
        ending - cash,
        "totalFees",
        totalFees,
        "totalSlippage",
        totalSlippage,
        "rebalanceCount",
        rebalances,
        "initialCapital",
        capital,
        "request",
        req);
  }

  private static boolean changed(LocalDate previous, LocalDate current, String freq) {
    if (freq.equals("daily")) return true;
    if (freq.equals("weekly"))
      return previous.get(WeekFields.ISO.weekOfWeekBasedYear())
              != current.get(WeekFields.ISO.weekOfWeekBasedYear())
          || previous.get(WeekFields.ISO.weekBasedYear())
              != current.get(WeekFields.ISO.weekBasedYear());
    if (freq.equals("quarterly"))
      return (previous.getMonthValue() - 1) / 3 != (current.getMonthValue() - 1) / 3
          || previous.getYear() != current.getYear();
    return previous.getMonth() != current.getMonth() || previous.getYear() != current.getYear();
  }

  public static Object metric(String name, Number value, int count) {
    return map(
        "name",
        name,
        "value",
        value,
        "sampleCount",
        count,
        "dataQuality",
        value == null ? "insufficient-samples" : "valid");
  }

  public static List<Object> metrics(List<Map<String, Object>> curve, double initial) {
    int n = curve.size();
    List<Object> out = new ArrayList<>();
    if (n == 0) return out;
    double[] returns = new double[n];
    double prev = initial, peak = initial, mdd = 0;
    for (int k = 0; k < n; k++) {
      double e = number(curve.get(k), "equity", initial);
      returns[k] = e / prev - 1;
      prev = e;
      peak = Math.max(peak, e);
      mdd = Math.max(mdd, 1 - e / peak);
    }
    double total = prev / initial - 1,
        years =
            Math.max(
                    1,
                    java.time.temporal.ChronoUnit.DAYS.between(
                        LocalDate.parse(String.valueOf(curve.get(0).get("date"))),
                        LocalDate.parse(String.valueOf(curve.get(n - 1).get("date")))))
                / 365.25,
        cagr = Math.pow(prev / initial, 1 / years) - 1,
        avg = mean(returns),
        sigma = sd(returns),
        down =
            Math.sqrt(
                Arrays.stream(returns).map(x -> Math.pow(Math.min(0, x), 2)).average().orElse(0));
    out.add(metric("totalReturn", total, n));
    out.add(metric("cagr", Double.isFinite(cagr) ? cagr : null, n));
    out.add(metric("maxDrawdown", mdd, n));
    out.add(metric("annualizedVolatility", sigma * Math.sqrt(252), n));
    out.add(metric("sharpe", sigma > 0 ? avg / sigma * Math.sqrt(252) : null, n));
    out.add(metric("sortino", down > 0 ? avg / down * Math.sqrt(252) : null, n));
    out.add(metric("calmar", mdd > 0 ? cagr / mdd : null, n));
    return out;
  }

  private void addBenchmark(
      List<Object> metrics, List<Map<String, Object>> curve, Asset benchmark, String market) {
    Double value = null, beta = null, alpha = null;
    int count = 0;
    if (benchmark != null && benchmark.series != null) {
      Map<String, Double> prices = new HashMap<>();
      for (Bar b : benchmark.series.bars)
        prices.put(Models.date(b.time, benchmark.instrument.market).toString(), b.close);
      boolean complete = curve.stream().allMatch(row -> prices.containsKey(row.get("date")));
      if (complete && curve.size() > 1) {
        double first = prices.get(curve.get(0).get("date")),
            last = prices.get(curve.get(curve.size() - 1).get("date"));
        value = last / first - 1;
        int n = curve.size() - 1;
        double[] p = new double[n], b = new double[n];
        for (int k = 1; k < curve.size(); k++) {
          p[k - 1] = number(curve.get(k), "equity", 1) / number(curve.get(k - 1), "equity", 1) - 1;
          b[k - 1] =
              prices.get(curve.get(k).get("date")) / prices.get(curve.get(k - 1).get("date")) - 1;
        }
        double pm = mean(p), bm = mean(b), cov = 0, var = 0;
        for (int k = 0; k < n; k++) {
          cov += (p[k] - pm) * (b[k] - bm);
          var += Math.pow(b[k] - bm, 2);
        }
        if (var > 0) {
          beta = cov / var;
          alpha = (pm - beta * bm) * 252;
        }
        count = n;
      }
    }
    Double total = null;
    for (Object m : metrics)
      if ("totalReturn".equals(object(m).get("name"))) total = number(object(m), "value", 0);
    metrics.add(metric("benchmarkReturn", value, count));
    metrics.add(
        metric("excessReturn", value == null || total == null ? null : total - value, count));
    metrics.add(metric("beta", beta, count));
    metrics.add(metric("alpha", alpha, count));
  }
}
