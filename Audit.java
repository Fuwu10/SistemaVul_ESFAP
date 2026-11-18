package pe.edu.esfap.portal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Auditoría con cadena de hashes: cada registro incluye el hash del anterior, así una alteración se detecta. */
@Component
public class Audit {
    private final LogRepo logs;
    public Audit(LogRepo l) { logs = l; }

    private static String cut(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n) : s; }
    static String calc(LogEntry e) { return Seguridad.sha256((e.prev + "|" + e.at + "|" + e.actor + "|" + e.action).getBytes(StandardCharsets.UTF_8)); }

    public synchronized void log(String who, String what) {
        var last = logs.findTopByOrderByIdDesc();
        var e = new LogEntry();
        e.at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        e.actor = cut(who, 60);
        e.action = cut(what, 240);
        e.prev = last != null && last.hash != null ? last.hash : "";
        e.hash = calc(e);
        logs.save(e);
    }

    /** Recorre la cadena y reporta el primer registro alterado (los anteriores a esta versión no tienen hash y se omiten). */
    public Map<String, Object> verify() {
        String prev = "";
        int total = 0, ok = 0;
        for (var e : logs.findAllByOrderByIdAsc()) {
            total++;
            if (e.hash == null) continue;
            if (!prev.equals(e.prev == null ? "" : e.prev) || !calc(e).equals(e.hash))
                return Json.m("ok", false, "total", total, "verificados", ok, "falla", e.id);
            prev = e.hash; ok++;
        }
        return Json.m("ok", true, "total", total, "verificados", ok);
    }
}
