package com.soyoung.stocks.service;

import java.util.Map;

public interface ArchiveService {
  Map<String, Object> export();

  String backup();

  String restore(Map<String, Object> archive);
}
