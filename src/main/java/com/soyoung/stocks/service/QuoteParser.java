package com.soyoung.stocks.service;

import static com.soyoung.stocks.domain.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Conservative order-book/trade decoding. Missing fields remain null. */
public final class QuoteParser {
  private QuoteParser() {}

  private static Double first(JsonNode n, String... keys) {
    for (String key : keys) {
      Double x = finite(n.get(key));
      if (x != null && x >= 0) return x;
    }
    return null;
  }

  private static String text(JsonNode n, String... keys) {
    for (String key : keys) if (n.hasNonNull(key)) return n.get(key).asText();
    return "";
  }

  private static String norm(String s) {
    return s.toLowerCase(Locale.ROOT).replaceAll("[_ -]", "");
  }

  private static String side(String s) {
    s = norm(s);
    return s.contains("sell") || s.contains("ask") || s.contains("卖")
        ? "sell"
        : s.contains("buy") || s.contains("bid") || s.contains("买") ? "buy" : null;
  }

  private static int level(String label, int fallback) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("[0-9]+").matcher(label);
    if (m.find())
      try {
        return Math.max(1, Math.min(100, Integer.parseInt(m.group())));
      } catch (Exception ignored) {
      }
    String[] names = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
    for (int k = 0; k < names.length; k++) if (label.contains(names[k])) return k + 1;
    return fallback;
  }

  private static void put(
      Map<String, Map<String, Object>> out, String side, int level, Double price, Double volume) {
    if (side == null || price == null && volume == null) return;
    String key = side + level;
    Map<String, Object> row =
        out.computeIfAbsent(
            key, k -> map("side", side, "level", level, "price", null, "volume", null));
    if (price != null && price > 0 && row.get("price") == null) row.put("price", price);
    if (volume != null && volume >= 0 && row.get("volume") == null) row.put("volume", volume);
  }

  private static void book(JsonNode n, Map<String, Map<String, Object>> out, int depth) {
    if (depth > 4 || out.size() > 200) return;
    if (n.isArray()) {
      int k = 0;
      for (JsonNode row : n) {
        String name = text(row, "ename", "eName", "name", "key", "type");
        String s = side(name);
        if (s != null) {
          String normalized = norm(name);
          boolean v = normalized.matches(".*(volume|amount|count|quantity|num|size|量).*?");
          Double x = first(row, "price", "originValue", "value");
          put(
              out,
              s,
              level(name, k + 1),
              v ? null : x,
              v ? x : first(row, "volume", "amount", "count", "quantity", "size"));
        } else book(row, out, depth + 1);
        k++;
      }
    } else if (n.isObject())
      n.fields()
          .forEachRemaining(
              e -> {
                String s = side(e.getKey());
                JsonNode value = e.getValue();
                if (s == null) {
                  if (Arrays.asList("pankouinfos", "orderBook", "orderbook", "depth", "quote")
                      .contains(e.getKey())) book(value, out, depth + 1);
                  return;
                }
                if (value.isArray()) {
                  int k = 1;
                  for (JsonNode row : value) {
                    Double
                        p =
                            row.isArray()
                                ? finite(row.path(0))
                                : first(
                                    row,
                                    "price",
                                    "buyPrice",
                                    "sellPrice",
                                    "bidPrice",
                                    "askPrice",
                                    "bidprice",
                                    "askprice",
                                    "value"),
                        v =
                            row.isArray()
                                ? finite(row.path(1))
                                : first(row, "volume", "amount", "count", "quantity", "size");
                    put(out, s, k++, p, v);
                  }
                } else {
                  String key = norm(e.getKey());
                  boolean v = key.matches(".*(volume|amount|count|quantity|num|size|量).*?");
                  Double x =
                      value.isObject()
                          ? first(value, "price", "originValue", "value", "askprice", "bidprice")
                          : finite(value);
                  put(
                      out,
                      s,
                      level(key, 1),
                      v ? null : x,
                      v ? x : first(value, "volume", "askvolume", "bidvolume"));
                }
              });
  }

  public static List<Map<String, Object>> orderBook(JsonNode n) {
    Map<String, Map<String, Object>> out = new TreeMap<>();
    book(n, out, 0);
    List<Map<String, Object>> rows = new ArrayList<>(out.values());
    rows.sort(
        Comparator.comparing(
            x ->
                String.valueOf(x.get("side"))
                    + String.format("%03d", ((Number) x.get("level")).intValue())));
    return rows;
  }

  public static List<Object> trades(JsonNode n) {
    List<Object> out = new ArrayList<>();
    for (String key :
        Arrays.asList(
            "trades",
            "trade",
            "tradeinfos",
            "tradeInfos",
            "ticks",
            "tickList",
            "transactions",
            "details",
            "detailinfos",
            "detailInfos",
            "deals",
            "tickTrades")) {
      JsonNode items = n.path(key);
      if (!items.isArray()) continue;
      for (JsonNode row : items) {
        if (out.size() >= 100) break;
        Double p = first(row, "price", "tradePrice", "dealPrice", "p"),
            v = first(row, "volume", "tradeVolume", "dealVolume", "v", "quantity", "count");
        if (p == null && v == null) continue;
        String
            time =
                text(
                    row,
                    "time",
                    "timestamp",
                    "datetime",
                    "date",
                    "timeText",
                    "timeStr",
                    "formatTime"),
            direction = text(row, "direction", "side", "type", "bs", "tradeType");
        String s = side(direction);
        if (s == null)
          s =
              Arrays.asList("b", "1").contains(direction)
                  ? "buy"
                  : Arrays.asList("s", "2").contains(direction) ? "sell" : "unknown";
        Long timestamp = null;
        try {
          timestamp = Long.parseLong(time);
          if (timestamp > 100000000000L) timestamp /= 1000;
        } catch (Exception ignored) {
        }
        out.add(
            map(
                "timestamp",
                timestamp,
                "timeText",
                time,
                "price",
                p != null && p > 0 ? p : null,
                "volume",
                v,
                "amount",
                first(row, "amount", "tradeAmount", "dealAmount", "a"),
                "direction",
                s));
      }
      break;
    }
    return out;
  }
}
