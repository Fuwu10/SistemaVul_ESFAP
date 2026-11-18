package pe.edu.esfap.portal;

import jakarta.persistence.EntityManager;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * ===== SOLO PARA LABORATORIO DE CIBERSEGURIDAD =====
 * Endpoints deliberadamente VULNERABLES. Solo existen si se arranca con LAB_VULN=true (por defecto NO se cargan).
 * Usar únicamente en local (localhost) y con datos ficticios. NUNCA desplegar con LAB_VULN=true.
 *
 *  - Inyección (SQLi): /api/lab/buscar y /api/lab/login concatenan la entrada en la consulta SQL.
 *  - Exposición de datos sensibles: /api/lab/usuarios, /api/lab/config, /api/lab/backup (sin autenticación),
 *    y errores con traza completa. Las contraseñas del intento de login se escriben en claro en la consola.
 */
@RestController @RequestMapping("/api/lab")
@ConditionalOnProperty(name = "portal.lab-vulnerable", havingValue = "true")
public class LabController {
    private final EntityManager em;
    @Value("${spring.datasource.url}") private String dbUrl;

    public LabController(EntityManager em) { this.em = em; }

    private static String trace(Exception e) {
        var sw = new StringWriter(); e.printStackTrace(new PrintWriter(sw)); return sw.toString();
    }

    // ---------- A02: Inyección ----------

    /** VULNERABLE: la búsqueda se concatena directo en SQL nativo. */
    @GetMapping("/buscar")
    ResponseEntity<?> buscar(@RequestParam(defaultValue = "") String q) {
        String sql = "SELECT username, name, role FROM usuarios WHERE name LIKE '%" + q + "%'";
        try {
            @SuppressWarnings("unchecked") List<Object[]> rows = em.createNativeQuery(sql).getResultList();
            return ResponseEntity.ok(rows.stream().map(r -> Map.of("username", String.valueOf(r[0]), "name", String.valueOf(r[1]), "role", String.valueOf(r[2]))).toList());
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage(), "sql", sql, "trace", trace(e))); // A03: error verboso
        }
    }

    record LoginLab(String id, String pw) {}

    /** VULNERABLE: compara el hash dentro del SQL y concatena usuario/clave (permite bypass tipo ' OR '1'='1). */
    @PostMapping("/login")
    ResponseEntity<?> login(@RequestBody LoginLab b) {
        System.out.println("[LAB] intento de login: usuario=" + b.id() + " clave=" + b.pw()); // A03: clave en claro en logs
        String sql = "SELECT username, role FROM usuarios WHERE username = '" + b.id() + "' AND password_hash = '" + b.pw() + "'";
        try {
            @SuppressWarnings("unchecked") List<Object[]> rows = em.createNativeQuery(sql).getResultList();
            if (rows.isEmpty()) return ResponseEntity.status(401).body(Map.of("ok", false));
            return ResponseEntity.ok(Map.of("ok", true, "username", String.valueOf(rows.get(0)[0]), "role", String.valueOf(rows.get(0)[1])));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage(), "sql", sql, "trace", trace(e)));
        }
    }

    // ---------- A03: Exposición de datos sensibles ----------

    /** VULNERABLE: sin autenticación y devuelve el hash de contraseña. */
    @GetMapping("/usuarios")
    List<?> usuarios() {
        return em.createQuery("select u from AppUser u").getResultList();
    }

    /** VULNERABLE: expone configuración interna y la clave HMAC del sistema. */
    @GetMapping("/config")
    Map<String, Object> config() throws Exception {
        Path p = Path.of("data", "secret.key");
        return Map.of("datasource", dbUrl, "hmacKeyHex", Files.exists(p) ? Seguridad.hex(Files.readAllBytes(p)) : "(no existe)",
            "java", System.getProperty("java.version"), "os", System.getProperty("os.name"), "dir", System.getProperty("user.dir"));
    }

    /** VULNERABLE: entrega el archivo de clave sin control de acceso ni cifrado. */
    @GetMapping("/backup")
    ResponseEntity<byte[]> backup() throws Exception {
        Path p = Path.of("data", "secret.key");
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=secret.key").body(Files.readAllBytes(p));
    }
}
