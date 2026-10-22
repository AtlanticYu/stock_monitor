package com.soyoung.stocks.service;

import static com.soyoung.stocks.domain.Json.*;

import com.soyoung.stocks.domain.Models;
import com.soyoung.stocks.domain.Models.*;
import com.soyoung.stocks.repository.StockRepository;
import java.time.*;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class CommandService {
  private final StockRepository db;
  private final InstrumentService instruments;
  private final AnalysisService analysis;
  private final MarketDataService market;
  private final BacktestEngine backtest;
  private final PortfolioEngine portfolio;
  private final EventHub hub;
  private final ArchiveService archives;

  public CommandService(
      StockRepository db,
      InstrumentService instruments,
      AnalysisService analysis,
      MarketDataService market,
      BacktestEngine backtest,
      PortfolioEngine portfolio,
      EventHub hub,
      ArchiveService archives) {
    this.db = db;
    this.instruments = instruments;
    this.analysis = analysis;
    this.market = market;
    this.backtest = backtest;
    this.portfolio = portfolio;
    this.hub = hub;
    this.archives = archives;
  }

  private long id(Map<String, Object> args, String key) {
    double n = number(args, key, 0);
    if (n <= 0 || n % 1 != 0) throw new IllegalArgumentException("无效记录 ID");
    return (long) n;
  }

  private Instrument instrument(Map<String, Object> args) {
    return args.get("instrumentId") == null ? db.active() : db.instrument(id(args, "instrumentId"));
  }

  private Map<String, Object> request(Map<String, Object> args) {
    return object(args.get("request"));
  }

  public Object execute(String cmd, Map<String, Object> a) {
    switch (cmd) {
      case "list_instruments":
        return db.instruments();
      case "get_active_instrument":
        return db.activeOrNull();
      case "preview_instrument":
        return InstrumentService.identity((String) a.get("pageUrl"));
      case "add_instrument":
        return add(a);
      case "update_instrument":
        return update(a);
      case "refresh_instrument_name":
        return instrument(a);
      case "set_active_instrument":
        {
          Instrument i = db.setActive(id(a, "id"));
          hub.generation.incrementAndGet();
          hub.emit(
              "instrument-changed",
              map(
                  "instrumentId",
                  i.id,
                  "instrument",
                  i,
                  "generation",
                  hub.generation.get(),
                  "cachedQuote",
                  db.quote(i.id)));
          market.refresh(i);
          return i;
        }
      case "set_instrument_enabled":
        {
          Instrument i = db.instrument(id(a, "id"));
          i.enabled = Boolean.TRUE.equals(a.get("enabled"));
          db.updateInstrument(i);
          if (!i.enabled) market.stop(i.id);
          return i;
        }
      case "delete_instrument":
        {
          long id = id(a, "id");
          market.stop(id);
          db.deleteInstrument(id);
          return true;
        }
      case "delete_instrument_data":
        archives.backup();
        db.deleteData(id(a, "id"));
        return true;
      case "reorder_instruments":
        return reorder(a);
      case "list_market_rules":
        return Arrays.asList("hk", "cn-ab", "cn-sh", "cn-sz", "us").stream()
            .map(instruments::rules)
            .collect(Collectors.toList());
      case "get_market_rules":
        return instruments.rules(String.valueOf(a.get("market")));
      case "save_market_rules":
        instruments.saveRules(object(a.get("rules")));
        return true;
      case "load_latest_quote":
        return db.quote(instrument(a).id);
      case "start_monitor":
        {
          Instrument i = db.activeOrNull();
          if (i == null)
            return map(
                "quote", null, "status", map("state", "disconnected", "message", "请点击左侧 + 添加股票"));
          market.refresh(i);
          return map("quote", db.quote(i.id), "status", market.health(i));
        }
      case "get_data_health":
      case "get_realtime_status":
        return market.health(instrument(a));
      case "load_chart":
        {
          Instrument i = instrument(a);
          String p = Models.period(String.valueOf(a.getOrDefault("period", "day")));
          Series s = series(i, p);
          String warning = market.chartWarning(i, p);
          if (warning.isEmpty()) warning = s == null ? "尚无行情；请刷新采集，或在设置中导入日 K 数据" : s.note;
          return map(
              "series",
              s,
              "warning",
              warning,
              "cachePath",
              "MySQL",
              "marketTimezone",
              Models.zone(i.market).getId());
        }
      case "live_chart":
        return series(instrument(a), String.valueOf(a.getOrDefault("period", "day")));
      case "refresh_chart_page":
      case "refresh_instrument":
        return market.refresh(instrument(a));
      case "load_analysis":
      case "calculate_indicators":
        return analysis.analysis(
            instrument(a),
            Models.period(String.valueOf(a.getOrDefault("period", "day"))),
            request(a));
      case "get_indicator_catalog":
        return analysis.catalog();
      case "load_realtime_analysis":
      case "calculate_realtime_analysis":
        return analysis.realtime(instrument(a));
      case "list_analysis_events":
        return db.events(a.get("instrumentId") == null ? null : id(a, "instrumentId"));
      case "acknowledge_analysis_event":
        db.ack(id(a, "eventId"));
        return true;
      case "clear_analysis_events":
        db.clearEvents(a.get("instrumentId") == null ? null : id(a, "instrumentId"));
        return true;
      case "save_alert_profile":
        {
          Map<String, Object> p = object(a.get("profile"));
          long i = id(p, "instrumentId");
          double cooldown = number(p, "cooldownSeconds", 900);
          if (cooldown < 0 || cooldown > 86400) throw new IllegalArgumentException("提醒间隔无效");
          double lo = number(p, "rsiOversold", 30), hi = number(p, "rsiOverbought", 70);
          if (lo < 0
              || hi > 100
              || lo >= hi
              || number(p, "volumeMultiple", 1) < 1
              || number(p, "scoreChangeThreshold", 10) <= 0)
            throw new IllegalArgumentException("提醒阈值无效");
          db.saveProfile(i, p);
          return p;
        }
      case "load_alert_profile":
        return db.profile(instrument(a).id);
      case "run_backtest":
        return runBacktest(convert(a.get("request"), BacktestRequest.class), true);
      case "screen_instruments_daily_backtest":
        return screen(request(a));
      case "scan_single_strategy_parameters":
        return scanSingle(request(a), a.get("candidates"));
      case "list_backtests":
        return db.backtests(a.get("instrumentId") == null ? null : id(a, "instrumentId"));
      case "load_backtest":
        return db.backtest(id(a, "id"));
      case "run_portfolio_research":
        return runPortfolio(request(a), true);
      case "validate_research_data":
      case "get_data_snapshot":
        return validateData(request(a));
      case "run_walk_forward":
        return walkForward(request(a));
      case "scan_strategy_parameters":
        return scanPortfolio(request(a));
      case "compare_research_runs":
        return compare(a);
      case "list_research_runs":
        return db.researchRuns();
      case "load_research_run":
        return db.researchRun(id(a, "id"));
      case "list_strategies":
        return db.strategies();
      case "save_strategy":
        {
          Map<String, Object> s = object(a.getOrDefault("strategy", a));
          return db.saveStrategy(
              (String) s.get("name"),
              String.valueOf(s.getOrDefault("kind", "ma")),
              s.getOrDefault("parameters", new LinkedHashMap<>()));
        }
      case "delete_strategy":
        db.deleteStrategy(id(a, "id"));
        return true;
      case "database_path":
        return "MySQL · 服务端持久化";
      case "get_retention_days":
        return Integer.parseInt(db.setting("retention_days", "365"));
      case "save_retention_days":
        {
          int days = integer(a, "retentionDays", 365);
          if (days > 3650) throw new IllegalArgumentException("最多保留 3650 天");
          db.settingPut("retention_days", String.valueOf(days));
          return days;
        }
      case "cleanup_expired_data":
        {
          String name = archives.backup();
          return map("deletedRows", db.cleanup(integer(a, "retentionDays", 365)), "backup", name);
        }
      case "check_database_integrity":
        return db.integrity();
      case "export_diagnostics":
        return diagnostics();
      case "export_instrument_data":
        return exportInstrument(instrument(a));
      case "list_intraday_dates":
        return db.intraday(instrument(a).id, null, null).stream()
            .map(r -> r.get("tradingDate"))
            .distinct()
            .collect(Collectors.toList());
      case "load_intraday_bars":
      case "export_intraday_data":
        return db.intraday(
            instrument(a).id, (String) a.get("startDate"), (String) a.get("endDate"));
      case "backup_database":
        return archives.export();
      case "restore_database":
        return archives.restore(object(a.get("archive")));
      case "set_web_panel":
      case "web_panel_state":
        return map("expanded", false, "visible", false);
      default:
        throw new IllegalArgumentException("不支持的操作：" + cmd);
    }
  }

  private Instrument add(Map<String, Object> a) {
    if (db.instruments().size() >= 100) throw new IllegalArgumentException("股票列表最多 100 项");
    Instrument i = InstrumentService.identity((String) a.get("pageUrl"));
    String name = (String) a.getOrDefault("displayName", a.get("name"));
    if (name != null && !name.trim().isEmpty()) {
      i.name = name.trim();
      i.nameSource = "custom";
    }
    if (i.name.length() > 128) throw new IllegalArgumentException("名称过长");
    i.sortOrder = db.instruments().size();
    i.id = db.insertInstrument(i);
    market.refresh(i);
    return db.instrument(i.id);
  }

  private Instrument update(Map<String, Object> a) {
    Instrument old = db.instrument(id(a, "id"));
    String url = (String) a.get("pageUrl");
    if (url != null) {
      Instrument identity = InstrumentService.identity(url);
      if (!old.market.equals(identity.market)
          || !old.code.equals(identity.code)
          || !InstrumentService.financeType(old).equals(InstrumentService.financeType(identity)))
        throw new IllegalArgumentException("编辑只修改名称和状态；修改市场、代码或股票/指数类型请添加新标的");
    }
    if (a.get("name") != null || a.get("displayName") != null) {
      String name = String.valueOf(a.getOrDefault("displayName", a.get("name"))).trim();
      if (name.isEmpty() || name.length() > 128)
        throw new IllegalArgumentException("名称长度须为 1 至 128");
      old.name = name;
      old.nameSource = "custom";
    }
    if (a.containsKey("enabled")) old.enabled = Boolean.TRUE.equals(a.get("enabled"));
    db.updateInstrument(old);
    if (!old.enabled) market.stop(old.id);
    return db.instrument(old.id);
  }

  private Object reorder(Map<String, Object> a) {
    List<?> ids = (List<?>) a.get("ids");
    if (ids == null) throw new IllegalArgumentException("缺少排序 ID");
    db.reorder(ids);
    return db.instruments();
  }

  public Series series(Instrument i, String period) {
    Models.period(period);
    Series s = db.series(i.id, period, true);
    if (s != null || period.equals("day") || period.equals("intraday")) return s;
    Series d = db.series(i.id, "day", false);
    if (d == null) return null;
    Series out = new Series();
    out.period = period;
    out.source = d.source + " · 日线聚合";
    out.adjustment = d.adjustment;
    out.fetchedAtMs = d.fetchedAtMs;
    out.note = "依据已存日线聚合；最后一根可能不完整";
    Map<String, Bar> grouped = new LinkedHashMap<>();
    for (Bar b : d.bars) {
      LocalDate date = Models.date(b.time, i.market);
      String key =
          period.equals("year")
              ? String.valueOf(date.getYear())
              : period.equals("month")
                  ? date.toString().substring(0, 7)
                  : date.get(WeekFields.ISO.weekBasedYear())
                      + "-"
                      + date.get(WeekFields.ISO.weekOfWeekBasedYear());
      Bar row = grouped.get(key);
      if (row == null) grouped.put(key, new Bar(b.time, b.open, b.high, b.low, b.close, b.volume));
      else {
        row.high = Math.max(row.high, b.high);
        row.low = Math.min(row.low, b.low);
        row.close = b.close;
        row.volume = row.volume == null || b.volume == null ? null : row.volume + b.volume;
      }
    }
    out.bars.addAll(grouped.values());
    return out;
  }

  public Map<String, Object> runBacktest(BacktestRequest req, boolean save) {
    if (req == null) throw new IllegalArgumentException("缺少回测请求");
    Instrument i = db.instrument(req.instrumentId);
    Series s = db.series(i.id, "day", false);
    if (s == null) throw new IllegalArgumentException("没有日 K 数据，请先刷新行情或导入");
    Map<String, Object> result = backtest.run(s.bars, instruments.rules(i.market), req, s.source);
    result.put("priceBasis", s.adjustment);
    if (save) {
      long id = db.saveBacktest(req, result);
      result.put("id", id);
    }
    return result;
  }

  private Map<String, Object> screen(Map<String, Object> req) {
    List<Object> rows = new ArrayList<>();
    int min = integer(req, "minSamples", 40);
    double minTrades = number(req, "minCompletedTrades", 5);
    if (minTrades < 0 || minTrades > 10000 || minTrades % 1 != 0)
      throw new IllegalArgumentException("最少完成交易数无效");
    int eligibleCount = 0, profitableCount = 0;
    for (Instrument i : db.instruments()) {
      try {
        BacktestRequest r = convert(req, BacktestRequest.class);
        r.instrumentId = i.id;
        Map<String, Object> result = runBacktest(r, false);
        boolean eligible =
            i.enabled
                && number(result, "sampleCount", 0) >= min
                && number(result, "completedTradeCount", 0) >= Math.max(1, minTrades)
                && "valid".equals(result.get("dataQuality"));
        String status =
            !i.enabled
                ? "disabled"
                : !"valid".equals(result.get("dataQuality"))
                    ? String.valueOf(result.get("dataQuality"))
                    : number(result, "sampleCount", 0) < min
                        ? "insufficient-data"
                        : number(result, "completedTradeCount", 0) == 0
                            ? "no-trades"
                            : number(result, "completedTradeCount", 0) < minTrades
                                ? "insufficient-trades"
                                : number(result, "totalReturn", 0) > 0
                                    ? "profitable"
                                    : "unprofitable";
        Map<String, Object> row = new LinkedHashMap<>(result);
        row.putAll(
            map(
                "instrumentId",
                i.id,
                "name",
                i.name,
                "code",
                i.code,
                "market",
                i.market,
                "currency",
                i.currency,
                "result",
                result,
                "winRate",
                eligible ? result.get("winRate") : null,
                "profitableProbability",
                eligible ? result.get("winRate") : null,
                "status",
                status,
                "eligible",
                eligible,
                "reason",
                eligible ? "历史已完成交易统计，不代表未来概率" : "样本或完成交易不足，或股票已停用"));
        rows.add(row);
        if (eligible) eligibleCount++;
        if (eligible && number(result, "totalReturn", 0) > 0) profitableCount++;
      } catch (IllegalArgumentException e) {
        rows.add(
            map(
                "instrumentId",
                i.id,
                "name",
                i.name,
                "code",
                i.code,
                "market",
                i.market,
                "eligible",
                false,
                "status",
                "data-error",
                "reason",
                e.getMessage()));
      }
    }
    rows.sort(
        (x, y) -> {
          Map<String, Object> p = object(x), q = object(y);
          int primary = Double.compare(number(q, "winRate", -1), number(p, "winRate", -1));
          return primary != 0
              ? primary
              : Double.compare(number(q, "totalReturn", -1), number(p, "totalReturn", -1));
        });
    return map(
        "rows",
        rows,
        "results",
        rows,
        "totalInstruments",
        rows.size(),
        "eligibleInstruments",
        eligibleCount,
        "profitableInstruments",
        profitableCount,
        "source",
        "MySQL 日 K",
        "strategy",
        req.getOrDefault("strategy", "ma"),
        "minSamples",
        min,
        "minCompletedTrades",
        minTrades,
        "warnings",
        Collections.singletonList("排序依据历史已完成交易胜率，不是预测概率"),
        "calculationVersion",
        2);
  }

  private Object scanSingle(Map<String, Object> req, Object input) {
    List<Map<String, Object>> candidates = new ArrayList<>();
    if (input instanceof List) {
      if (((List<?>) input).size() > 64) throw new IllegalArgumentException("最多扫描 64 组参数");
      for (Object item : (List<?>) input) candidates.add(object(item));
    } else candidates = singleCandidates(req);
    List<Object> rows = new ArrayList<>();
    for (Map<String, Object> params : candidates) {
      Map<String, Object> r = new LinkedHashMap<>(req);
      r.put("parameters", params);
      Map<String, Object> result = runBacktest(convert(r, BacktestRequest.class), false);
      Map<String, Object> row = new LinkedHashMap<>(result);
      row.putAll(map("parameters", params, "result", result, "status", result.get("dataQuality")));
      rows.add(row);
    }
    rows.sort(
        (x, y) ->
            Double.compare(
                number(object(y), "totalReturn", -1), number(object(x), "totalReturn", -1)));
    return map(
        "candidates",
        rows,
        "rows",
        rows,
        "calculationVersion",
        2,
        "warning",
        "参数扫描可能过拟合，请用独立时间段检验");
  }

  private List<Map<String, Object>> singleCandidates(Map<String, Object> req) {
    String strategy = String.valueOf(req.getOrDefault("strategy", "ma"));
    List<Map<String, Object>> out = new ArrayList<>();
    if (strategy.equals("ma"))
      for (int fast : new int[] {5, 10, 15})
        for (int slow : new int[] {20, 40, 60})
          if (fast < slow) out.add(map("fastPeriod", fast, "slowPeriod", slow));
          else {
          }
    else if (strategy.equals("rsi"))
      for (int p : new int[] {7, 14, 21})
        out.add(map("period", p, "oversold", 30, "overbought", 70));
    else if (strategy.equals("macd"))
      for (int s : new int[] {20, 26, 35}) out.add(map("fast", 12, "slow", s, "signal", 9));
    else for (int p : new int[] {10, 20, 30}) out.add(map("period", p, "std", 2));
    return out;
  }

  private List<PortfolioEngine.Asset> assets(Map<String, Object> req) {
    List<?> ids =
        req.get("instrumentIds") instanceof List
            ? (List<?>) req.get("instrumentIds")
            : db.instruments().stream()
                .filter(i -> i.enabled)
                .map(i -> i.id)
                .collect(Collectors.toList());
    if (ids.size() > 100) throw new IllegalArgumentException("股票数量超限");
    List<PortfolioEngine.Asset> out = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    for (Object id : ids) {
      long n = ((Number) id).longValue();
      if (!seen.add(n)) throw new IllegalArgumentException("组合股票不能重复");
      Instrument i = db.instrument(n);
      out.add(
          new PortfolioEngine.Asset(i, db.series(n, "day", false), instruments.rules(i.market)));
    }
    return out;
  }

  private PortfolioEngine.Asset benchmark(Map<String, Object> req) {
    if (req.get("benchmarkInstrumentId") == null) return null;
    Instrument i = db.instrument(id(req, "benchmarkInstrumentId"));
    return new PortfolioEngine.Asset(i, db.series(i.id, "day", false), instruments.rules(i.market));
  }

  public Map<String, Object> runPortfolio(Map<String, Object> req, boolean save) {
    Map<String, Object> result = portfolio.run(assets(req), benchmark(req), req);
    if (save) result.put("id", db.saveResearch(req, result));
    return result;
  }

  private Object validateData(Map<String, Object> req) {
    List<Object> rows = new ArrayList<>();
    for (PortfolioEngine.Asset a : assets(req)) {
      Series s = a.series;
      rows.add(
          map(
              "instrumentId",
              a.instrument.id,
              "name",
              a.instrument.name,
              "code",
              a.instrument.code,
              "currency",
              a.instrument.currency,
              "sampleCount",
              s == null ? 0 : s.bars.size(),
              "source",
              s == null ? "无" : s.source,
              "priceBasis",
              s == null ? "unknown" : s.adjustment,
              "status",
              s == null || s.bars.size() < integer(req, "minSamples", 120)
                  ? "insufficient-samples"
                  : s.bars.stream().anyMatch(b -> b.volume == null) ? "missing-volume" : "valid"));
    }
    return map(
        "rows",
        rows,
        "priceBasis",
        req.getOrDefault("priceBasis", "unknown"),
        "calculationVersion",
        2);
  }

  private Object scanPortfolio(Map<String, Object> req) {
    List<Object> rows = new ArrayList<>();
    for (int n : new int[] {1, 3, 5})
      for (String f : Arrays.asList("weekly", "monthly")) {
        Map<String, Object> copy = new LinkedHashMap<>(req),
            factor = object(req.get("factorConfig"));
        factor.put("targetHoldings", n);
        factor.put("rebalanceFrequency", f);
        copy.put("factorConfig", factor);
        try {
          Map<String, Object> result = runPortfolio(copy, false);
          rows.add(map("parameters", factor, "result", result));
        } catch (IllegalArgumentException ex) {
          rows.add(map("parameters", factor, "error", ex.getMessage()));
        }
      }
    return map("rows", rows, "warning", "同区间参数比较存在过拟合风险");
  }

  private Object walkForward(Map<String, Object> envelope) {
    Map<String, Object> req =
        envelope.get("base") == null ? envelope : object(envelope.get("base"));
    List<PortfolioEngine.Asset> assets = assets(req);
    if (assets.isEmpty() || assets.get(0).series == null)
      throw new IllegalArgumentException("缺少日 K");
    int train = integer(envelope, "trainWindow", 504),
        validation = integer(envelope, "validationWindow", 126),
        step = integer(envelope, "step", 63);
    List<Bar> bars = assets.get(0).series.bars;
    List<Object> windows = new ArrayList<>();
    if ((bars.size() - train - validation) / step > 24)
      throw new IllegalArgumentException("最多 24 个滚动窗口，请增加步长");
    for (int end = train; end + validation <= bars.size(); end += step) {
      Map<String, Object> best = null;
      double bestReturn = -Double.MAX_VALUE;
      for (String frequency : Arrays.asList("weekly", "monthly")) {
        Map<String, Object> copy = new LinkedHashMap<>(req),
            factor = object(req.get("factorConfig"));
        factor.put("rebalanceFrequency", frequency);
        copy.put("factorConfig", factor);
        copy.put(
            "startDate",
            Models.date(bars.get(end - train).time, assets.get(0).instrument.market).toString());
        copy.put(
            "endDate",
            Models.date(bars.get(end - 1).time, assets.get(0).instrument.market).toString());
        copy.put("minSamples", Math.min(40, train));
        Map<String, Object> result = portfolio.run(assets, benchmark(req), copy);
        double ret = number(result, "endingEquity", 0) / number(copy, "initialCapital", 100000) - 1;
        if (ret > bestReturn) {
          bestReturn = ret;
          best = copy;
        }
      }
      Map<String, Object> validationRequest = new LinkedHashMap<>(best);
      validationRequest.put(
          "startDate", Models.date(bars.get(end).time, assets.get(0).instrument.market).toString());
      validationRequest.put(
          "endDate",
          Models.date(bars.get(end + validation - 1).time, assets.get(0).instrument.market)
              .toString());
      validationRequest.put("minSamples", Math.min(validation, 40));
      windows.add(
          map(
              "trainingReturn",
              bestReturn,
              "selectedParameters",
              best.get("factorConfig"),
              "validation",
              portfolio.run(assets, benchmark(req), validationRequest)));
    }
    if (windows.isEmpty()) throw new IllegalArgumentException("数据不足一个完整训练和验证窗口");
    return map("windows", windows, "warning", "各窗口独立初始资金；窗口可重叠，收益不可直接相加", "calculationVersion", 2);
  }

  private Object compare(Map<String, Object> req) {
    List<?> ids = (List<?>) req.get("ids");
    if (ids == null) return db.researchRuns();
    return db.researchRuns().stream()
        .filter(
            r ->
                ids.stream()
                    .anyMatch(i -> ((Number) i).longValue() == ((Number) r.get("id")).longValue()))
        .collect(Collectors.toList());
  }

  private Object exportInstrument(Instrument i) {
    List<Series> series = new ArrayList<>();
    for (String p : Arrays.asList("intraday", "day", "week", "month", "year")) {
      Series s = db.series(i.id, p, false);
      if (s != null) series.add(s);
    }
    return map("instrument", i, "series", series);
  }

  private Object diagnostics() {
    return map(
        "application",
        "stocks-monitor-platform",
        "database",
        "MySQL",
        "databaseStatus",
        db.integrity(),
        "instruments",
        db.instruments().size(),
        "time",
        Instant.now().toString(),
        "active",
        db.activeOrNull());
  }
}
