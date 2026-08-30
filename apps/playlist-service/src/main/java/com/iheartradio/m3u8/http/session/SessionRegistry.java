package com.iheartradio.m3u8.http.session;

import com.iheartradio.m3u8.http.DemoLog;
import com.iheartradio.m3u8.http.DemoSession;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory demo sessions. One registry per {@code DemoPlayerServer}.
 */
public final class SessionRegistry {

    private final Map<String, DemoSession> sessions = new ConcurrentHashMap<String, DemoSession>();
    private final AtomicLong counter = new AtomicLong(0);
    private final long ttlMs;

    public SessionRegistry(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public int size() {
        return sessions.size();
    }

    public String nextId() {
        return Long.toString(counter.incrementAndGet(), 36)
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public DemoSession create(DemoSession session) {
        expireOld();
        sessions.put(session.id, session);
        logSession(session);
        return session;
    }

    public DemoSession createFromJson(String json) {
        return create(DemoSession.fromJson(nextId(), json));
    }

    public DemoSession require(String id) {
        if (id == null) {
            return null;
        }
        DemoSession s = sessions.get(id);
        if (s == null || s.isExpired(ttlMs)) {
            if (s != null) {
                sessions.remove(id);
            }
            return null;
        }
        return s;
    }

    private void expireOld() {
        for (Map.Entry<String, DemoSession> e : sessions.entrySet()) {
            if (e.getValue().isExpired(ttlMs)) {
                sessions.remove(e.getKey());
            }
        }
    }

    private static void logSession(DemoSession session) {
        if (session == null) {
            return;
        }
        StringBuilder offs = new StringBuilder("[");
        for (int i = 0; i < session.breaks.size(); i++) {
            if (i > 0) {
                offs.append(',');
            }
            offs.append(session.breaks.get(i).offsetSec);
        }
        offs.append(']');
        DemoLog.event("session")
                .sid(session.id)
                .put("strategy", session.strategy.wireName())
                .put("forceVod", session.forceVod)
                .put("contentUrl", session.contentUrl)
                .put("adUrl", session.adUrl)
                .put("breakCount", session.breaks.size())
                .putRaw("splices", offs.toString())
                .write();
    }
}
