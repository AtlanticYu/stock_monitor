package com.soyoung.stocks.service;

import static com.soyoung.stocks.domain.Json.*;

import com.soyoung.stocks.storage.SupabaseClient;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("supabase")
public class SupabaseArchiveService implements ArchiveService {
  private final SupabaseClient client;

  public SupabaseArchiveService(SupabaseClient client) {
    this.client = client;
  }

  public Map<String, Object> export() {
    Map<String, Object> tables = new LinkedHashMap<>();
    for (String table : SupabaseClient.TABLES)
      if (!"backups".equals(table)) {
        String order =
            "settings".equals(table)
                ? "setting_key.asc"
                : "series".equals(table)
                    ? "instrument_id.asc,period.asc"
                    : Arrays.asList("quotes", "alert_profiles").contains(table)
                        ? "instrument_id.asc"
                        : "id.asc";
        tables.put("stocks_" + table, client.select(table, map("order", order)));
      }
    return map(
        "format",
        "stocks-monitor-supabase",
        "version",
        1,
        "exportedAtMs",
        System.currentTimeMillis(),
        "tables",
        tables);
  }

  public String backup() {
    String name =
        "stocks-supabase-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".json";
    client.insert(
        "backups",
        map("name", name, "created_at", System.currentTimeMillis() / 1000, "payload", export()));
    return name;
  }

  public String restore(Map<String, Object> archive) {
    throw new IllegalArgumentException(
        "Supabase REST 模式不支持跨表原子恢复；为避免部分覆盖，已拒绝整库恢复。请使用单标的行情 JSON 导入。");
  }
}
