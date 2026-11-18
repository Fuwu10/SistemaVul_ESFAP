package pe.edu.esfap.portal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Limitador de peticiones en memoria (ventana deslizante). Para varios servidores use Redis o el proxy inverso. */
@Component
public class RateLimiter {
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public boolean allow(String key, int max, long windowMs) {
        long now = System.currentTimeMillis();
        if (hits.size() > 20000) hits.clear();
        var d = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (d) {
            while (!d.isEmpty() && now - d.peekFirst() > windowMs) d.pollFirst();
            if (d.size() >= max) return false;
            d.addLast(now);
            return true;
        }
    }
}
