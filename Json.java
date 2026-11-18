package pe.edu.esfap.portal;

import java.util.LinkedHashMap;
import java.util.Map;

/** Utilidad mínima para armar respuestas JSON con orden estable. */
final class Json {
    private Json() {}
    static Map<String, Object> m(Object... kv) {
        var r = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]);
        return r;
    }
}
