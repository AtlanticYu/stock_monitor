package com.soyoung.stocks.storage;

import static org.mockito.Mockito.*;

import com.soyoung.stocks.service.EventHub;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class UserEventIsolationTest {
  @Test
  @SuppressWarnings("unchecked")
  void eventsOnlyReachTheCurrentOwnersStream() throws Exception {
    EventHub hub = new EventHub();
    java.lang.reflect.Field field = EventHub.class.getDeclaredField("clients");
    field.setAccessible(true);
    Map<SseEmitter, String> clients = (Map<SseEmitter, String>) field.get(hub);
    SseEmitter alice = mock(SseEmitter.class), bob = mock(SseEmitter.class);
    String a = "11111111-1111-1111-1111-111111111111", b = "22222222-2222-2222-2222-222222222222";
    clients.put(alice, a);
    clients.put(bob, b);
    UserScope.run(
        new UserScope.Identity(a, "token", System.currentTimeMillis() / 1000 + 60),
        () -> hub.emit("quote-update", Collections.singletonMap("price", 100)));
    verify(alice).send(any(SseEmitter.SseEventBuilder.class));
    verifyNoInteractions(bob);
  }

  @Test
  @SuppressWarnings("unchecked")
  void expiredStreamsCloseBeforeReceivingAnotherEvent() throws Exception {
    EventHub hub = new EventHub();
    java.lang.reflect.Field clientsField = EventHub.class.getDeclaredField("clients");
    java.lang.reflect.Field expiryField = EventHub.class.getDeclaredField("expirations");
    clientsField.setAccessible(true);
    expiryField.setAccessible(true);
    Map<SseEmitter, String> clients = (Map<SseEmitter, String>) clientsField.get(hub);
    Map<SseEmitter, Long> expirations = (Map<SseEmitter, Long>) expiryField.get(hub);
    SseEmitter emitter = mock(SseEmitter.class);
    String owner = "11111111-1111-1111-1111-111111111111";
    clients.put(emitter, owner);
    expirations.put(emitter, 1L);
    UserScope.run(
        new UserScope.Identity(owner, "expired", 1L),
        () -> hub.emit("quote-update", Collections.emptyMap()));
    verify(emitter).complete();
    verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    org.junit.jupiter.api.Assertions.assertTrue(clients.isEmpty());
    org.junit.jupiter.api.Assertions.assertTrue(expirations.isEmpty());
  }
}
