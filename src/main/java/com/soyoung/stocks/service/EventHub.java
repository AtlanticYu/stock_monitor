package com.soyoung.stocks.service;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class EventHub {
  private final CopyOnWriteArrayList<SseEmitter> clients = new CopyOnWriteArrayList<>();
  public final AtomicLong generation = new AtomicLong(1);

  public SseEmitter subscribe() {
    SseEmitter e = new SseEmitter(0L);
    clients.add(e);
    e.onCompletion(() -> clients.remove(e));
    e.onTimeout(() -> clients.remove(e));
    e.onError(x -> clients.remove(e));
    try {
      e.send(SseEmitter.event().name("connected").data("{}"));
    } catch (Exception x) {
      clients.remove(e);
    }
    return e;
  }

  public void emit(String name, Object value) {
    for (SseEmitter e : clients)
      try {
        e.send(SseEmitter.event().name(name).data(value));
      } catch (Exception x) {
        clients.remove(e);
        e.complete();
      }
  }

  @Scheduled(fixedDelay = 20000)
  public void heartbeat() {
    for (SseEmitter e : clients)
      try {
        e.send(SseEmitter.event().comment("heartbeat"));
      } catch (Exception x) {
        clients.remove(e);
        e.complete();
      }
  }
}
