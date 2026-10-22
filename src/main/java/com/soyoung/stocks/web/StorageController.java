package com.soyoung.stocks.web;

import static com.soyoung.stocks.domain.Json.*;

import com.soyoung.stocks.storage.SupabaseClient;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

@RestController
public class StorageController {
  private final ObjectProvider<SupabaseClient> cloud;

  public StorageController(ObjectProvider<SupabaseClient> cloud) {
    this.cloud = cloud;
  }

  @GetMapping("/api/storage")
  public Map<String, Object> configuration() {
    SupabaseClient client = cloud.getIfAvailable();
    return client == null ? map("mode", "mysql") : client.configuration();
  }
}
