package com.soyoung.stocks.repository;

import com.soyoung.stocks.domain.Models.*;
import java.util.*;

/** Persistence contract shared by MySQL and user-scoped Supabase storage. */
public interface StockRepository {
  List<Instrument> instruments();

  Instrument instrument(long id);

  Instrument active();

  Instrument activeOrNull();

  Instrument setActive(long id);

  long insertInstrument(Instrument i);

  void updateInstrument(Instrument i);

  void reorder(List<?> ids);

  void deleteInstrument(long id);

  void deleteData(long id);

  String setting(String key, String fallback);

  void settingPut(String key, String value);

  void saveSeries(Instrument i, Series s);

  Series series(long id, String period, boolean latestDay);

  List<Map<String, Object>> intraday(long id, String start, String end);

  Object quote(long id);

  void saveQuote(long id, Object quote);

  long saveBacktest(BacktestRequest req, Object result);

  Map<String, Object> backtest(long id);

  Map<String, Object> researchRun(long id);

  List<Map<String, Object>> backtests(Long id);

  long saveResearch(Object request, Object result);

  List<Map<String, Object>> researchRuns();

  List<Map<String, Object>> strategies();

  long saveStrategy(String name, String kind, Object parameters);

  void deleteStrategy(long id);

  Object profile(long id);

  void saveProfile(long id, Object profile);

  List<Map<String, Object>> events(Long id);

  long saveEvent(long id, Object event);

  void ack(long id);

  void clearEvents(Long id);

  int cleanup(int days);

  String integrity();

  default String storageDescription() {
    return "MySQL · 服务端持久化";
  }

  default void scheduledScopes(Runnable action) {
    action.run();
  }
}
