package com.soyoung.stocks.domain;

import com.fasterxml.jackson.databind.*;
import java.util.*;

public final class Json {
  public static final ObjectMapper MAPPER =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private Json() {}

  public static Map<String, Object> map(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
    return result;
  }

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("JSON 编码失败", e);
    }
  }

  public static JsonNode node(Object value) {
    return MAPPER.valueToTree(value);
  }

  public static JsonNode read(String value) {
    try {
      return MAPPER.readTree(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("JSON 数据无效", e);
    }
  }

  public static <T> T convert(Object value, Class<T> type) {
    return MAPPER.convertValue(value, type);
  }

  public static <T> T parse(String value, Class<T> type) {
    return convert(read(value), type);
  }

  public static Map<String, Object> object(Object value) {
    if (value == null) return new LinkedHashMap<>();
    return MAPPER.convertValue(
        value,
        MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
  }

  public static String text(JsonNode n, String key, String fallback) {
    return n.hasNonNull(key) ? n.get(key).asText() : fallback;
  }

  public static double num(JsonNode n, String key, double fallback) {
    return n.hasNonNull(key) ? n.get(key).asDouble(fallback) : fallback;
  }

  public static Double finite(JsonNode n) {
    if (n == null || n.isNull()) return null;
    try {
      double d = Double.parseDouble(n.asText().replace("%", ""));
      return Double.isFinite(d) ? d : null;
    } catch (Exception e) {
      return null;
    }
  }

  public static int integer(Map<String, Object> params, String key, int fallback) {
    Object v = params.get(key);
    if (v == null) return fallback;
    if (!(v instanceof Number) || ((Number) v).doubleValue() != ((Number) v).intValue())
      throw new IllegalArgumentException(key + " 必须为整数");
    int n = ((Number) v).intValue();
    if (n < 1 || n > 20000) throw new IllegalArgumentException(key + " 必须在 1 到 20000 之间");
    return n;
  }

  public static double number(Map<String, Object> params, String key, double fallback) {
    Object v = params.get(key);
    if (v == null) return fallback;
    if (!(v instanceof Number) || !Double.isFinite(((Number) v).doubleValue()))
      throw new IllegalArgumentException(key + " 必须为有限数值");
    return ((Number) v).doubleValue();
  }
}
