package com.soyoung.stocks.service;

import static com.soyoung.stocks.domain.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.soyoung.stocks.domain.Models;
import com.soyoung.stocks.domain.Models.*;
import com.soyoung.stocks.repository.StockRepository;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import javax.annotation.PreDestroy;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MarketDataService {
  private final StockRepository db;
  private final EventHub hub;
  private final AnalysisService analysis;
  private final boolean enabled;
  private final OkHttpClient http =
      new OkHttpClient.Builder()
          .connectTimeout(8, TimeUnit.SECONDS)
          .readTimeout(20, TimeUnit.SECONDS)
          .callTimeout(30, TimeUnit.SECONDS)
          .followRedirects(false)
          .build();
  private final TencentHistoryFallback historyFallback = new TencentHistoryFallback(http);
  private final ExecutorService worker = Executors.newFixedThreadPool(2);
  private final Set<Long> busy = ConcurrentHashMap.newKeySet();
  private final Map<Long, Long> cooldown = new ConcurrentHashMap<>();
  private final Map<Long, String> errors = new ConcurrentHashMap<>();
  private final Map<String, String> historyErrors = new ConcurrentHashMap<>();
  private final Map<Long, WebSocket> sockets = new ConcurrentHashMap<>();
  private volatile boolean closing;

  public MarketDataService(
      StockRepository db,
      EventHub hub,
      AnalysisService analysis,
      @Value("${stocks.market-enabled:true}") boolean enabled) {
    this.db = db;
    this.hub = hub;
    this.analysis = analysis;
    this.enabled = enabled;
  }

  public String refresh(Instrument i) {
    if (!enabled) return "自动行情采集已关闭，可导入真实 OHLCV 数据";
    if (System.currentTimeMillis() < cooldown.getOrDefault(i.id, 0L)) return "数据源请求冷却中；保留已有数据";
    if (busy.add(i.id))
      worker.submit(
          () -> {
            try {
              connect(i);
              resolveName(i);
              refreshHistory(i);
            } catch (Exception e) {
              String message = e.getMessage() == null ? "行情请求失败" : e.getMessage();
              errors.put(i.id, message);
              cooldown.put(i.id, System.currentTimeMillis() + 300000);
              hub.emit(
                  "chart-capture-status",
                  map("instrumentId", i.id, "state", "error", "message", message));
              hub.emit(
                  "quote-status", map("instrumentId", i.id, "state", "error", "message", message));
            } finally {
              busy.remove(i.id);
              hub.emit("quote-health", health(i));
            }
          });
    return "已提交服务端采集任务；返回后刷新图表";
  }

  // Each period can succeed independently; a missing intraday series must not block daily history.
  void refreshHistory(Instrument i) {
    boolean failed = false;
    for (String period : Arrays.asList("day", "intraday")) {
      String key = i.id + ":" + period;
      String message = "";
      try {
        fetch(i, period);
        historyErrors.remove(key);
      } catch (Exception e) {
        failed = true;
        message =
            (period.equals("day") ? "日 K：" : "分时：")
                + (e.getMessage() == null ? "行情请求失败" : e.getMessage());
        historyErrors.put(key, message);
      }
      hub.emit(
          "chart-capture-status",
          map(
              "instrumentId",
              i.id,
              "period",
              period,
              "state",
              message.isEmpty() ? "updated" : "error",
              "message",
              message));
    }
    if (failed) cooldown.put(i.id, System.currentTimeMillis() + 300000);
    else cooldown.remove(i.id);
  }

  public String chartWarning(Instrument i, String period) {
    // Weekly/monthly/yearly charts are aggregated from daily history.
    String sourcePeriod = period.equals("intraday") ? "intraday" : "day";
    return historyErrors.getOrDefault(i.id + ":" + sourcePeriod, "");
  }

  @Scheduled(initialDelay = 10000, fixedDelayString = "${stocks.refresh-delay-ms:300000}")
  public void scheduled() {
    if (!enabled || closing) return;
    for (Instrument i : db.instruments()) if (i.enabled) refresh(i);
  }

  public Map<String, Object> health(Instrument i) {
    Series s = db.series(i.id, "day", false);
    Object q = db.quote(i.id);
    long time = q == null ? (s == null ? 0 : s.fetchedAtMs) : node(q).path("receivedAtMs").asLong();
    long age = time == 0 ? 0 : Math.max(0, (System.currentTimeMillis() - time) / 1000);
    String message = errors.get(i.id);
    for (String period : Arrays.asList("day", "intraday")) {
      String warning = historyErrors.get(i.id + ":" + period);
      if (warning != null) message = message == null ? warning : message + "；" + warning;
    }
    return map(
        "instrumentId",
        i.id,
        "generation",
        hub.generation.get(),
        "state",
        message != null
            ? (time == 0 ? "error" : "partial")
            : time == 0 ? "disconnected" : age > 600 ? "stale" : "cached",
        "message",
        message != null ? message : enabled ? "行情服务运行中" : "自动采集已关闭",
        "staleSeconds",
        time == 0 ? null : age,
        "latencyMs",
        null,
        "missingVolume",
        s != null && s.bars.stream().anyMatch(b -> b.volume == null),
        "invalidBars",
        0,
        "session",
        null);
  }

  private void resolveName(Instrument original) {
    if (!"auto".equals(original.nameSource)) return;
    try {
      Request r =
          new Request.Builder().url(original.pageUrl).header("User-Agent", "Mozilla/5.0").build();
      try (Response response = http.newCall(r).execute()) {
        if (!response.isSuccessful() || response.body() == null) return;
        okio.BufferedSource source = response.body().source();
        source.request(2000001);
        if (source.buffer().size() > 2000000) return;
        String html = source.readUtf8();
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
        String title = doc.title();
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile(
                    "^(.{1,80}?)\\s*[（(]" + java.util.regex.Pattern.quote(original.code) + "[)）]",
                    java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(title);
        if (!m.find()) return;
        String name = m.group(1).trim();
        Instrument current = db.instrument(original.id);
        if (!"auto".equals(current.nameSource) || name.isEmpty() || name.contains("百度股市通")) return;
        current.name = name;
        db.updateInstrument(current);
        hub.emit("instrument-name-updated", map("instrumentId", current.id, "instrument", current));
      }
    } catch (Exception ignored) {
    }
  }

  private void connect(Instrument i) {
    if (closing || sockets.containsKey(i.id) || !i.enabled) return;
    Request req =
        new Request.Builder()
            .url("wss://finance-ws.pae.baidu.com/?finClientType=pc")
            .header("Origin", "https://finance.baidu.com")
            .build();
    WebSocket ws =
        http.newWebSocket(
            req,
            new WebSocketListener() {
              public void onOpen(WebSocket w, Response r) {
                w.send(write(subscription(i)));
              }

              public void onMessage(WebSocket w, String text) {
                try {
                  JsonNode root = MAPPER.readTree(text);
                  if (!"0".equals(root.path("resultCode").asText())) return;
                  JsonNode d = root.path("data");
                  if (d.isArray()) {
                    for (JsonNode item : d) quote(i, item);
                  } else quote(i, d);
                } catch (Exception ignored) {
                }
              }

              public void onFailure(WebSocket w, Throwable t, Response r) {
                sockets.remove(i.id);
                errors.put(i.id, "实时连接中断，等待下一轮重连");
              }

              public void onClosed(WebSocket w, int code, String reason) {
                sockets.remove(i.id);
              }
            });
    sockets.put(i.id, ws);
  }

  static Map<String, Object> subscription(Instrument i) {
    return map(
        "method",
        "subscribe",
        "source",
        "pc-web",
        "product",
        "snapshot",
        "items",
        Collections.singletonList(
            map(
                "name",
                i.name,
                "code",
                i.code,
                "financeType",
                InstrumentService.financeType(i),
                "market",
                market(i))));
  }

  private void quote(Instrument i, JsonNode d) {
    if ((d.hasNonNull("financeType")
            && !InstrumentService.financeType(i).equals(d.path("financeType").asText()))
        || !d.path("code").asText().equalsIgnoreCase(i.code)
        || !d.path("market").asText().equals(market(i))) return;
    JsonNode cur = d.path("cur");
    Double price = finite(cur.get("price"));
    if (price == null || price <= 0) return;
    long now = System.currentTimeMillis();
    Map<String, Object> quote =
        map(
            "instrumentId",
            i.id,
            "generation",
            hub.generation.get(),
            "code",
            i.code,
            "name",
            i.name,
            "currency",
            i.currency,
            "price",
            cur.path("price").asText(),
            "increase",
            cur.path("increase").asText(),
            "ratio",
            cur.path("ratio").asText().replace("%", ""),
            "marketTime",
            d.path("update").path("text").asText(),
            "marketStatus",
            d.path("update").path("stockStatus").asText("实时快照"),
            "receivedAtMs",
            now,
            "source",
            "百度 WebSocket",
            "orderBook",
            QuoteParser.orderBook(d),
            "tickTrades",
            QuoteParser.trades(d));
    db.saveQuote(i.id, quote);
    errors.remove(i.id);
    hub.emit("quote-update", quote);
    hub.emit("quote-detail-update", quote);
    hub.emit("quote-status", map("instrumentId", i.id, "state", "connected", "message", "已连接行情快照"));
  }

  private static String historyMarket(Instrument i) {
    return i.market.equals("cn-sh") ? "sh" : i.market.equals("cn-sz") ? "sz" : market(i);
  }

  private static String market(Instrument i) {
    return i.market.startsWith("cn") ? "ab" : i.market;
  }

  static HttpUrl historyUrl(Instrument i, String period) {
    Models.period(period);
    HttpUrl.Builder url =
        HttpUrl.parse("https://finance.pae.baidu.com/vapi/v1/getquotation").newBuilder();
    String[][] params = {
      {"srcid", "5353"},
      {"all", "1"},
      {"code", i.code},
      {"query", i.code},
      {"financeType", InstrumentService.financeType(i)},
      {
        "group",
        ("intraday".equals(period) ? "quotation_minute_" : "quotation_kline_") + historyMarket(i)
      },
      {"stock_type", historyMarket(i)},
      {"newFormat", "1"},
      {"pointType", "string"},
      {"finClientType", "pc"}
    };
    for (String[] p : params) url.addQueryParameter(p[0], p[1]);
    if (period.equals("intraday")) {
      url.addQueryParameter("eprop", "min");
      url.addQueryParameter("chartType", "minute");
    } else {
      // Baidu uses numeric K-line types (1=daily, 2=weekly, 3=monthly, 4=yearly).
      int ktype = Arrays.asList("day", "week", "month", "year").indexOf(period) + 1;
      url.addQueryParameter("ktype", String.valueOf(ktype));
    }
    return url.build();
  }

  public Series fetch(Instrument i, String period) throws Exception {
    Series series;
    try {
      series = fetchBaidu(i, period);
    } catch (Exception primary) {
      if (!TencentHistoryFallback.supports(i) || !"day".equals(period)) throw primary;
      try {
        series = historyFallback.fetch(i);
      } catch (Exception fallback) {
        throw new IllegalStateException(
            primary.getMessage() + "；备用历史源也失败：" + fallback.getMessage(), primary);
      }
    }
    db.saveSeries(i, series);
    hub.emit("chart-data-updated", map("instrumentId", i.id, "period", period));
    analysis.publish(i);
    return series;
  }

  private Series fetchBaidu(Instrument i, String period) throws Exception {
    Request request =
        new Request.Builder()
            .url(historyUrl(i, period))
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", i.pageUrl)
            .build();
    try (Response response = http.newCall(request).execute()) {
      ResponseBody body = response.body();
      if (body == null || body.contentLength() > 8000000)
        throw new IllegalStateException("行情响应过大或为空");
      okio.BufferedSource responseSource = body.source();
      responseSource.request(8000001);
      if (responseSource.buffer().size() > 8000000) throw new IllegalStateException("行情响应超过 8 MB");
      return parseResponse(response.code(), responseSource.readByteArray(), i, period);
    }
  }

  static Series parseResponse(int status, byte[] body, Instrument i, String period)
      throws Exception {
    JsonNode root = null;
    try {
      root = MAPPER.readTree(body);
    } catch (java.io.IOException e) {
      if (status >= 200 && status < 300)
        throw new IllegalArgumentException("百度行情响应不是有效 JSON；缓存保留", e);
    }
    if (root != null && root.path("Result").path("isCaptchaEnabled").asBoolean())
      throw new IllegalArgumentException("百度历史行情要求验证码，自动同步受限；可稍后重试或在设置中导入行情 JSON，已有数据保留");
    if (status < 200 || status >= 300)
      throw new IllegalStateException("百度行情返回 HTTP " + status + "；缓存保留");
    if (root == null) throw new IllegalArgumentException("百度历史行情响应为空；缓存保留");
    return parse(root, i, period);
  }

  public static Series parse(JsonNode root, Instrument i, String period) {
    if (!"0".equals(root.path("ResultCode").asText()))
      throw new IllegalArgumentException("历史行情业务错误");
    JsonNode result = root.path("Result");
    if (result.path("isCaptchaEnabled").asBoolean() || result.path("code").asInt() == 403)
      throw new IllegalArgumentException("行情源要求验证码；服务端无法完成网页验证，缓存保留");
    List<JsonNode> rows = new ArrayList<>();
    rows(result.has("newMarketData") ? result.get("newMarketData") : result, null, rows, 0);
    Series s = new Series();
    s.period = period;
    s.source = "百度历史接口";
    s.adjustment = "unknown";
    s.note = "接口默认价格口径，复权未经确认；最后一根可能尚未完成";
    s.fetchedAtMs = System.currentTimeMillis();
    TreeMap<Long, Bar> values = new TreeMap<>();
    for (JsonNode row : rows)
      try {
        boolean minute = period.equals("intraday");
        JsonNode t = minute ? row.path("timestamp") : row.path("time");
        if (t.isMissingNode()) t = minute ? row.path("time") : row.path("timestamp");
        long time = timestamp(t.asText(), i.market);
        if (minute) time = time / 60 * 60;
        else
          time =
              Models.date(time, i.market)
                  .atTime(12, 0)
                  .atZone(Models.zone(i.market))
                  .toEpochSecond();
        Double close = finite(row.get("close"));
        if (close == null && minute) close = finite(row.get("price"));
        Double open = minute ? close : finite(row.get("open")),
            hi = minute ? close : finite(row.get("high")),
            lo = minute ? close : finite(row.get("low"));
        if (close == null || open == null || hi == null || lo == null) continue;
        Bar b = new Bar(time, open, hi, lo, close, finite(row.get("volume")));
        if (b.valid()) values.put(time, b);
      } catch (RuntimeException ignored) {
      }
    s.bars = new ArrayList<>(values.values());
    if (s.bars.isEmpty()) throw new IllegalArgumentException("行情为空或 OHLC 字段无效，缓存保留");
    return s;
  }

  private static void rows(JsonNode n, JsonNode inherited, List<JsonNode> out, int depth) {
    if (depth > 8 || out.size() > 20000) throw new IllegalArgumentException("行情超过限制");
    JsonNode keys = n.has("keys") ? n.get("keys") : inherited;
    JsonNode d = n.has("marketData") ? n.get("marketData") : n.has("p") ? n.get("p") : n;
    if (d.isTextual()) {
      if (keys == null || !keys.isArray()) throw new IllegalArgumentException("历史行情缺少列名");
      for (String line : d.asText().split(";")) {
        if (line.trim().isEmpty()) continue;
        String[] cols = line.split(",", -1);
        if (cols.length != keys.size()) throw new IllegalArgumentException("历史行情列数不一致");
        com.fasterxml.jackson.databind.node.ObjectNode obj = MAPPER.createObjectNode();
        for (int k = 0; k < cols.length; k++) obj.put(keys.get(k).asText(), cols[k]);
        out.add(obj);
      }
    } else if (d.isArray()) for (JsonNode item : d) rows(item, keys, out, depth + 1);
    else if (d.has("timestamp") || d.has("time")) out.add(d);
    else throw new IllegalArgumentException("无法识别行情格式");
  }

  static long timestamp(String text, String market) {
    if (text.matches("\\d{10,13}")) {
      long t = Long.parseLong(text);
      return t > 100000000000L ? t / 1000 : t;
    }
    String t = text.trim().replace('T', ' ');
    if (t.matches("\\d{8}"))
      return LocalDate.parse(t, DateTimeFormatter.BASIC_ISO_DATE)
          .atTime(12, 0)
          .atZone(Models.zone(market))
          .toEpochSecond();
    if (t.length() == 10)
      return LocalDate.parse(t).atTime(12, 0).atZone(Models.zone(market)).toEpochSecond();
    return LocalDateTime.parse(
            t.length() == 16 ? t + ":00" : t, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        .atZone(Models.zone(market))
        .toEpochSecond();
  }

  public void stop(long id) {
    WebSocket socket = sockets.remove(id);
    if (socket != null) socket.close(1000, "instrument disabled");
  }

  @PreDestroy
  public void close() {
    closing = true;
    for (WebSocket ws : sockets.values()) ws.close(1001, "shutdown");
    worker.shutdownNow();
    http.dispatcher().executorService().shutdown();
    http.connectionPool().evictAll();
  }
}
