package com.soyoung.stocks.repository;

import static com.soyoung.stocks.domain.Json.*;

import com.soyoung.stocks.domain.Models;
import com.soyoung.stocks.domain.Models.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("!supabase")
public class JdbcStockRepository implements StockRepository {
  private final JdbcTemplate jdbc;

  public JdbcStockRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private final RowMapper<Instrument> instrumentMapper =
      (r, n) -> {
        Instrument i = new Instrument();
        i.id = r.getLong("id");
        i.market = r.getString("market");
        i.exchange = r.getString("exchange_name");
        i.code = r.getString("code");
        i.name = r.getString("name");
        i.pageUrl = r.getString("page_url");
        i.currency = r.getString("currency");
        i.enabled = r.getBoolean("enabled");
        i.sortOrder = r.getInt("sort_order");
        i.nameSource = r.getString("name_source");
        i.createdAt = r.getLong("created_at");
        i.updatedAt = r.getLong("updated_at");
        i.nameUpdatedAt = (Long) r.getObject("name_updated_at");
        return i;
      };

  public List<Instrument> instruments() {
    return jdbc.query("SELECT * FROM instruments ORDER BY sort_order,id", instrumentMapper);
  }

  public Instrument instrument(long id) {
    List<Instrument> rows =
        jdbc.query("SELECT * FROM instruments WHERE id=?", instrumentMapper, id);
    if (rows.isEmpty()) throw new IllegalArgumentException("股票不存在");
    return rows.get(0);
  }

  public Instrument active() {
    String id = setting("active_instrument", null);
    if (id != null) {
      try {
        Instrument i = instrument(Long.parseLong(id));
        if (i.enabled) return i;
      } catch (IllegalArgumentException ignored) {
      }
    }
    return instruments().stream()
        .filter(i -> i.enabled)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("尚未添加已启用股票，请点击左侧 + 添加"));
  }

  public Instrument activeOrNull() {
    try {
      return active();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  public Instrument setActive(long id) {
    Instrument i = instrument(id);
    if (!i.enabled) throw new IllegalArgumentException("股票已停用");
    settingPut("active_instrument", String.valueOf(id));
    return i;
  }

  public long insertInstrument(Instrument i) {
    long now = System.currentTimeMillis() / 1000;
    long id =
        insert(
            "INSERT INTO instruments(market,exchange_name,code,name,page_url,currency,enabled,sort_order,name_source,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            i.market,
            i.exchange,
            i.code,
            i.name,
            i.pageUrl,
            i.currency,
            i.enabled,
            i.sortOrder,
            i.nameSource,
            now,
            now);
    if (setting("active_instrument", null) == null)
      settingPut("active_instrument", String.valueOf(id));
    return id;
  }

  public void updateInstrument(Instrument i) {
    jdbc.update(
        "UPDATE instruments SET market=?,exchange_name=?,code=?,name=?,page_url=?,currency=?,enabled=?,name_source=?,updated_at=? WHERE id=?",
        i.market,
        i.exchange,
        i.code,
        i.name,
        i.pageUrl,
        i.currency,
        i.enabled,
        i.nameSource,
        System.currentTimeMillis() / 1000,
        i.id);
  }

  @Transactional
  public void reorder(List<?> ids) {
    int order = 0;
    for (Object id : ids)
      jdbc.update(
          "UPDATE instruments SET sort_order=? WHERE id=?", order++, ((Number) id).longValue());
  }

  public void deleteInstrument(long id) {
    jdbc.update("DELETE FROM instruments WHERE id=?", id);
  }

  @Transactional
  public void deleteData(long id) {
    for (String table :
        Arrays.asList(
            "bars", "series_meta", "quotes", "backtests", "analysis_events", "alert_profiles"))
      jdbc.update("DELETE FROM " + table + " WHERE instrument_id=?", id);
  }

  public String setting(String key, String fallback) {
    List<String> values =
        jdbc.query(
            "SELECT setting_value FROM app_settings WHERE setting_key=?",
            (r, n) -> r.getString(1),
            key);
    return values.isEmpty() ? fallback : values.get(0);
  }

  @Transactional
  public synchronized void settingPut(String key, String value) {
    if (jdbc.update("UPDATE app_settings SET setting_value=? WHERE setting_key=?", value, key) == 0)
      jdbc.update("INSERT INTO app_settings(setting_key,setting_value) VALUES(?,?)", key, value);
  }

  @Transactional
  public synchronized void saveSeries(Instrument i, Series s) {
    Models.period(s.period);
    if (s.bars.isEmpty()) throw new IllegalArgumentException("行情为空，旧缓存保留");
    TreeMap<Long, Bar> incoming = new TreeMap<>();
    for (Bar b : s.bars) {
      if (!b.valid()) throw new IllegalArgumentException("非法 OHLCV，已拒绝整批行情");
      incoming.put(b.time, b);
    }
    s.bars = new ArrayList<>(incoming.values());
    Series old = series(i.id, s.period, false);
    if (old != null && old.fetchedAtMs > s.fetchedAtMs) return;
    boolean revised =
        old != null
            && (!Objects.equals(old.adjustment, s.adjustment)
                || !Objects.equals(old.source, s.source));
    if (old != null && !"intraday".equals(s.period)) {
      Map<Long, Bar> previous = old.bars.stream().collect(Collectors.toMap(b -> b.time, b -> b));
      for (Bar b : s.bars) {
        Bar p = previous.get(b.time);
        if (p != null
            && b.time < old.bars.get(old.bars.size() - 1).time
            && (p.open != b.open || p.high != b.high || p.low != b.low || p.close != b.close)) {
          revised = true;
          break;
        }
      }
    }
    if (revised) jdbc.update("DELETE FROM bars WHERE instrument_id=? AND period=?", i.id, s.period);
    for (Bar b : s.bars) {
      String date = Models.date(b.time, i.market).toString();
      int count =
          jdbc.update(
              "UPDATE bars SET open_price=?,high_price=?,low_price=?,close_price=?,volume=COALESCE(?,volume),source=?,adjustment=?,fetched_at=?,previous_close=COALESCE(?,previous_close) WHERE instrument_id=? AND period=? AND bar_time=?",
              b.open,
              b.high,
              b.low,
              b.close,
              b.volume,
              s.source,
              s.adjustment,
              s.fetchedAtMs,
              s.previousClose,
              i.id,
              s.period,
              b.time);
      if (count == 0)
        jdbc.update(
            "INSERT INTO bars(instrument_id,period,bar_time,trading_date,open_price,high_price,low_price,close_price,volume,source,adjustment,fetched_at,previous_close) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            i.id,
            s.period,
            b.time,
            date,
            b.open,
            b.high,
            b.low,
            b.close,
            b.volume,
            s.source,
            s.adjustment,
            s.fetchedAtMs,
            s.previousClose);
    }
    if (jdbc.update(
            "UPDATE series_meta SET source=?,adjustment=?,fetched_at=?,previous_close=?,note=? WHERE instrument_id=? AND period=?",
            s.source,
            s.adjustment,
            s.fetchedAtMs,
            s.previousClose,
            s.note,
            i.id,
            s.period)
        == 0)
      jdbc.update(
          "INSERT INTO series_meta(instrument_id,period,source,adjustment,fetched_at,previous_close,note) VALUES(?,?,?,?,?,?,?)",
          i.id,
          s.period,
          s.source,
          s.adjustment,
          s.fetchedAtMs,
          s.previousClose,
          s.note);
  }

  public Series series(long id, String period, boolean latestDay) {
    Models.period(period);
    List<Series> metadata =
        jdbc.query(
            "SELECT * FROM series_meta WHERE instrument_id=? AND period=?",
            (r, n) -> {
              Series s = new Series();
              s.period = period;
              s.source = r.getString("source");
              s.adjustment = r.getString("adjustment");
              s.fetchedAtMs = r.getLong("fetched_at");
              s.previousClose = (Double) r.getObject("previous_close");
              s.note = r.getString("note");
              return s;
            },
            id,
            period);
    if (metadata.isEmpty()) return null;
    Series s = metadata.get(0);
    s.bars =
        jdbc.query(
            "SELECT * FROM bars WHERE instrument_id=? AND period=? ORDER BY bar_time",
            (r, n) ->
                new Bar(
                    r.getLong("bar_time"),
                    r.getDouble("open_price"),
                    r.getDouble("high_price"),
                    r.getDouble("low_price"),
                    r.getDouble("close_price"),
                    (Double) r.getObject("volume")),
            id,
            period);
    if (s.bars.isEmpty()) return null;
    if (latestDay && "intraday".equals(period)) {
      String market = instrument(id).market;
      java.time.LocalDate last = Models.date(s.bars.get(s.bars.size() - 1).time, market);
      s.bars =
          s.bars.stream()
              .filter(b -> Models.date(b.time, market).equals(last))
              .collect(Collectors.toList());
    }
    return s;
  }

  public List<Map<String, Object>> intraday(long id, String start, String end) {
    List<Object> args = new ArrayList<>();
    args.add(id);
    String sql = "SELECT * FROM bars WHERE instrument_id=? AND period='intraday'";
    if (start != null) {
      java.time.LocalDate.parse(start);
      sql += " AND trading_date>=?";
      args.add(start);
    }
    if (end != null) {
      java.time.LocalDate.parse(end);
      sql += " AND trading_date<=?";
      args.add(end);
    }
    Instrument i = instrument(id);
    return jdbc.query(
        sql + " ORDER BY bar_time",
        (r, n) ->
            map(
                "instrumentId",
                id,
                "market",
                i.market,
                "code",
                i.code,
                "tradingDate",
                r.getDate("trading_date").toString(),
                "timestamp",
                r.getLong("bar_time"),
                "open",
                r.getDouble("open_price"),
                "high",
                r.getDouble("high_price"),
                "low",
                r.getDouble("low_price"),
                "close",
                r.getDouble("close_price"),
                "volume",
                r.getObject("volume"),
                "previousClose",
                r.getObject("previous_close"),
                "source",
                r.getString("source")),
        args.toArray());
  }

  public Object quote(long id) {
    List<String> values =
        jdbc.query(
            "SELECT payload FROM quotes WHERE instrument_id=?", (r, n) -> r.getString(1), id);
    return values.isEmpty() ? null : read(values.get(0));
  }

  @Transactional
  public synchronized void saveQuote(long id, Object quote) {
    String payload = write(quote);
    long now = System.currentTimeMillis();
    if (jdbc.update(
            "UPDATE quotes SET received_at=?,payload=? WHERE instrument_id=?", now, payload, id)
        == 0)
      jdbc.update(
          "INSERT INTO quotes(instrument_id,received_at,payload) VALUES(?,?,?)", id, now, payload);
  }

  public long saveBacktest(BacktestRequest req, Object result) {
    return insert(
        "INSERT INTO backtests(instrument_id,strategy,parameters_json,request_json,result_json,created_at) VALUES(?,?,?,?,?,?)",
        req.instrumentId,
        req.strategy,
        write(req.parameters),
        write(req),
        write(result),
        System.currentTimeMillis() / 1000);
  }

  public Map<String, Object> backtest(long id) {
    List<Map<String, Object>> rows =
        jdbc.query(
            "SELECT * FROM backtests WHERE id=?",
            (r, n) ->
                map(
                    "id",
                    r.getLong("id"),
                    "instrumentId",
                    r.getLong("instrument_id"),
                    "strategy",
                    r.getString("strategy"),
                    "parameters",
                    read(r.getString("parameters_json")),
                    "request",
                    read(r.getString("request_json")),
                    "result",
                    read(r.getString("result_json")),
                    "createdAt",
                    r.getLong("created_at")),
            id);
    if (rows.isEmpty()) throw new IllegalArgumentException("回测记录不存在");
    return rows.get(0);
  }

  public Map<String, Object> researchRun(long id) {
    List<Map<String, Object>> rows =
        jdbc.query(
            "SELECT * FROM research_runs WHERE id=?",
            (r, n) ->
                map(
                    "id",
                    r.getLong("id"),
                    "request",
                    read(r.getString("request_json")),
                    "result",
                    read(r.getString("result_json")),
                    "calculationVersion",
                    r.getInt("calculation_version"),
                    "createdAt",
                    r.getLong("created_at")),
            id);
    if (rows.isEmpty()) throw new IllegalArgumentException("研究记录不存在");
    return rows.get(0);
  }

  public List<Map<String, Object>> backtests(Long id) {
    return jdbc.query(
        "SELECT * FROM backtests"
            + (id == null ? "" : " WHERE instrument_id=?")
            + " ORDER BY id DESC LIMIT 100",
        (r, n) ->
            map(
                "id",
                r.getLong("id"),
                "instrumentId",
                r.getLong("instrument_id"),
                "strategy",
                r.getString("strategy"),
                "parameters",
                read(r.getString("parameters_json")),
                "result",
                read(r.getString("result_json")),
                "createdAt",
                r.getLong("created_at")),
        id == null ? new Object[] {} : new Object[] {id});
  }

  public long saveResearch(Object request, Object result) {
    return insert(
        "INSERT INTO research_runs(request_json,result_json,calculation_version,created_at) VALUES(?,?,?,?)",
        write(request),
        write(result),
        2,
        System.currentTimeMillis() / 1000);
  }

  public List<Map<String, Object>> researchRuns() {
    return jdbc.query(
        "SELECT * FROM research_runs ORDER BY id DESC LIMIT 100",
        (r, n) ->
            map(
                "id",
                r.getLong("id"),
                "request",
                read(r.getString("request_json")),
                "result",
                read(r.getString("result_json")),
                "calculationVersion",
                r.getInt("calculation_version"),
                "createdAt",
                r.getLong("created_at")));
  }

  public List<Map<String, Object>> strategies() {
    return jdbc.query(
        "SELECT * FROM strategies ORDER BY id",
        (r, n) ->
            map(
                "id",
                r.getLong("id"),
                "name",
                r.getString("name"),
                "kind",
                r.getString("kind"),
                "parameters",
                read(r.getString("parameters_json"))));
  }

  public long saveStrategy(String name, String kind, Object parameters) {
    if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("策略名不能为空");
    jdbc.update("DELETE FROM strategies WHERE name=?", name);
    return insert(
        "INSERT INTO strategies(name,kind,parameters_json,created_at) VALUES(?,?,?,?)",
        name,
        kind,
        write(parameters),
        System.currentTimeMillis() / 1000);
  }

  public void deleteStrategy(long id) {
    jdbc.update("DELETE FROM strategies WHERE id=?", id);
  }

  public Object profile(long id) {
    List<String> values =
        jdbc.query(
            "SELECT payload FROM alert_profiles WHERE instrument_id=?",
            (r, n) -> r.getString(1),
            id);
    return values.isEmpty()
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
        : read(values.get(0));
  }

  @Transactional
  public synchronized void saveProfile(long id, Object profile) {
    instrument(id);
    if (jdbc.update("UPDATE alert_profiles SET payload=? WHERE instrument_id=?", write(profile), id)
        == 0)
      jdbc.update(
          "INSERT INTO alert_profiles(instrument_id,payload) VALUES(?,?)", id, write(profile));
  }

  public List<Map<String, Object>> events(Long id) {
    return jdbc.query(
        "SELECT * FROM analysis_events"
            + (id == null ? "" : " WHERE instrument_id=?")
            + " ORDER BY id DESC LIMIT 200",
        (r, n) -> {
          Map<String, Object> e = object(read(r.getString("payload")));
          e.put("id", r.getLong("id"));
          e.put("acknowledged", r.getBoolean("acknowledged"));
          return e;
        },
        id == null ? new Object[] {} : new Object[] {id});
  }

  public long saveEvent(long id, Object event) {
    return insert(
        "INSERT INTO analysis_events(instrument_id,event_time,payload) VALUES(?,?,?)",
        id,
        System.currentTimeMillis() / 1000,
        write(event));
  }

  public void ack(long id) {
    jdbc.update("UPDATE analysis_events SET acknowledged=TRUE WHERE id=?", id);
  }

  public void clearEvents(Long id) {
    if (id == null) jdbc.update("DELETE FROM analysis_events");
    else jdbc.update("DELETE FROM analysis_events WHERE instrument_id=?", id);
  }

  @Transactional
  public int cleanup(int days) {
    if (days < 1 || days > 3650) throw new IllegalArgumentException("保留期限须在 1 到 3650 天内");
    long seconds = System.currentTimeMillis() / 1000 - days * 86400L;
    int n = jdbc.update("DELETE FROM quotes WHERE received_at<?", seconds * 1000);
    n += jdbc.update("DELETE FROM bars WHERE bar_time<?", seconds);
    n += jdbc.update("DELETE FROM analysis_events WHERE event_time<?", seconds);
    n += jdbc.update("DELETE FROM backtests WHERE created_at<?", seconds);
    n += jdbc.update("DELETE FROM research_runs WHERE created_at<?", seconds);
    jdbc.update(
        "DELETE FROM series_meta WHERE NOT EXISTS (SELECT 1 FROM bars WHERE bars.instrument_id=series_meta.instrument_id AND bars.period=series_meta.period)");
    return n;
  }

  public String integrity() {
    jdbc.queryForObject("SELECT 1", Integer.class);
    return "ok";
  }

  private long insert(String sql, Object... args) {
    KeyHolder holder = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
          for (int n = 0; n < args.length; n++) p.setObject(n + 1, args[n]);
          return p;
        },
        holder);
    Number key = holder.getKey();
    if (key == null) throw new IllegalStateException("没有返回记录 ID");
    return key.longValue();
  }
}
