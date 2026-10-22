package com.soyoung.stocks.service;

import static com.soyoung.stocks.domain.Json.*;
import static com.soyoung.stocks.service.IndicatorEngine.*;

import com.soyoung.stocks.domain.Models.*;
import com.soyoung.stocks.repository.StockRepository;
import com.soyoung.stocks.storage.UserScope;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class AnalysisService {
  private final StockRepository db;
  private final IndicatorEngine engine;
  private final EventHub hub;
  private final List<Map<String, Object>> catalog;
  private final Map<String, Map<String, Object>> lastAnalysis = new ConcurrentHashMap<>();
  private final Map<String, Long> lastAlert = new ConcurrentHashMap<>();

  @SuppressWarnings("unchecked")
  public AnalysisService(StockRepository db, IndicatorEngine engine, EventHub hub)
      throws Exception {
    this.db = db;
    this.engine = engine;
    this.hub = hub;
    catalog =
        MAPPER.readValue(getClass().getResourceAsStream("/indicator-catalog.json"), List.class);
  }

  public Object catalog() {
    return catalog;
  }

  private Map<String, Object> definition(String name) {
    String base = name.matches("MA[0-9]+") ? "MA" : name.replaceAll("_.*$", "");
    return catalog.stream()
        .filter(x -> name.equals(x.get("name")) || base.equals(x.get("name")))
        .findFirst()
        .orElse(map("name", name, "category", "other"));
  }

  public Map<String, Object> analysis(Instrument i, String period, Map<String, Object> request) {
    Series s = db.series(i.id, period, true);
    List<Result> results =
        engine.calculate(s == null ? Collections.emptyList() : s.bars, request, i.market, period);
    List<Object> details = new ArrayList<>();
    for (Result r : results) {
      details.add(detail(r.name, r.values, r, s, period));
      if (r.extra != null)
        for (Map.Entry<String, Object> e : r.extra.entrySet())
          if (e.getValue() instanceof Double[])
            details.add(detail(r.name + "_" + e.getKey(), (Double[]) e.getValue(), r, s, period));
    }
    return map(
        "instrumentId",
        i.id,
        "period",
        period,
        "indicators",
        results,
        "indicatorDetails",
        details,
        "summary",
        summary(results, s),
        "compositeDetails",
        composite(results, s, period),
        "source",
        s == null ? "暂无行情" : s.source,
        "fetchedAtMs",
        s == null ? 0 : s.fetchedAtMs,
        "calculationVersion",
        4);
  }

  private List<Object> composite(List<Result> results, Series s, String period) {
    Double ma = value(results, "MA20"), macd = value(results, "MACD"), rsi = value(results, "RSI");
    Double[] values = {
      ma == null || s == null ? null : s.bars.get(s.bars.size() - 1).close > ma ? 75. : 25.,
      macd == null ? null : macd > 0 ? 75. : 25.,
      rsi
    };
    String[] names = {"TREND_SCORE", "MOMENTUM_SCORE", "RSI_SCORE"},
        labels = {"趋势分量 · 40%", "动量分量 · 35%", "RSI 分量 · 25%"},
        formula = {"收盘价在 MA20 上方得75分，否则25分", "MACD DIF 大于0得75分，否则25分", "使用 RSI14 数值"};
    List<Object> out = new ArrayList<>();
    for (int k = 0; k < 3; k++)
      out.add(
          map(
              "name",
              names[k],
              "displayName",
              labels[k],
              "category",
              "composite",
              "latestValue",
              values[k],
              "period",
              period,
              "unit",
              "分",
              "dataQuality",
              values[k] == null ? "insufficient-samples" : "valid",
              "status",
              values[k] == null ? "样本不足" : values[k] >= 60 ? "偏多" : values[k] < 40 ? "偏空" : "中性",
              "source",
              s == null ? "暂无行情" : s.source,
              "updatedAt",
              s == null ? 0 : s.fetchedAtMs / 1000,
              "description",
              "有效分量按权重归一后得到描述性综合分，分数不表示盈利概率。",
              "formula",
              formula[k],
              "limitations",
              "Java Web 版综合分口径；不要与 Rust 版评分直接比较。"));
    return out;
  }

  private Object detail(String name, Double[] values, Result r, Series s, String period) {
    Map<String, Object> d = new LinkedHashMap<>(definition(name));
    Double v = last(values), prev = values.length > 1 ? values[values.length - 2] : null;
    d.putAll(
        map(
            "name",
            name,
            "latestValue",
            v,
            "previousValue",
            prev,
            "parameters",
            r.parameters,
            "period",
            period,
            "dataQuality",
            r.dataQuality,
            "source",
            s == null ? "暂无行情" : s.source,
            "updatedAt",
            s == null ? 0 : s.fetchedAtMs / 1000,
            "directionSymbol",
            v == null || prev == null ? "→" : v > prev ? "↑" : v < prev ? "↓" : "→",
            "status",
            v == null ? "样本不足" : status(name, v, prev),
            "tone",
            v == null ? "neutral" : v != null && prev != null && v > prev ? "bullish" : "neutral"));
    return d;
  }

  private String status(String n, double v, Double prev) {
    if (n.equals("RSI") || n.equals("MFI")) return v > 70 ? "超买观察" : v < 30 ? "超卖观察" : "中性区间";
    if (n.equals("MACD") || n.equals("PPO")) return v > 0 ? "偏多" : "偏空";
    return prev == null ? "有效" : v > prev ? "上升" : v < prev ? "下降" : "持平";
  }

  private Double value(List<Result> r, String n) {
    Result v = find(r, n);
    return v == null ? null : last(v.values);
  }

  private Map<String, Object> summary(List<Result> r, Series s) {
    Double ma = value(r, "MA20"),
        macd = value(r, "MACD"),
        rsi = value(r, "RSI"),
        atr = value(r, "ATR_PCT"),
        vol = value(r, "VOL_MA");
    double c = s == null ? 0 : s.bars.get(s.bars.size() - 1).close;
    return map(
        "trend",
        ma == null ? "样本不足" : c > ma ? "均线上方 · 偏多" : "均线下方 · 偏空",
        "momentum",
        macd == null ? "样本不足" : macd > 0 ? "动量偏多" : "动量偏空",
        "volatility",
        atr == null ? "样本不足" : atr > 4 ? "波动放大" : "正常波动",
        "volume",
        vol == null ? "成交量不可用" : "成交量有效",
        "signal",
        rsi == null ? "等待数据" : rsi > 70 ? "超买观察" : rsi < 30 ? "超卖观察" : "综合观察");
  }

  public Map<String, Object> realtime(Instrument i) {
    String period = "day";
    Series s = db.series(i.id, period, true);
    List<Result> r =
        engine.calculate(
            s == null ? Collections.emptyList() : s.bars, Collections.emptyMap(), i.market, period);
    Map<String, Object> summary = summary(r, s);
    double weight = 0, sum = 0;
    Double ma = value(r, "MA20"), m = value(r, "MACD"), rsi = value(r, "RSI");
    double close = s == null ? 0 : s.bars.get(s.bars.size() - 1).close;
    if (ma != null) {
      sum += (close > ma ? 75 : 25) * .4;
      weight += .4;
    }
    if (m != null) {
      sum += (m > 0 ? 75 : 25) * .35;
      weight += .35;
    }
    if (rsi != null) {
      sum += Math.max(0, Math.min(100, rsi)) * .25;
      weight += .25;
    }
    Double score = weight < .5 ? null : sum / weight;
    String signal =
        score == null
            ? "insufficient-data"
            : score >= 70 ? "bullish" : score < 40 ? "bearish" : "neutral";
    Double atr = value(r, "ATR_PCT");
    List<Object> warnings = new ArrayList<>();
    if (atr != null && atr > 4)
      warnings.add(map("title", "波动较高", "severity", "warning", "detail", "日线 ATR 超过价格的 4%"));
    if (rsi != null && (rsi > 70 || rsi < 30))
      warnings.add(
          map(
              "title",
              rsi > 70 ? "超买" : "超卖",
              "severity",
              "warning",
              "detail",
              "RSI 处于极值区间，不能单独作为反转信号"));
    if (s == null)
      warnings.add(map("title", "缺少行情", "severity", "warning", "detail", "请先采集或导入日 K 数据"));
    Double support = null, resistance = null;
    if (s != null) {
      int start = Math.max(0, s.bars.size() - 20);
      for (Bar b : s.bars.subList(start, s.bars.size())) {
        support = support == null ? b.low : Math.min(support, b.low);
        resistance = resistance == null ? b.high : Math.max(resistance, b.high);
      }
    }
    Map<String, Object> a =
        map(
            "instrumentId",
            i.id,
            "generation",
            hub.generation.get(),
            "period",
            period,
            "score",
            score,
            "signal",
            signal,
            "confidence",
            weight,
            "availableWeight",
            weight * 100,
            "trend",
            summary.get("trend"),
            "momentum",
            summary.get("momentum"),
            "volatility",
            summary.get("volatility"),
            "volumeState",
            summary.get("volume"),
            "recommendation",
            score == null ? "等待足够数据" : "指标描述仅供研究，请结合风险与交易成本",
            "source",
            s == null ? "暂无行情" : s.source,
            "updatedAtMs",
            System.currentTimeMillis(),
            "dataQuality",
            s == null || score == null
                ? "insufficient-samples"
                : s.bars.stream().anyMatch(b -> b.volume == null) ? "missing-volume" : "valid",
            "supportResistance",
            map("support", support, "resistance", resistance),
            "risk",
            map(
                "level",
                warnings.isEmpty() ? "正常观察" : "需要关注",
                "tone",
                warnings.isEmpty() ? "neutral" : "warning",
                "warnings",
                warnings,
                "atrPct",
                atr),
            "indicators",
            r,
            "compositeDetails",
            Collections.emptyList());
    return a;
  }

  public synchronized void publish(Instrument i) {
    Map<String, Object> current = realtime(i),
        previous = lastAnalysis.put(UserScope.owner() + ":" + i.id, current);
    hub.emit("realtime-analysis-update", current);
    Map<String, Object> profile = object(db.profile(i.id));
    if (!Boolean.TRUE.equals(profile.get("enabled"))) return;
    long now = System.currentTimeMillis();
    if (now - lastAlert.getOrDefault(UserScope.owner() + ":" + i.id, 0L)
        < number(profile, "cooldownSeconds", 900) * 1000) return;
    String signal = String.valueOf(current.get("signal"));
    List<String> reasons = new ArrayList<>();
    if (previous != null && !Objects.equals(previous.get("signal"), current.get("signal")))
      reasons.add("综合信号由 " + previous.get("signal") + " 变为 " + signal);
    if (previous != null
        && previous.get("score") != null
        && current.get("score") != null
        && Math.abs(number(current, "score", 0) - number(previous, "score", 0))
            >= number(profile, "scoreChangeThreshold", 10)) reasons.add("综合评分变化超过设定阈值");
    Series series = db.series(i.id, "day", false);
    if (series != null) {
      List<Result> indicators =
          engine.calculate(series.bars, Collections.emptyMap(), i.market, "day");
      Double rsi = value(indicators, "RSI");
      String zone =
          rsi == null
              ? "none"
              : rsi >= number(profile, "rsiOverbought", 70)
                  ? "high"
                  : rsi <= number(profile, "rsiOversold", 30) ? "low" : "neutral";
      current.put("rsiZone", zone);
      if ((zone.equals("high") || zone.equals("low"))
          && (previous == null || !zone.equals(previous.get("rsiZone"))))
        reasons.add(zone.equals("high") ? "RSI 越过超买阈值" : "RSI 越过超卖阈值");
      int size = series.bars.size();
      if (size > 5) {
        double sum = 0;
        boolean valid = true;
        for (int k = size - 6; k < size - 1; k++) {
          Double v = series.bars.get(k).volume;
          if (v == null) {
            valid = false;
            break;
          }
          sum += v;
        }
        Double last = series.bars.get(size - 1).volume;
        boolean high =
            valid
                && sum > 0
                && last != null
                && last / (sum / 5) >= number(profile, "volumeMultiple", 1);
        current.put("highVolume", high);
        if (high && (previous == null || !Boolean.TRUE.equals(previous.get("highVolume"))))
          reasons.add("成交量超过前5根均量的设定倍数");
      }
    }
    if (reasons.isEmpty()) return;
    lastAlert.put(UserScope.owner() + ":" + i.id, now);
    Map<String, Object> event =
        map(
            "instrumentId",
            i.id,
            "generation",
            hub.generation.get(),
            "timestamp",
            now / 1000,
            "eventTime",
            now / 1000,
            "severity",
            "warning",
            "signal",
            signal,
            "score",
            current.get("score"),
            "period",
            current.get("period"),
            "source",
            current.get("source"),
            "dataQuality",
            current.get("dataQuality"),
            "eventType",
            "indicator-change",
            "message",
            String.join("；", reasons),
            "reasons",
            reasons,
            "indicatorSnapshot",
            composite(
                series == null
                    ? Collections.emptyList()
                    : engine.calculate(series.bars, Collections.emptyMap(), i.market, "day"),
                series,
                "day"),
            "acknowledged",
            false);
    long id = db.saveEvent(i.id, event);
    event.put("id", id);
    hub.emit("analysis-event", event);
  }
}
