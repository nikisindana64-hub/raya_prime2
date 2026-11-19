package com.mamarala.rayaprime.entity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Потокобезопасный реестр «у какого игрока сейчас есть живая Райя». Нужен, потому что голосовой плагин
 * работает в чужом потоке и не имеет права трогать мир (getEntitiesOfClass и т.п.).
 * Обновляется из серверного потока самой Раей.
 */
public final class RayaPresence {
    private static final Map<UUID, Long> LAST_SEEN_MS = new ConcurrentHashMap<>();
    private static final long TTL_MS = 4000L;

    private RayaPresence() {}

    public static void mark(UUID owner) {
        if (owner != null) {
            LAST_SEEN_MS.put(owner, System.currentTimeMillis());
        }
    }

    public static boolean hasRaya(UUID owner) {
        Long t = LAST_SEEN_MS.get(owner);
        return t != null && System.currentTimeMillis() - t <= TTL_MS;
    }

    public static void clear() {
        LAST_SEEN_MS.clear();
    }
}
