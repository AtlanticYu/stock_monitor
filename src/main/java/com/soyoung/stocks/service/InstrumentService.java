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
    if (input == null) throw new IllegalArgumentException("请输入股票地址或市场代码");
    String value = input.trim(), key = value;
    if (value.contains("://")) {
      URI uri = URI.create(value);
      if (!"https".equals(uri.getScheme())
          || !"finance.baidu.com".equals(uri.getHost())
          || uri.getPort() != -1
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null)
        throw new IllegalArgumentException("只接受 finance.baidu.com 的 HTTPS 股票页");
      key = uri.getPath().replaceFirst("^/stock/", "");
    }
    Matcher matcher = Pattern.compile("^(hk|ab|sh|sz|us)-([A-Za-z0-9.]+)$").matcher(key);
    if (!matcher.matches())
      throw new IllegalArgumentException("使用 hk-00700、sh-600519、sz-000001 或 us-AAPL");
    String prefix = matcher.group(1), code = matcher.group(2);
    if (("hk".equals(prefix) && !code.matches("[0-9]{5}"))
        || (("sh".equals(prefix) || "sz".equals(prefix) || "ab".equals(prefix))
            && !code.matches("[0-9]{6}"))
        || ("us".equals(prefix) && !code.matches("[A-Za-z][A-Za-z0-9.]{0,19}")))
      throw new IllegalArgumentException("股票代码格式无效");
    Instrument i = new Instrument();
    i.market =
        "sh".equals(prefix)
            ? "cn-sh"
            : "sz".equals(prefix) ? "cn-sz" : "ab".equals(prefix) ? "cn-ab" : prefix;
    i.code = "us".equals(prefix) ? code.toUpperCase(Locale.ROOT) : code;
    i.currency = Models.currency(i.market);
    i.exchange = Models.exchange(i.market);
    i.pageUrl = "https://finance.baidu.com/stock/" + prefix + "-" + i.code.toLowerCase(Locale.ROOT);
    i.name = i.code;
    return i;
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
