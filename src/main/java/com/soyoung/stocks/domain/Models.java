package com.soyoung.stocks.domain;

import java.time.*;
import java.util.*;

public final class Models {
  private Models() {}

  public static class Bar {
    public long time;
    public double open, high, low, close;
    public Double volume;

    public Bar() {}

    public Bar(long time, double open, double high, double low, double close, Double volume) {
      this.time = time;
      this.open = open;
      this.high = high;
      this.low = low;
      this.close = close;
      this.volume = volume;
    }

    public boolean valid() {
      return time >= 946656000L
          && time < 4102444800L
          && positive(open)
          && positive(high)
          && positive(low)
          && positive(close)
          && high >= Math.max(open, close)
          && low <= Math.min(open, close)
          && high >= low
          && (volume == null || Double.isFinite(volume) && volume >= 0);
    }
  }

  public static class Instrument {
    public long id, createdAt, updatedAt;
    public String market, exchange, code, name, pageUrl, currency, nameSource = "auto";
    public Long nameUpdatedAt;
    public boolean enabled = true;
    public int sortOrder;
  }

  public static class Series {
    public String period = "day", source = "", adjustment = "unknown", note = "";
    public List<Bar> bars = new ArrayList<>();
    public long fetchedAtMs;
    public Double previousClose;
  }

  public static class BacktestRequest {
    public long instrumentId;
    public String strategy = "ma", startDate, endDate;
    public Map<String, Object> parameters = new LinkedHashMap<>();
    public double initialCapital = 100000, maxPosition = 1;
    public Double commissionRate, stampDutyRate, slippageRate;
    public Boolean allowFractionalShares, enforcePriceLimit;
  }

  public static ZoneId zone(String market) {
    return ZoneId.of("us".equals(market) ? "America/New_York" : "Asia/Hong_Kong");
  }

  public static LocalDate date(long time, String market) {
    return Instant.ofEpochSecond(time).atZone(zone(market)).toLocalDate();
  }

  public static boolean positive(double n) {
    return Double.isFinite(n) && n > 0;
  }

  public static String currency(String market) {
    return "us".equals(market) ? "USD" : "hk".equals(market) ? "HKD" : "CNY";
  }

  public static String exchange(String market) {
    return "us".equals(market)
        ? "US"
        : "hk".equals(market)
            ? "HKEX"
            : "cn-sh".equals(market) ? "SSE" : "cn-sz".equals(market) ? "SZSE" : "SSE/SZSE";
  }

  public static String period(String p) {
    if (!Arrays.asList("intraday", "day", "week", "month", "year").contains(p))
      throw new IllegalArgumentException("不支持的行情周期");
    return p;
  }
}
