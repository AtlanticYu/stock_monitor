package com.soyoung.stocks.service;

import com.soyoung.stocks.storage.UserScope;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class EventHub {
  private final Map<SseEmitter, String> clients = new ConcurrentHashMap<>();
  private final Map<SseEmitter, Long> expirations = new ConcurrentHashMap<>();
  public final AtomicLong generation = new AtomicLong(1);

  public SseEmitter subscribe() {
    SseEmitter e = new SseEmitter(0L);
    clients.put(e, UserScope.owner());
    UserScope.Identity identity = UserScope.current();
    if (identity != null) expirations.put(e, identity.expiresAt);
    e.onCompletion(() -> remove(e));
    e.onTimeout(() -> remove(e));
    e.onError(x -> remove(e));
    try {
      e.send(SseEmitter.event().name("connected").data("{}"));
    } catch (Exception x) {
      remove(e);
    }
    return e;
  }

  private void remove(SseEmitter emitter) {
    clients.remove(emitter);
    expirations.remove(emitter);
  }

  private boolean expired(SseEmitter emitter) {
    Long expiresAt = expirations.get(emitter);
    if (expiresAt == null || expiresAt > System.currentTimeMillis() / 1000) return false;
    remove(emitter);
    emitter.complete();
    return true;
  }

  public void emit(String name, Object value) {
    for (SseEmitter e : clients.keySet())
      try {
        if (expired(e) || !UserScope.owner().equals(clients.get(e))) continue;
        e.send(SseEmitter.event().name(name).data(value));
      } catch (Exception x) {
        remove(e);
        e.complete();
      }
  }

  @Scheduled(fixedDelay = 20000)
  public void heartbeat() {
    for (SseEmitter e : clients.keySet())
      try {
        if (!expired(e)) e.send(SseEmitter.event().comment("heartbeat"));
      } catch (Exception x) {
        remove(e);
        e.complete();
      }
  }
}
