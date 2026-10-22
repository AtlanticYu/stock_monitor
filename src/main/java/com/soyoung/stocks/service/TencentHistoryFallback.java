package com.soyoung.stocks.service;

import static com.soyoung.stocks.domain.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.soyoung.stocks.domain.Models.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.*;

/** Daily history fallback for US, Hong Kong and Shanghai/Shenzhen stocks. */
final class TencentHistoryFallback {
  private final OkHttpClient http;
  private final Map<String, String> symbols = new ConcurrentHashMap<>();

  TencentHistoryFallback(OkHttpClient http) {
    this.http = http;
  }

  Series fetch(Instrument instrument) throws Exception {
    String base = baseSymbol(instrument);
    String symbol = symbols.getOrDefault(base, base);
    JsonNode root = request(symbol);
    // Tencent's canonical US symbol includes the exchange suffix (e.g. NVDA.OQ).
    // Resolve it from the provider's quote metadata instead of assuming every stock is Nasdaq.
    String canonical =
        "us".equals(instrument.market) && "stock".equals(InstrumentService.financeType(instrument))
            ? canonicalSymbol(root, symbol, instrument.code)
            : symbol;
    if (!canonical.equals(symbol)) {
      root = request(canonical);
      symbol = canonical;
    }
    Series series = parse(root, symbol, instrument);
    symbols.put(base, symbol);
    return series;
  }

  static boolean supports(Instrument instrument) {
    try {
      baseSymbol(instrument);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  static String baseSymbol(Instrument instrument) {
    String market = instrument.market, code = instrument.code;
    if ("index".equals(InstrumentService.financeType(instrument))) {
      if ("us".equals(market)) {
        if (Arrays.asList("IXIC", "DJI", "INX").contains(code)) return "us." + code;
        throw new IllegalArgumentException("备用历史源暂不支持该美股指数");
      }
      if ("cn-ab".equals(market)) throw new IllegalArgumentException("指数备用历史源需要明确的 sh 或 sz 市场代码");
    }
    if ("us".equals(market)) return "us" + code;
    if ("hk".equals(market))
      return "hk" + code; // Preserve five-digit codes, including leading zeros.
    if ("cn-sh".equals(market)) return "sh" + code;
    if ("cn-sz".equals(market)) return "sz" + code;
    if ("cn-ab".equals(market)) {
      if (code.startsWith("6") || code.startsWith("900")) return "sh" + code;
      if (code.startsWith("0") || code.startsWith("2") || code.startsWith("3")) return "sz" + code;
    }
    throw new IllegalArgumentException("备用历史源无法确定该股票的交易所，请使用明确的沪深市场代码");
  }

  private JsonNode request(String symbol) throws Exception {
    HttpUrl url =
        HttpUrl.parse("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get")
            .newBuilder()
            .addQueryParameter("param", symbol + ",day,,,640,qfq")
            .build();
    Request request =
        new Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://gu.qq.com/")
            .build();
    try (Response response = http.newCall(request).execute()) {
      if (!response.isSuccessful())
        throw new IllegalStateException("腾讯历史接口返回 HTTP " + response.code());
      ResponseBody body = response.body();
      if (body == null || body.contentLength() > 8000000)
        throw new IllegalArgumentException("腾讯历史响应过大或为空");
      okio.BufferedSource source = body.source();
      source.request(8000001);
      if (source.buffer().size() > 8000000) throw new IllegalArgumentException("腾讯历史响应超过 8 MB");
      return MAPPER.readTree(source.readByteArray());
    }
  }

  static String canonicalSymbol(JsonNode root, String symbol, String code) {
    JsonNode quote = root.path("data").path(symbol).path("qt").path(symbol);
    String canonical = quote.path(2).asText("").toUpperCase(Locale.ROOT);
    if (canonical.matches(
        java.util.regex.Pattern.quote(code.toUpperCase(Locale.ROOT)) + "\\.[A-Z]{1,4}"))
      return "us" + canonical;
    return symbol;
  }

  private static Double volumeScale(JsonNode data, String symbol, Instrument instrument) {
    if (!instrument.market.startsWith("cn-")) return 1.0;
    JsonNode quote = data.path("qt").path(symbol);
    Double volume = finite(quote.path(36)),
        amountWan = finite(quote.path(37)),
        high = finite(quote.path(33)),
        low = finite(quote.path(34));
    if (volume == null
        || amountWan == null
        || high == null
        || low == null
        || volume <= 0
        || amountWan <= 0
        || low <= 0
        || high < low) return null;
    // Mainland snapshot turnover is in CNY 10,000; volume may be shares or 100-share lots.
    // Confirm the scale against that session's price range, independently of adjusted candle
    // prices.
    double amount = amountWan * 10000;
    boolean shares = volume * low <= amount * 1.05 && volume * high >= amount * 0.95;
    boolean lots = volume * 100 * low <= amount * 1.05 && volume * 100 * high >= amount * 0.95;
    return shares == lots ? null : shares ? 1.0 : 100.0;
  }

  static Series parse(JsonNode root, String symbol, Instrument instrument) {
    String base = baseSymbol(instrument);
    boolean validSymbol =
        "us".equals(instrument.market)
            ? symbol.matches(java.util.regex.Pattern.quote(base) + "(?:\\.[A-Z]{1,4})?")
            : base.equals(symbol);
    if (!validSymbol) throw new IllegalArgumentException("备用行情股票代码或市场不匹配");
    if (root == null || root.path("code").asInt(-1) != 0)
      throw new IllegalArgumentException("腾讯历史行情业务错误");
    JsonNode data = root.path("data").path(symbol);
    boolean adjusted = data.path("qfqday").isArray() && data.path("qfqday").size() > 0;
    JsonNode rows = data.path(adjusted ? "qfqday" : "day");
    if (!rows.isArray() || rows.size() > 20000) throw new IllegalArgumentException("腾讯历史行情格式无效");
    Double volumeScale = volumeScale(data, symbol, instrument);
    TreeMap<Long, Bar> bars = new TreeMap<>();
    for (JsonNode row : rows) {
      if (!row.isArray() || row.size() < 5) continue;
      try {
        Double open = finite(row.get(1)),
            close = finite(row.get(2)),
            high = finite(row.get(3)),
            low = finite(row.get(4));
        if (open == null || close == null || high == null || low == null) continue;
        Double volume = finite(row.get(5));
        if (volume != null) volume = volumeScale == null ? null : volume * volumeScale;
        Bar bar =
            new Bar(
                MarketDataService.timestamp(row.get(0).asText(), instrument.market),
                open,
                high,
                low,
                close,
                volume);
        if (bar.valid()) bars.put(bar.time, bar);
      } catch (RuntimeException ignored) {
      }
    }
    if (bars.isEmpty()) throw new IllegalArgumentException("腾讯历史行情为空或 OHLC 字段无效");
    Series series = new Series();
    series.period = "day";
    String marketName =
        "us".equals(instrument.market) ? "美股" : "hk".equals(instrument.market) ? "港股" : "A 股";
    series.source = "腾讯" + marketName + "历史接口";
    series.adjustment = adjusted ? "qfq" : "unknown";
    series.note =
        "百度历史接口暂不可用，使用备用"
            + marketName
            + "日 K；"
            + (adjusted ? "数据源标记为前复权。" : "数据源默认价格口径，复权未经确认。")
            + "请求最近 640 根，数据源可能附带当日未完成 K 线。"
            + (volumeScale == null ? "成交量单位无法确认，已留空。" : "成交量统一为股。");
    series.bars = new ArrayList<>(bars.values());
    series.fetchedAtMs = System.currentTimeMillis();
    return series;
  }
}
