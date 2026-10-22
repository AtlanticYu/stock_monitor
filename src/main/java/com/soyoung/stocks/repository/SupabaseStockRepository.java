package com.soyoung.stocks.repository;

import static com.soyoung.stocks.domain.Json.*;

import com.soyoung.stocks.domain.Models;
import com.soyoung.stocks.domain.Models.*;
import com.soyoung.stocks.storage.SupabaseClient;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
@Profile("supabase")
public class SupabaseStockRepository implements StockRepository {
  private final SupabaseClient client;

  public SupabaseStockRepository(SupabaseClient client) {
    this.client = client;
  }

  private static long now() {
    return System.currentTimeMillis() / 1000;
  }

  private static String eq(Object v) {
    return "eq." + v;
  }

  private static long id(Map<String, Object> row) {
    return ((Number) row.get("id")).longValue();
  }

  private Map<String, Object> first(String table, Map<String, Object> query) {
    query = new LinkedHashMap<>(query);
    query.put("limit", 1);
    List<Map<String, Object>> rows = client.select(table, query);
    return rows.isEmpty() ? null : rows.get(0);
  }

  private Instrument instrumentRow(Map<String, Object> r) {
    Instrument i = new Instrument();
    i.id = id(r);
    i.market = (String) r.get("market");
    i.exchange = (String) r.get("exchange_name");
    i.code = (String) r.get("code");
    i.name = (String) r.get("name");
    i.pageUrl = (String) r.get("page_url");
    i.currency = (String) r.get("currency");
    i.enabled = Boolean.TRUE.equals(r.get("enabled"));
    i.sortOrder = ((Number) r.get("sort_order")).intValue();
    i.nameSource = (String) r.get("name_source");
    i.createdAt = ((Number) r.get("created_at")).longValue();
    i.updatedAt = ((Number) r.get("updated_at")).longValue();
    i.nameUpdatedAt =
        r.get("name_updated_at") == null ? null : ((Number) r.get("name_updated_at")).longValue();
    return i;
  }

  private Map<String, Object> instrumentValues(Instrument i) {
    return map(
        "market",
        i.market,
        "exchange_name",
        i.exchange,
        "code",
        i.code,
        "name",
        i.name,
        "page_url",
        i.pageUrl,
        "currency",
        i.currency,
        "enabled",
        i.enabled,
        "sort_order",
        i.sortOrder,
        "name_source",
        i.nameSource,
        "updated_at",
        now(),
        "name_updated_at",
        i.nameUpdatedAt);
  }

  public List<Instrument> instruments() {
    return client.select("instruments", map("order", "sort_order.asc,id.asc")).stream()
        .map(this::instrumentRow)
        .collect(Collectors.toList());
  }

  public Instrument instrument(long id) {
    Map<String, Object> row = first("instruments", map("id", eq(id)));
    if (row == null) throw new IllegalArgumentException("股票不存在或不属于当前登录用户");
    return instrumentRow(row);
  }

  public Instrument active() {
    Instrument active = activeOrNull();
    if (active == null) throw new IllegalArgumentException("尚未添加已启用股票，请点击左侧 + 添加");
    return active;
  }

  public Instrument activeOrNull() {
    String selected = setting("active_instrument", null);
    if (selected != null)
      try {
        Instrument i = instrument(Long.parseLong(selected));
        if (i.enabled) return i;
      } catch (IllegalArgumentException ignored) {
      }
    return instruments().stream().filter(i -> i.enabled).findFirst().orElse(null);
  }

  public Instrument setActive(long id) {
    Instrument i = instrument(id);
    if (!i.enabled) throw new IllegalArgumentException("股票已停用");
    settingPut("active_instrument", String.valueOf(id));
    return i;
  }

  public long insertInstrument(Instrument i) {
    Map<String, Object> row = instrumentValues(i);
    row.put("created_at", now());
    long id = id(client.insert("instruments", row));
    if (setting("active_instrument", null) == null)
      settingPut("active_instrument", String.valueOf(id));
    return id;
  }

  public void updateInstrument(Instrument i) {
    instrument(i.id);
    client.request(
        "PATCH", "instruments", map("id", eq(i.id)), instrumentValues(i), "return=minimal");
  }

  public void reorder(List<?> ids) {
    for (Object id : ids) instrument(((Number) id).longValue());
    int n = 0;
    for (Object id : ids)
      client.request(
          "PATCH", "instruments", map("id", eq(id)), map("sort_order", n++), "return=minimal");
  }

  public void deleteInstrument(long id) {
    instrument(id);
    client.delete("instruments", map("id", eq(id)));
  }

  public void deleteData(long id) {
    instrument(id);
    for (String table :
        Arrays.asList("series", "quotes", "backtests", "analysis_events", "alert_profiles"))
      client.delete(table, map("instrument_id", eq(id)));
  }

  public String setting(String key, String fallback) {
    Map<String, Object> r = first("settings", map("setting_key", eq(key)));
    return r == null ? fallback : (String) r.get("setting_value");
  }

  public void settingPut(String key, String value) {
    client.upsert(
        "settings", "owner_id,setting_key", map("setting_key", key, "setting_value", value));
  }

  private Series seriesRow(Map<String, Object> r) {
    Series s = new Series();
    s.period = (String) r.get("period");
    s.source = (String) r.get("source");
    s.adjustment = (String) r.get("adjustment");
    s.note = (String) r.get("note");
    s.fetchedAtMs = ((Number) r.get("fetched_at")).longValue();
    s.previousClose =
        r.get("previous_close") == null ? null : ((Number) r.get("previous_close")).doubleValue();
    for (Object b : (List<?>) r.get("bars")) s.bars.add(MAPPER.convertValue(b, Bar.class));
    return s;
  }

  public synchronized void saveSeries(Instrument i, Series s) {
    Models.period(s.period);
    instrument(i.id);
    if (s.bars == null || s.bars.isEmpty() || s.bars.size() > 20000)
      throw new IllegalArgumentException("每批行情须为 1 至 20000 根");
    for (Bar b : s.bars) if (!b.valid()) throw new IllegalArgumentException("非法 OHLCV，已拒绝整批行情");
    Map<String, Object> key = map("instrument_id", eq(i.id), "period", eq(s.period));
    for (int attempt = 0; attempt < 4; attempt++) {
      Map<String, Object> row = first("series", key);
      Series old = row == null ? null : seriesRow(row);
      if (old != null && old.fetchedAtMs > s.fetchedAtMs) return;
      TreeMap<Long, Bar> merged = new TreeMap<>();
      boolean revised =
          old != null
              && (!Objects.equals(old.source, s.source)
                  || !Objects.equals(old.adjustment, s.adjustment));
      if (old != null && !revised && !"intraday".equals(s.period)) {
        Map<Long, Bar> previous = old.bars.stream().collect(Collectors.toMap(b -> b.time, b -> b));
        long last = old.bars.get(old.bars.size() - 1).time;
        for (Bar b : s.bars) {
          Bar p = previous.get(b.time);
          if (p != null
              && b.time < last
              && (p.open != b.open || p.high != b.high || p.low != b.low || p.close != b.close)) {
            revised = true;
            break;
          }
        }
      }
      if (old != null && !revised) for (Bar b : old.bars) merged.put(b.time, b);
      for (Bar b : s.bars) {
        Bar copy = MAPPER.convertValue(b, Bar.class);
        Bar previous = merged.get(copy.time);
        if (copy.volume == null && previous != null) copy.volume = previous.volume;
        merged.put(copy.time, copy);
      }
      if (merged.size() > 50000) throw new IllegalArgumentException("单个行情序列超过 50000 根，请先清理过期数据");
      Map<String, Object> values =
          map(
              "instrument_id",
              i.id,
              "period",
              s.period,
              "source",
              s.source,
              "adjustment",
              s.adjustment,
              "fetched_at",
              s.fetchedAtMs,
              "previous_close",
              s.previousClose != null ? s.previousClose : old == null ? null : old.previousClose,
              "note",
              s.note,
              "bars",
              new ArrayList<>(merged.values()),
              "revision",
              UUID.randomUUID().toString());
      if (row == null) {
        try {
          client.insert("series", values);
          return;
        } catch (DataIntegrityViolationException conflict) {
          continue;
        }
      }
      Map<String, Object> filter = new LinkedHashMap<>(key);
      filter.put("revision", eq(row.get("revision")));
      if (!client.request("PATCH", "series", filter, values, "return=representation").isEmpty())
        return;
    }
    throw new IllegalStateException("行情被并发更新，请重试；已保存的数据保持完整");
  }

  public Series series(long id, String period, boolean latestDay) {
    Models.period(period);
    Map<String, Object> row = first("series", map("instrument_id", eq(id), "period", eq(period)));
    if (row == null) return null;
    Series s = seriesRow(row);
    if (s.bars.isEmpty()) return null;
    if (latestDay && "intraday".equals(period)) {
      String market = instrument(id).market;
      LocalDate last = Models.date(s.bars.get(s.bars.size() - 1).time, market);
      s.bars =
          s.bars.stream()
              .filter(b -> Models.date(b.time, market).equals(last))
              .collect(Collectors.toList());
    }
    return s;
  }

  public List<Map<String, Object>> intraday(long id, String start, String end) {
    if (start != null) LocalDate.parse(start);
    if (end != null) LocalDate.parse(end);
    Instrument i = instrument(id);
    Series s = series(id, "intraday", false);
    List<Map<String, Object>> rows = new ArrayList<>();
    if (s != null)
      for (Bar b : s.bars) {
        String date = Models.date(b.time, i.market).toString();
        if ((start == null || date.compareTo(start) >= 0)
            && (end == null || date.compareTo(end) <= 0))
          rows.add(
              map(
                  "instrumentId",
                  id,
                  "market",
                  i.market,
                  "code",
                  i.code,
                  "tradingDate",
                  date,
                  "timestamp",
                  b.time,
                  "open",
                  b.open,
                  "high",
                  b.high,
                  "low",
                  b.low,
                  "close",
                  b.close,
                  "volume",
                  b.volume,
                  "previousClose",
                  s.previousClose,
                  "source",
                  s.source));
      }
    return rows;
  }

  public Object quote(long id) {
    Map<String, Object> r = first("quotes", map("instrument_id", eq(id)));
    return r == null ? null : r.get("payload");
  }

  public void saveQuote(long id, Object quote) {
    client.upsert(
        "quotes",
        "owner_id,instrument_id",
        map("instrument_id", id, "received_at", System.currentTimeMillis(), "payload", quote));
  }

  public long saveBacktest(BacktestRequest request, Object result) {
    instrument(request.instrumentId);
    return id(
        client.insert(
            "backtests",
            map(
                "instrument_id",
                request.instrumentId,
                "strategy",
                request.strategy,
                "parameters_json",
                request.parameters,
                "request_json",
                request,
                "result_json",
                result,
                "created_at",
                now())));
  }

  private Map<String, Object> backtestRow(Map<String, Object> r) {
    return map(
        "id",
        r.get("id"),
        "instrumentId",
        r.get("instrument_id"),
        "strategy",
        r.get("strategy"),
        "parameters",
        r.get("parameters_json"),
        "request",
        r.get("request_json"),
        "result",
        r.get("result_json"),
        "createdAt",
        r.get("created_at"));
  }

  public Map<String, Object> backtest(long id) {
    Map<String, Object> r = first("backtests", map("id", eq(id)));
    if (r == null) throw new IllegalArgumentException("回测记录不存在");
    return backtestRow(r);
  }

  public List<Map<String, Object>> backtests(Long id) {
    Map<String, Object> q = map("order", "id.desc", "limit", 100);
    if (id != null) q.put("instrument_id", eq(id));
    return client.select("backtests", q).stream()
        .map(this::backtestRow)
        .collect(Collectors.toList());
  }

  private Map<String, Object> researchRow(Map<String, Object> r) {
    return map(
        "id",
        r.get("id"),
        "request",
        r.get("request_json"),
        "result",
        r.get("result_json"),
        "calculationVersion",
        r.get("calculation_version"),
        "createdAt",
        r.get("created_at"));
  }

  public Map<String, Object> researchRun(long id) {
    Map<String, Object> r = first("research_runs", map("id", eq(id)));
    if (r == null) throw new IllegalArgumentException("研究记录不存在");
    return researchRow(r);
  }

  public long saveResearch(Object request, Object result) {
    return id(
        client.insert(
            "research_runs",
            map(
                "request_json",
                request,
                "result_json",
                result,
                "calculation_version",
                2,
                "created_at",
                now())));
  }

  public List<Map<String, Object>> researchRuns() {
    return client.select("research_runs", map("order", "id.desc", "limit", 100)).stream()
        .map(this::researchRow)
        .collect(Collectors.toList());
  }

  public List<Map<String, Object>> strategies() {
    return client.select("strategies", map("order", "id.asc")).stream()
        .map(
            r ->
                map(
                    "id",
                    r.get("id"),
                    "name",
                    r.get("name"),
                    "kind",
                    r.get("kind"),
                    "parameters",
                    r.get("parameters_json")))
        .collect(Collectors.toList());
  }

  public long saveStrategy(String name, String kind, Object parameters) {
    if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("策略名不能为空");
    List<Map<String, Object>> r =
        client.request(
            "POST",
            "strategies",
            map("on_conflict", "owner_id,name"),
            map("name", name, "kind", kind, "parameters_json", parameters, "created_at", now()),
            "resolution=merge-duplicates,return=representation");
    return id(r.get(0));
  }

  public void deleteStrategy(long id) {
    client.delete("strategies", map("id", eq(id)));
  }

  public Object profile(long id) {
    Map<String, Object> r = first("alert_profiles", map("instrument_id", eq(id)));
    return r == null
        ? map(
            "instrumentId",
            id,
            "enabled",
            true,
            "cooldownSeconds",
            900,
            "volumeMultiple",
            1.0,
            "scoreChangeThreshold",
            10,
            "rsiOverbought",
            70,
            "rsiOversold",
            30)
        : r.get("payload");
  }

  public void saveProfile(long id, Object profile) {
    instrument(id);
    client.upsert(
        "alert_profiles", "owner_id,instrument_id", map("instrument_id", id, "payload", profile));
  }

  public List<Map<String, Object>> events(Long id) {
    Map<String, Object> q = map("order", "id.desc", "limit", 200);
    if (id != null) q.put("instrument_id", eq(id));
    return client.select("analysis_events", q).stream()
        .map(
            r -> {
              Map<String, Object> e = new LinkedHashMap<>(object(r.get("payload")));
              e.put("id", r.get("id"));
              e.put("acknowledged", r.get("acknowledged"));
              return e;
            })
        .collect(Collectors.toList());
  }

  public long saveEvent(long id, Object event) {
    return id(
        client.insert(
            "analysis_events", map("instrument_id", id, "event_time", now(), "payload", event)));
  }

  public void ack(long id) {
    client.request(
        "PATCH", "analysis_events", map("id", eq(id)), map("acknowledged", true), "return=minimal");
  }

  public void clearEvents(Long id) {
    client.delete(
        "analysis_events", id == null ? Collections.emptyMap() : map("instrument_id", eq(id)));
  }

  public int cleanup(int days) {
    if (days < 1 || days > 3650) throw new IllegalArgumentException("保留期限须在 1 到 3650 天内");
    long seconds = now() - days * 86400L;
    int n = client.delete("quotes", map("received_at", "lt." + (seconds * 1000)));
    n += client.delete("analysis_events", map("event_time", "lt." + seconds));
    n += client.delete("backtests", map("created_at", "lt." + seconds));
    n += client.delete("research_runs", map("created_at", "lt." + seconds));
    for (Map<String, Object> row :
        client.select("series", map("order", "instrument_id.asc,period.asc"))) {
      Series s = seriesRow(row);
      List<Bar> keep = s.bars.stream().filter(b -> b.time >= seconds).collect(Collectors.toList());
      Map<String, Object> q =
          map(
              "instrument_id",
              eq(row.get("instrument_id")),
              "period",
              eq(s.period),
              "revision",
              eq(row.get("revision")));
      List<Map<String, Object>> changed =
          client.request(
              keep.isEmpty() ? "DELETE" : "PATCH",
              "series",
              q,
              keep.isEmpty() ? null : map("bars", keep, "revision", UUID.randomUUID().toString()),
              "return=representation");
      if (!changed.isEmpty()) n += s.bars.size() - keep.size();
    }
    return n;
  }

  public String integrity() {
    client.select("instruments", map("select", "id", "limit", 1));
    return "ok";
  }

  public String storageDescription() {
    return "Supabase · u_yuzhengxin · 按登录用户隔离";
  }

  public void scheduledScopes(Runnable action) {
    client.forUsers(action);
  }
}
