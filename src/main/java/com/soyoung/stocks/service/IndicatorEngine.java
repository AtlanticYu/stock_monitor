package com.soyoung.stocks.service;

import static com.soyoung.stocks.domain.Json.*;

import com.soyoung.stocks.domain.Models;
import com.soyoung.stocks.domain.Models.Bar;
import java.util.*;
import java.util.function.DoubleBinaryOperator;
import org.springframework.stereotype.Service;

/** Pure OHLCV calculations: aligned null warmup, no invented prices or volume. */
@Service
public class IndicatorEngine {
  public static class Result {
    public String name, dataQuality = "valid";
    public Map<String, Object> parameters = new LinkedHashMap<>(), extra;
    public Double[] values;

    public Result(
        String name, Double[] values, Map<String, Object> parameters, Map<String, Object> extra) {
      this.name = name;
      this.values = values;
      this.parameters = parameters;
      this.extra = extra;
      this.parameters.put("_calculationVersion", 4);
    }
  }

  public static Double finite(double value) {
    return Double.isFinite(value) ? value : null;
  }

  public static Double last(Double[] values) {
    return values.length == 0 ? null : values[values.length - 1];
  }

  public static Double[] closes(List<Bar> b) {
    Double[] v = new Double[b.size()];
    for (int i = 0; i < v.length; i++) if (b.get(i).valid()) v[i] = b.get(i).close;
    return v;
  }

  public static Double[] combine(Double[] a, Double[] b, DoubleBinaryOperator op) {
    Double[] r = new Double[a.length];
    for (int i = 0; i < r.length; i++)
      if (a[i] != null && b[i] != null) r[i] = finite(op.applyAsDouble(a[i], b[i]));
    return r;
  }

  public static boolean window(Double[] v, int from, int to) {
    if (from < 0) return false;
    for (int i = from; i <= to; i++) if (v[i] == null || !Double.isFinite(v[i])) return false;
    return true;
  }

  public static Double[] sma(Double[] v, int n) {
    Double[] out = new Double[v.length];
    for (int i = n - 1; i < v.length; i++) {
      if (!window(v, i - n + 1, i)) continue;
      double sum = 0;
      for (int j = i - n + 1; j <= i; j++) sum += v[j];
      out[i] = finite(sum / n);
    }
    return out;
  }

  public static Double[] smooth(Double[] v, int n, boolean wilder) {
    Double[] out = new Double[v.length];
    Double previous = null;
    double k = wilder ? 1.0 / n : 2.0 / (n + 1);
    for (int i = n - 1; i < v.length; i++) {
      if (previous == null) {
        if (!window(v, i - n + 1, i)) continue;
        double sum = 0;
        for (int j = i - n + 1; j <= i; j++) sum += v[j];
        previous = finite(sum / n);
      } else if (v[i] != null) previous = finite(previous * (1 - k) + v[i] * k);
      else {
        previous = null;
        continue;
      }
      out[i] = previous;
    }
    return out;
  }

  public static Double[] wma(Double[] v, int n) {
    Double[] out = new Double[v.length];
    double denominator = n * (n + 1.0) / 2;
    for (int i = n - 1; i < v.length; i++) {
      if (!window(v, i - n + 1, i)) continue;
      double sum = 0;
      for (int j = 0; j < n; j++) sum += v[i - n + 1 + j] * (j + 1);
      out[i] = finite(sum / denominator);
    }
    return out;
  }

  public static Double[] std(Double[] v, int n) {
    Double[] avg = sma(v, n), out = new Double[v.length];
    for (int i = n - 1; i < v.length; i++)
      if (avg[i] != null) {
        double sum = 0;
        for (int j = i - n + 1; j <= i; j++) sum += Math.pow(v[j] - avg[i], 2);
        out[i] = finite(Math.sqrt(sum / n));
      }
    return out;
  }

  public static Double[] rsi(Double[] v, int n) {
    Double[] gains = new Double[v.length], loss = new Double[v.length];
    for (int i = 1; i < v.length; i++)
      if (v[i] != null && v[i - 1] != null) {
        double change = v[i] - v[i - 1];
        gains[i] = Math.max(0, change);
        loss[i] = Math.max(0, -change);
      }
    Double[] g = smooth(gains, n, true), l = smooth(loss, n, true);
    return combine(g, l, (a, b) -> a == 0 && b == 0 ? 50 : b == 0 ? 100 : 100 - 100 / (1 + a / b));
  }

  public static Double[] atr(List<Bar> b, int n) {
    Double[] tr = new Double[b.size()];
    for (int i = 0; i < tr.length; i++) {
      Bar x = b.get(i);
      if (!x.valid()) continue;
      if (i == 0) tr[i] = x.high - x.low;
      else if (b.get(i - 1).valid())
        tr[i] =
            Math.max(
                x.high - x.low,
                Math.max(
                    Math.abs(x.high - b.get(i - 1).close), Math.abs(x.low - b.get(i - 1).close)));
    }
    return smooth(tr, n, true);
  }

  public static Double[][] macd(Double[] v, int f, int s, int signal) {
    Double[] dif = combine(smooth(v, f, false), smooth(v, s, false), (a, b) -> a - b),
        dea = smooth(dif, signal, false);
    return new Double[][] {dif, dea, combine(dif, dea, (a, b) -> a - b)};
  }

  private static boolean valid(List<Bar> b, int from, int to, boolean volume) {
    if (from < 0) return false;
    for (int i = from; i <= to; i++)
      if (!b.get(i).valid() || (volume && b.get(i).volume == null)) return false;
    return true;
  }

  private static double extreme(List<Bar> b, int from, int to, boolean high) {
    double value = high ? -Double.MAX_VALUE : Double.MAX_VALUE;
    for (int i = from; i <= to; i++)
      value = high ? Math.max(value, b.get(i).high) : Math.min(value, b.get(i).low);
    return value;
  }

  private static Double[] midpoints(List<Bar> b, int n) {
    Double[] out = new Double[b.size()];
    for (int i = n - 1; i < out.length; i++)
      if (valid(b, i - n + 1, i, false))
        out[i] = (extreme(b, i - n + 1, i, true) + extreme(b, i - n + 1, i, false)) / 2;
    return out;
  }

  private static Double[] shift(Double[] v, int n) {
    Double[] out = new Double[v.length];
    for (int i = 0; i + n < v.length; i++) out[i + n] = v[i];
    return out;
  }

  private static double tp(Bar b) {
    return (b.high + b.low + b.close) / 3;
  }

  private static double money(Bar b) {
    return b.high == b.low ? 0 : (2 * b.close - b.high - b.low) / (b.high - b.low) * b.volume;
  }

  private static int p(Map<String, Object> r, String key, int fallback) {
    return integer(r, key, fallback);
  }

  public List<Result> calculate(List<Bar> b, Map<String, Object> r, String market, String period) {
    int size = b.size();
    Double[] c = closes(b);
    List<Result> out = new ArrayList<>();
    for (String key : r.keySet())
      if (key.endsWith("Period")
          || Arrays.asList(
                  "macdFast",
                  "macdSlow",
                  "macdSignal",
                  "ppoFast",
                  "ppoSlow",
                  "ppoSignal",
                  "ichimokuConversion",
                  "ichimokuBase",
                  "ichimokuSpan")
              .contains(key)) if (r.get(key) != null) p(r, key, 1);
    if (p(r, "macdFast", 12) >= p(r, "macdSlow", 26) || p(r, "ppoFast", 12) >= p(r, "ppoSlow", 26))
      throw new IllegalArgumentException("快线周期必须小于慢线周期");
    if (p(r, "ichimokuConversion", 9) > p(r, "ichimokuBase", 26)
        || p(r, "ichimokuBase", 26) > p(r, "ichimokuSpan", 52))
      throw new IllegalArgumentException("一目均衡周期顺序无效");
    List<?> mas =
        r.get("maPeriods") == null ? Arrays.asList(5, 10, 20) : (List<?>) r.get("maPeriods");
    for (Object value : mas) {
      int n = integer(map("n", value), "n", 5);
      add(out, "MA" + n, sma(c, n), map("period", n), null);
    }
    int ep = p(r, "emaPeriod", 20), wp = p(r, "wmaPeriod", 20), dp = p(r, "hmaPeriod", 20);
    Double[] e1 = smooth(c, dp, false), e2 = smooth(e1, dp, false), e3 = smooth(e2, dp, false);
    add(out, "EMA", smooth(c, ep, false), map("period", ep), null);
    add(out, "WMA", wma(c, wp), map("period", wp), null);
    add(out, "DEMA", combine(e1, e2, (a, z) -> 2 * a - z), map("period", dp), null);
    add(
        out,
        "TEMA",
        combine(combine(e1, e2, (a, z) -> 3 * a - 3 * z), e3, (a, z) -> a + z),
        map("period", dp),
        null);
    add(
        out,
        "HMA",
        wma(
            combine(wma(c, Math.max(1, dp / 2)), wma(c, dp), (a, z) -> 2 * a - z),
            (int) Math.round(Math.sqrt(dp))),
        map("period", dp),
        null);
    int fast = p(r, "macdFast", 12), slow = p(r, "macdSlow", 26), signal = p(r, "macdSignal", 9);
    Double[][] m = macd(c, fast, slow, signal);
    add(
        out,
        "MACD",
        m[0],
        map("fast", fast, "slow", slow, "signal", signal),
        map("signal", m[1], "histogram", m[2]));
    int rp = p(r, "rsiPeriod", 14);
    add(out, "RSI", rsi(c, rp), map("period", rp), null);
    int kp = p(r, "kdjPeriod", 9);
    Double[] k = new Double[size], d = new Double[size], j = new Double[size];
    Double pk = null, pd = null;
    for (int i = 0; i < size; i++) {
      if (!b.get(i).valid()) {
        pk = null;
        pd = null;
        continue;
      }
      if (i < kp - 1 || !valid(b, i - kp + 1, i, false)) continue;
      double hi = extreme(b, i - kp + 1, i, true),
          lo = extreme(b, i - kp + 1, i, false),
          rsv = hi == lo ? 50 : (b.get(i).close - lo) / (hi - lo) * 100;
      pk = (pk == null ? 50 : pk) * 2 / 3 + rsv / 3;
      pd = (pd == null ? 50 : pd) * 2 / 3 + pk / 3;
      k[i] = pk;
      d[i] = pd;
      j[i] = 3 * pk - 2 * pd;
    }
    add(out, "KDJ", k, map("period", kp), map("d", d, "j", j));
    int sp = p(r, "stochasticPeriod", 14), ss = p(r, "stochasticSignal", 3);
    Double[] stochastic = new Double[size], will = new Double[size];
    int wn = p(r, "williamsPeriod", 14);
    for (int i = 0; i < size; i++) {
      if (i >= sp - 1 && valid(b, i - sp + 1, i, false)) {
        double hi = extreme(b, i - sp + 1, i, true), lo = extreme(b, i - sp + 1, i, false);
        stochastic[i] = hi == lo ? 50 : (b.get(i).close - lo) / (hi - lo) * 100;
      }
      if (i >= wn - 1 && valid(b, i - wn + 1, i, false)) {
        double hi = extreme(b, i - wn + 1, i, true), lo = extreme(b, i - wn + 1, i, false);
        will[i] = hi == lo ? -50 : (hi - b.get(i).close) / (hi - lo) * -100;
      }
    }
    add(out, "STOCH", stochastic, map("period", sp, "signal", ss), map("d", sma(stochastic, ss)));
    add(out, "WILLR", will, map("period", wn), null);
    int cp = p(r, "cciPeriod", 20);
    Double[] cci = new Double[size];
    for (int i = cp - 1; i < size; i++)
      if (valid(b, i - cp + 1, i, false)) {
        double avg = 0, dev = 0;
        for (int z = i - cp + 1; z <= i; z++) avg += tp(b.get(z)) / cp;
        for (int z = i - cp + 1; z <= i; z++) dev += Math.abs(tp(b.get(z)) - avg) / cp;
        cci[i] = dev == 0 ? 0 : (tp(b.get(i)) - avg) / (0.015 * dev);
      }
    add(out, "CCI", cci, map("period", cp), null);
    for (String name : Arrays.asList("ROC", "MOM")) {
      int n =
          p(r, name.equals("ROC") ? "rocPeriod" : "momentumPeriod", name.equals("ROC") ? 12 : 10);
      Double[] v = new Double[size];
      for (int i = n; i < size; i++)
        if (window(c, i - n, i))
          v[i] = name.equals("ROC") ? (c[i] / c[i - n] - 1) * 100 : c[i] - c[i - n];
      add(out, name, v, map("period", n), null);
    }
    int tn = p(r, "trixPeriod", 15);
    Double[] triple = smooth(smooth(smooth(c, tn, false), tn, false), tn, false),
        trix = new Double[size];
    for (int i = 1; i < size; i++)
      if (triple[i] != null && triple[i - 1] != null && triple[i - 1] != 0)
        trix[i] = (triple[i] / triple[i - 1] - 1) * 100;
    add(out, "TRIX", trix, map("period", tn), null);
    int pf = p(r, "ppoFast", 12), ps = p(r, "ppoSlow", 26), pg = p(r, "ppoSignal", 9);
    Double[] ppo = combine(smooth(c, pf, false), smooth(c, ps, false), (a, z) -> (a / z - 1) * 100),
        ppoSignal = smooth(ppo, pg, false);
    add(
        out,
        "PPO",
        ppo,
        map("fast", pf, "slow", ps, "signal", pg),
        map("signal", ppoSignal, "histogram", combine(ppo, ppoSignal, (a, z) -> a - z)));
    int an = p(r, "adxPeriod", 14);
    Double[] tr = new Double[size], plus = new Double[size], minus = new Double[size];
    for (int i = 1; i < size; i++)
      if (valid(b, i - 1, i, false)) {
        Bar x = b.get(i), prev = b.get(i - 1);
        double up = x.high - prev.high, down = prev.low - x.low;
        plus[i] = up > down && up > 0 ? up : 0;
        minus[i] = down > up && down > 0 ? down : 0;
        tr[i] =
            Math.max(
                x.high - x.low,
                Math.max(Math.abs(x.high - prev.close), Math.abs(x.low - prev.close)));
      }
    Double[] atr = smooth(tr, an, true),
        diPlus = combine(smooth(plus, an, true), atr, (a, z) -> z == 0 ? 0 : a / z * 100),
        diMinus = combine(smooth(minus, an, true), atr, (a, z) -> z == 0 ? 0 : a / z * 100),
        dx = combine(diPlus, diMinus, (a, z) -> a + z == 0 ? 0 : Math.abs(a - z) / (a + z) * 100);
    add(
        out,
        "ADX",
        smooth(dx, an, true),
        map("period", an),
        map("diPlus", diPlus, "diMinus", diMinus));
    int aro = p(r, "aroonPeriod", 25);
    Double[] au = new Double[size], adown = new Double[size];
    for (int i = aro; i < size; i++)
      if (valid(b, i - aro, i, false)) {
        int hi = i - aro, lo = hi;
        for (int z = i - aro + 1; z <= i; z++) {
          if (b.get(z).high >= b.get(hi).high) hi = z;
          if (b.get(z).low <= b.get(lo).low) lo = z;
        }
        au[i] = (hi - (i - aro)) * 100.0 / aro;
        adown[i] = (lo - (i - aro)) * 100.0 / aro;
      }
    add(out, "AROON", au, map("period", aro), map("down", adown));
    int ap = p(r, "atrPeriod", 14);
    Double[] av = atr(b, ap);
    add(out, "ATR", av, map("period", ap), null);
    add(out, "ATR_PCT", combine(av, c, (a, z) -> a / z * 100), map("period", ap), null);
    int bp = p(r, "bollingerPeriod", 20);
    double multiplier = number(r, "bollingerStd", 2);
    if (multiplier <= 0) throw new IllegalArgumentException("布林带倍数必须大于 0");
    Double[] middle = sma(c, bp), sd = std(c, bp);
    add(
        out,
        "BOLL",
        middle,
        map("period", bp, "std", multiplier),
        map(
            "upper",
            combine(middle, sd, (a, z) -> a + multiplier * z),
            "lower",
            combine(middle, sd, (a, z) -> a - multiplier * z)));
    add(out, "STDDEV", sd, map("period", bp), null);
    int kn = p(r, "keltnerPeriod", 20);
    double km = number(r, "keltnerMultiplier", 2);
    if (km <= 0) throw new IllegalArgumentException("通道倍数必须大于0");
    Double[] kmid = smooth(c, kn, false), ka = atr(b, kn);
    add(
        out,
        "KELTNER",
        kmid,
        map("period", kn),
        map(
            "upper",
            combine(kmid, ka, (a, z) -> a + km * z),
            "lower",
            combine(kmid, ka, (a, z) -> a - km * z)));
    int dn = p(r, "donchianPeriod", 20);
    Double[] dh = new Double[size], dl = new Double[size];
    for (int i = dn - 1; i < size; i++)
      if (valid(b, i - dn + 1, i, false)) {
        dh[i] = extreme(b, i - dn + 1, i, true);
        dl[i] = extreme(b, i - dn + 1, i, false);
      }
    add(
        out,
        "DONCHIAN",
        combine(dh, dl, (a, z) -> (a + z) / 2),
        map("period", dn),
        map("upper", dh, "lower", dl));
    int hn = p(r, "hvPeriod", 20);
    double annual =
        "week".equals(period) ? 52 : "month".equals(period) ? 12 : "year".equals(period) ? 1 : 252;
    Double[] logReturn = new Double[size];
    for (int i = 1; i < size; i++)
      if (c[i] != null && c[i - 1] != null) logReturn[i] = Math.log(c[i] / c[i - 1]);
    Double[] hv = std(logReturn, hn);
    for (int i = 0; i < size; i++)
      hv[i] =
          "intraday".equals(period) ? null : hv[i] == null ? null : hv[i] * Math.sqrt(annual) * 100;
    add(
        out,
        "HV",
        hv,
        map(
            "period",
            hn,
            "annualization",
            "intraday".equals(period) ? null : annual,
            "barPeriod",
            period),
        null);
    double step = number(r, "sarStep", 0.02), max = number(r, "sarMax", 0.2);
    if (step <= 0 || max < step || max > 1) throw new IllegalArgumentException("SAR 参数无效");
    add(out, "SAR", sar(b, step, max), map("step", step, "max", max), null);
    Double[] volumes = new Double[size],
        obv = new Double[size],
        ad = new Double[size],
        vwap = new Double[size];
    double obvValue = 0, adValue = 0, pv = 0, vv = 0;
    boolean broken = false, dayBroken = false;
    java.time.LocalDate day = null;
    for (int i = 0; i < size; i++) {
      Bar x = b.get(i);
      if (x.valid() && x.volume != null) volumes[i] = x.volume;
      java.time.LocalDate key = Models.date(x.time, market);
      if (!key.equals(day)) {
        day = key;
        pv = 0;
        vv = 0;
        dayBroken = false;
      }
      if (volumes[i] == null) {
        broken = true;
        dayBroken = true;
        continue;
      }
      if (!broken) {
        if (i > 0 && b.get(i - 1).valid())
          obvValue += Double.compare(x.close, b.get(i - 1).close) * x.volume;
        obv[i] = obvValue;
        adValue += money(x);
        ad[i] = adValue;
      }
      if (!dayBroken && "intraday".equals(period)) {
        pv += tp(x) * x.volume;
        vv += x.volume;
        if (vv > 0) vwap[i] = pv / vv;
      }
    }
    int vn = p(r, "volumeMaPeriod", 5);
    add(out, "VOL_MA", sma(volumes, vn), map("period", vn), null);
    add(out, "OBV", obv, map("origin", 0), null);
    add(out, "AD", ad, map(), null);
    add(out, "VWAP", vwap, map("reset", "market-trading-date"), null);
    int mn = p(r, "mfiPeriod", 14), cn = p(r, "cmfPeriod", 20);
    Double[] mfi = new Double[size], cmf = new Double[size];
    for (int i = 0; i < size; i++) {
      if (i >= mn && valid(b, i - mn, i, true)) {
        double pos = 0, neg = 0;
        for (int z = i - mn + 1; z <= i; z++) {
          double t = tp(b.get(z)), prev = tp(b.get(z - 1)), flow = t * b.get(z).volume;
          if (t > prev) pos += flow;
          else if (t < prev) neg += flow;
        }
        mfi[i] = pos == 0 && neg == 0 ? 50 : neg == 0 ? 100 : 100 - 100 / (1 + pos / neg);
      }
      if (i >= cn - 1 && valid(b, i - cn + 1, i, true)) {
        double sum = 0, vol = 0;
        for (int z = i - cn + 1; z <= i; z++) {
          sum += money(b.get(z));
          vol += b.get(z).volume;
        }
        if (vol > 0) cmf[i] = sum / vol;
      }
    }
    add(out, "MFI", mfi, map("period", mn), null);
    add(out, "CMF", cmf, map("period", cn), null);
    add(out, "VOLUME_DIVERGENCE", divergence(b, 5), map("lookback", 5), null);
    int ic = p(r, "ichimokuConversion", 9),
        ib = p(r, "ichimokuBase", 26),
        isp = p(r, "ichimokuSpan", 52);
    Double[] conv = midpoints(b, ic),
        base = midpoints(b, ib),
        rawB = midpoints(b, isp),
        rawA = combine(conv, base, (a, z) -> (a + z) / 2);
    add(
        out,
        "ICHIMOKU",
        conv,
        map("conversion", ic, "base", ib, "span", isp),
        map(
            "base",
            base,
            "spanA",
            shift(rawA, ib),
            "spanB",
            shift(rawB, ib),
            "rawSpanA",
            rawA,
            "rawSpanB",
            rawB));
    Double[] patterns = new Double[size], support = new Double[size], resistance = new Double[size];
    String[] labels = new String[size];
    for (int i = 0; i < size; i++) {
      if (i >= 20 && valid(b, i - 20, i - 1, false)) {
        support[i] = extreme(b, i - 20, i - 1, false);
        resistance[i] = extreme(b, i - 20, i - 1, true);
      }
      String label = pattern(b, i);
      labels[i] = label;
      if (Arrays.asList("锤头线", "看涨吞没", "早晨之星", "三白兵").contains(label)) patterns[i] = 1.0;
      else if (Arrays.asList("看跌吞没", "黄昏之星", "三只乌鸦").contains(label)) patterns[i] = -1.0;
      else if (i >= 5 && valid(b, i - 5, i, false) && b.get(i).high > b.get(i).low)
        patterns[i] = 0.0;
    }
    add(
        out,
        "PATTERN",
        patterns,
        map("lookback", 20),
        map("labels", labels, "support", support, "resistance", resistance));
    boolean invalid = b.stream().anyMatch(x -> !x.valid()),
        missing = b.stream().anyMatch(x -> x.volume == null);
    for (Result value : out) {
      boolean volume =
          Arrays.asList("VOL_MA", "OBV", "AD", "VWAP", "MFI", "CMF", "VOLUME_DIVERGENCE")
              .contains(value.name);
      value.dataQuality =
          invalid
              ? "invalid-ohlc"
              : volume && missing
                  ? "missing-volume"
                  : Arrays.stream(value.values).allMatch(Objects::isNull)
                      ? "insufficient-samples"
                      : "valid";
    }
    return out;
  }

  private static void add(
      List<Result> out,
      String name,
      Double[] values,
      Map<String, Object> params,
      Map<String, Object> extra) {
    out.add(new Result(name, values, params, extra));
  }

  public static Result find(List<Result> results, String name) {
    return results.stream().filter(r -> r.name.equals(name)).findFirst().orElse(null);
  }

  private static Double[] divergence(List<Bar> b, int n) {
    Double[] out = new Double[b.size()];
    for (int i = 2 * n - 1; i < b.size(); i++)
      if (valid(b, i - 2 * n + 1, i, true)) {
        int start = i - 2 * n + 1;
        double[] local = new double[2 * n];
        for (int z = 1; z < 2 * n; z++)
          local[z] =
              local[z - 1]
                  + Double.compare(b.get(start + z).close, b.get(start + z - 1).close)
                      * b.get(start + z).volume;
        int h1 = 0, l1 = 0, h2 = n, l2 = n;
        for (int z = 0; z < 2 * n; z++) {
          if (z < n) {
            if (b.get(start + z).high >= b.get(start + h1).high) h1 = z;
            if (b.get(start + z).low <= b.get(start + l1).low) l1 = z;
          } else {
            if (b.get(start + z).high >= b.get(start + h2).high) h2 = z;
            if (b.get(start + z).low <= b.get(start + l2).low) l2 = z;
          }
        }
        out[i] =
            b.get(start + h2).high > b.get(start + h1).high && local[h2] < local[h1]
                ? -1.0
                : b.get(start + l2).low < b.get(start + l1).low && local[l2] > local[l1]
                    ? 1.0
                    : 0.0;
      }
    return out;
  }

  private static Double[] sar(List<Bar> b, double step, double max) {
    Double[] out = new Double[b.size()];
    boolean initialized = false, rising = true;
    double af = step, ep = 0, value = 0;
    int start = 0;
    for (int i = 0; i < b.size(); i++) {
      Bar x = b.get(i);
      if (!x.valid()) {
        initialized = false;
        continue;
      }
      if (!initialized) {
        if (i == 0 || !b.get(i - 1).valid()) continue;
        Bar prev = b.get(i - 1);
        rising = x.close >= prev.close;
        ep = rising ? Math.max(prev.high, x.high) : Math.min(prev.low, x.low);
        value = rising ? Math.min(prev.low, x.low) : Math.max(prev.high, x.high);
        af = step;
        start = i - 1;
        out[i] = value;
        initialized = true;
        continue;
      }
      Bar prev = b.get(i - 1);
      value += af * (ep - value);
      if (rising) {
        value = Math.min(value, prev.low);
        if (i >= start + 2) value = Math.min(value, b.get(i - 2).low);
        if (x.low <= value) {
          rising = false;
          value = Math.max(ep, Math.max(prev.high, x.high));
          ep = x.low;
          af = step;
        } else if (x.high > ep) {
          ep = x.high;
          af = Math.min(max, af + step);
        }
      } else {
        value = Math.max(value, prev.high);
        if (i >= start + 2) value = Math.max(value, b.get(i - 2).high);
        if (x.high >= value) {
          rising = true;
          value = Math.min(ep, Math.min(prev.low, x.low));
          ep = x.high;
          af = step;
        } else if (x.low < ep) {
          ep = x.low;
          af = Math.min(max, af + step);
        }
      }
      out[i] = finite(value);
    }
    return out;
  }

  private static boolean trend(List<Bar> b, int start, boolean up) {
    if (start < 3 || !valid(b, start - 3, start - 1, false)) return false;
    return up
        ? b.get(start - 2).close > b.get(start - 3).close
            && b.get(start - 1).close > b.get(start - 2).close
        : b.get(start - 2).close < b.get(start - 3).close
            && b.get(start - 1).close < b.get(start - 2).close;
  }

  private static double body(Bar b) {
    return Math.abs(b.close - b.open);
  }

  private static String pattern(List<Bar> b, int i) {
    Bar x = b.get(i);
    if (!x.valid() || x.high <= x.low) return "未确认";
    double size = body(x);
    if (size / (x.high - x.low) < 0.1) return "十字星";
    if (trend(b, i, false)
        && Math.min(x.open, x.close) - x.low >= 2 * size
        && x.high - Math.max(x.open, x.close) <= size) return "锤头线";
    if (i > 0 && b.get(i - 1).valid()) {
      Bar p = b.get(i - 1);
      if (trend(b, i - 1, false)
          && p.close < p.open
          && x.close > x.open
          && x.open <= p.close
          && x.close >= p.open
          && size > body(p)) return "看涨吞没";
      if (trend(b, i - 1, true)
          && p.close > p.open
          && x.close < x.open
          && x.open >= p.close
          && x.close <= p.open
          && size > body(p)) return "看跌吞没";
    }
    if (i >= 2 && valid(b, i - 2, i, false)) {
      Bar a = b.get(i - 2), c = b.get(i - 1);
      boolean first = body(a) > (a.high - a.low) * 0.5, middle = body(c) < body(a) * 0.35;
      if (trend(b, i - 2, false)
          && first
          && middle
          && a.close < a.open
          && Math.max(c.open, c.close) < a.close
          && x.close > x.open
          && x.close > (a.open + a.close) / 2) return "早晨之星";
      if (trend(b, i - 2, true)
          && first
          && middle
          && a.close > a.open
          && Math.min(c.open, c.close) > a.close
          && x.close < x.open
          && x.close < (a.open + a.close) / 2) return "黄昏之星";
      boolean inside =
          c.open > Math.min(a.open, a.close)
              && c.open < Math.max(a.open, a.close)
              && x.open > Math.min(c.open, c.close)
              && x.open < Math.max(c.open, c.close);
      if (inside
          && body(a) > (a.high - a.low) * 0.5
          && body(c) > (c.high - c.low) * 0.5
          && size > (x.high - x.low) * 0.5) {
        if (trend(b, i - 2, false)
            && a.close > a.open
            && c.close > c.open
            && x.close > x.open
            && a.high - a.close < body(a) * 0.25
            && c.high - c.close < body(c) * 0.25
            && x.high - x.close < size * 0.25
            && x.close > c.close
            && c.close > a.close) return "三白兵";
        if (trend(b, i - 2, true)
            && a.close < a.open
            && c.close < c.open
            && x.close < x.open
            && a.close - a.low < body(a) * 0.25
            && c.close - c.low < body(c) * 0.25
            && x.close - x.low < size * 0.25
            && x.close < c.close
            && c.close < a.close) return "三只乌鸦";
      }
    }
    return "未确认";
  }
}
