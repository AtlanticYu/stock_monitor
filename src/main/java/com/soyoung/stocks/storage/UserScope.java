package com.soyoung.stocks.storage;

import java.util.function.Supplier;

/** Request identity verified by Supabase Auth; propagated explicitly to background work. */
public final class UserScope {
  private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();

  private UserScope() {}

  public static final class Identity {
    public final String userId, token;
    public final long expiresAt;

    public Identity(String userId, String token, long expiresAt) {
      this.userId = java.util.UUID.fromString(userId).toString();
      this.token = token;
      this.expiresAt = expiresAt;
    }
  }

  public static Identity current() {
    return CURRENT.get();
  }

  public static Identity required() {
    Identity identity = current();
    if (identity == null || identity.expiresAt <= System.currentTimeMillis() / 1000)
      throw new IllegalStateException("Supabase 登录已过期，请重新登录");
    return identity;
  }

  public static String owner() {
    return current() == null ? "local" : current().userId;
  }

  public static <T> T with(Identity identity, Supplier<T> action) {
    Identity old = CURRENT.get();
    try {
      if (identity == null) CURRENT.remove();
      else CURRENT.set(identity);
      return action.get();
    } finally {
      if (old == null) CURRENT.remove();
      else CURRENT.set(old);
    }
  }

  public static void run(Identity identity, Runnable action) {
    with(
        identity,
        () -> {
          action.run();
          return null;
        });
  }
}
