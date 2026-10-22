package com.soyoung.stocks.service;

import static com.soyoung.stocks.domain.Json.*;

import com.soyoung.stocks.domain.Models;
import com.soyoung.stocks.domain.Models.*;
import com.soyoung.stocks.repository.StockRepository;
import java.net.URI;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Service;

@Service
public class InstrumentService {
  private final StockRepository repository;

  public InstrumentService(StockRepository repository) {
    this.repository = repository;
  }

  public static Instrument identity(String input) {
    if (input == null) throw new IllegalArgumentException("请输入股票或指数地址、市场代码");
    String value = input.trim(), key = value;
    if (value.contains("://")) {
      URI uri = URI.create(value);
      if (!"https".equals(uri.getScheme())
          || !"finance.baidu.com".equals(uri.getHost())
          || uri.getPort() != -1
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null)
        throw new IllegalArgumentException("只接受 finance.baidu.com 的 HTTPS 股票或指数页");
      key = uri.getPath();
    }
    String type = "stock";
    if (key.startsWith("/")) key = key.substring(1);
    if (key.startsWith("index/")) {
      type = "index";
      key = key.substring("index/".length());
    } else if (key.startsWith("stock/")) key = key.substring("stock/".length());
    // Full URLs must point to an explicit instrument page.
    else if (value.contains("://"))
      throw new IllegalArgumentException("地址路径须为 /stock/市场代码 或 /index/市场代码");
    Matcher matcher =
        Pattern.compile("^(hk|ab|sh|sz|us)-([A-Za-z0-9.]+)$", Pattern.CASE_INSENSITIVE)
            .matcher(key);
    if (!matcher.matches())
      throw new IllegalArgumentException(
          "使用 hk-00700、sh-600519、sz-000001、us-AAPL 或指数地址 https://finance.baidu.com/index/us-IXIC");
    String prefix = matcher.group(1).toLowerCase(Locale.ROOT), code = matcher.group(2);
    if (("hk".equals(prefix)
            && !("index".equals(type)
                ? code.matches("[A-Za-z0-9][A-Za-z0-9.]{0,19}")
                : code.matches("[0-9]{5}")))
        || (("sh".equals(prefix) || "sz".equals(prefix) || "ab".equals(prefix))
            && !code.matches("[0-9]{6}"))
        || ("us".equals(prefix) && !code.matches("[A-Za-z][A-Za-z0-9.]{0,19}")))
      throw new IllegalArgumentException("股票或指数代码格式无效");
    Instrument i = new Instrument();
    i.market =
        "sh".equals(prefix)
            ? "cn-sh"
            : "sz".equals(prefix) ? "cn-sz" : "ab".equals(prefix) ? "cn-ab" : prefix;
    i.code = code.toUpperCase(Locale.ROOT);
    i.currency = Models.currency(i.market);
    i.exchange = Models.exchange(i.market);
    i.pageUrl =
        "https://finance.baidu.com/"
            + type
            + "/"
            + prefix
            + "-"
            + ("index".equals(type) ? i.code : i.code.toLowerCase(Locale.ROOT));
    i.name = i.code;
    if ("index".equals(type) && "us".equals(prefix)) {
      if ("IXIC".equals(i.code)) i.name = "纳斯达克综合指数";
      else if ("DJI".equals(i.code)) i.name = "道琼斯工业平均指数";
      else if ("INX".equals(i.code)) i.name = "标普500指数";
    }
    return i;
  }

  public static String financeType(Instrument instrument) {
    return instrument.pageUrl != null
            && URI.create(instrument.pageUrl).getPath().startsWith("/index/")
        ? "index"
        : "stock";
  }

  public static Map<String, Object> defaultRules(String market) {
    boolean cn = market.startsWith("cn-");
    return map(
        "market",
        market,
        "currency",
        Models.currency(market),
        "lotSize",
        cn ? 100 : 1,
        "allowFractionalShares",
        false,
        "commissionRate",
        0.0003,
        "stampDutyRate",
        cn ? 0.0005 : 0.0,
        "slippageRate",
        0.0005,
        "priceLimitRule",
        cn ? map("enabled", true, "percent", 0.10) : null,
        "sessions",
        Collections.emptyList());
  }

  public Map<String, Object> rules(String market) {
    Map<String, Object> r = defaultRules(market);
    String stored = repository.setting("rules:" + market, null);
    if (stored != null) r.putAll(object(read(stored)));
    return r;
  }

  public void saveRules(Map<String, Object> rules) {
    String market = String.valueOf(rules.get("market"));
    if (!Arrays.asList("hk", "us", "cn-ab", "cn-sh", "cn-sz").contains(market))
      throw new IllegalArgumentException("无效市场");
    for (String key : Arrays.asList("commissionRate", "stampDutyRate", "slippageRate")) {
      double n = number(rules, key, 0);
      if (n < 0 || n >= 1) throw new IllegalArgumentException("费率须在 [0,1) 内");
    }
    integer(rules, "lotSize", 1);
    repository.settingPut("rules:" + market, write(rules));
  }
}
