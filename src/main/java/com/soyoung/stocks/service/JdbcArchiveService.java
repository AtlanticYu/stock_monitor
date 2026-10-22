package com.soyoung.stocks.service;

import static com.soyoung.stocks.domain.Json.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!supabase")
public class JdbcArchiveService implements ArchiveService {
  private final JdbcTemplate jdbc;
  private final Path directory;
  private static final List<String> TABLES =
      Arrays.asList(
          "instruments",
          "app_settings",
          "series_meta",
          "bars",
          "quotes",
          "strategies",
          "backtests",
          "research_runs",
          "market_rules",
          "alert_profiles",
          "analysis_events");

  public JdbcArchiveService(
      JdbcTemplate jdbc, @Value("${stocks.archive-dir:./data/archives}") String directory) {
    this.jdbc = jdbc;
    this.directory = Paths.get(directory);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> export() {
    Map<String, Object> tables = new LinkedHashMap<>();
    for (String table : TABLES)
      tables.put(
          table,
          jdbc.query(
              "SELECT * FROM " + table,
              (r, n) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int k = 1; k <= r.getMetaData().getColumnCount(); k++) {
                  Object value = r.getObject(k);
                  if (value instanceof java.sql.Clob) value = r.getString(k);
                  row.put(
                      r.getMetaData().getColumnLabel(k).toLowerCase(Locale.ROOT),
                      value instanceof java.sql.Date ? value.toString() : value);
                }
                return row;
              }));
    return map(
        "format",
        "stocks-monitor-platform",
        "version",
        1,
        "exportedAtMs",
        System.currentTimeMillis(),
        "tables",
        tables);
  }

  public String backup() {
    try {
      Files.createDirectories(directory);
      String name =
          "stocks-"
              + System.currentTimeMillis()
              + "-"
              + UUID.randomUUID().toString().substring(0, 8)
              + ".json";
      Files.write(
          directory.resolve(name),
          write(export()).getBytes(java.nio.charset.StandardCharsets.UTF_8),
          StandardOpenOption.CREATE_NEW);
      return name;
    } catch (java.io.IOException e) {
      throw new IllegalStateException("备份写入失败，请检查备份目录权限", e);
    }
  }

  @Transactional
  public String restore(Map<String, Object> archive) {
    if (!"stocks-monitor-platform".equals(archive.get("format"))
        || number(archive, "version", 0) != 1) throw new IllegalArgumentException("备份格式或版本不受支持");
    Map<String, Object> tables = object(archive.get("tables"));
    if (!tables.keySet().equals(new HashSet<>(TABLES)))
      throw new IllegalArgumentException("备份表不完整");
    int count = 0;
    for (String table : TABLES) {
      Object value = tables.get(table);
      if (!(value instanceof List)) throw new IllegalArgumentException("无效备份表");
      List<?> rows = (List<?>) value;
      count += rows.size();
      if (count > 1000000) throw new IllegalArgumentException("备份超过行数限制");
      Set<String> columns =
          new HashSet<>(
              jdbc.query(
                  "SELECT * FROM " + table + " WHERE 1=0",
                  rs -> {
                    Set<String> c = new HashSet<>();
                    for (int k = 1; k <= rs.getMetaData().getColumnCount(); k++)
                      c.add(rs.getMetaData().getColumnLabel(k).toLowerCase(Locale.ROOT));
                    return c;
                  }));
      for (Object r : rows)
        if (!object(r).keySet().equals(columns))
          throw new IllegalArgumentException("备份字段与当前结构不一致：" + table);
    }
    String safeCopy = backup();
    List<String> reverse = new ArrayList<>(TABLES);
    Collections.reverse(reverse);
    for (String table : reverse) jdbc.update("DELETE FROM " + table);
    for (String table : TABLES)
      for (Object row : (List<?>) tables.get(table)) {
        Map<String, Object> values = object(row);
        String columns = String.join(",", values.keySet()),
            holders = values.keySet().stream().map(k -> "?").collect(Collectors.joining(","));
        jdbc.update(
            "INSERT INTO " + table + "(" + columns + ") VALUES(" + holders + ")",
            values.values().toArray());
      }
    return safeCopy;
  }
}
