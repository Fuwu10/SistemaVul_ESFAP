package pe.edu.esfap.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

/** API REST: autenticación, sesión, datos institucionales, usuarios, auditoría, política de seguridad y verificación pública. */
@RestController @RequestMapping("/api")
public class ApiController {
    record Login(String id, String pw) {}
    record Pass(String old, String neu) {}
    record NewUser(String username, String name, String role, String pw) {}
    private final UserRepo users; private final StoreRepo store; private final LogRepo logs;
    private final TramiteRepo tramites; private final AdjuntoRepo adjuntos;
    private final Auth auth; private final Audit audit; private final RateLimiter rl; private final Seguridad seg;
    private final BCryptPasswordEncoder enc = new BCryptPasswordEncoder(12);
    private final String dummyHash = enc.encode("no-existe-" + System.nanoTime());
    private final ObjectMapper om = new ObjectMapper();
    private final SecureRandom rnd = new SecureRandom();

    public ApiController(UserRepo u, StoreRepo s, LogRepo l, TramiteRepo t, AdjuntoRepo a, Auth au, Audit ad, RateLimiter r, Seguridad sg) {
        users = u; store = s; logs = l; tramites = t; adjuntos = a; auth = au; audit = ad; rl = r; seg = sg;
    }

    Map<String, Object> view(AppUser u, HttpServletRequest q) {
        var s = q.getSession(false);
        return Json.m("id", u.username, "role", u.role, "name", u.name,
            "mustChange", Boolean.TRUE.equals(u.mustChange), "csrf", s == null ? "" : String.valueOf(s.getAttribute("csrf")));
    }

    /** Bloqueo temporal (15 min). Una cuenta bloqueada con la regla anterior (sin fecha) sigue bloqueada hasta que Soporte la libere. */
    private boolean isLocked(AppUser u, long now) {
        if (!Policy.locked(u.fails)) return false;
        if (u.lockedUntil == null || u.lockedUntil > now) return true;
        u.fails = 0; u.lockedUntil = null; users.save(u);
        return false;
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@RequestBody Login b, HttpServletRequest q) {
        if (!rl.allow("login:" + q.getRemoteAddr(), 10, 300_000)) return ResponseEntity.status(429).build();
        var id = b.id() == null ? "" : b.id().trim();
        var u = id.length() > 60 ? null : users.findById(id).orElse(null);
        // Se compara siempre contra un hash (aunque el usuario no exista) para igualar tiempos y no revelar cuentas.
        boolean pwOk = enc.matches(b.pw() == null ? "" : b.pw(), u != null ? u.passwordHash : dummyHash);
        long now = System.currentTimeMillis();
        boolean locked = u != null && isLocked(u, now);
        if (u == null || !pwOk) {
            if (u != null && u.active && !locked) {
                u.fails++;
                if (Policy.locked(u.fails)) u.lockedUntil = now + Policy.LOCK_MS;
                users.save(u);
            }
            audit.log(id.length() > 60 ? "?" : id, u == null ? "Login fallido: usuario inexistente" : "Login fallido (" + u.fails + ")");
            return ResponseEntity.status(401).build();
        }
        if (locked || !u.active) { audit.log(id, "Login rechazado: cuenta bloqueada o inactiva"); return ResponseEntity.status(423).build(); }
        u.fails = 0; u.lockedUntil = null; users.save(u);
        var old = q.getSession(false); if (old != null) old.invalidate();
        var s = q.getSession(true);
        s.setAttribute("u", id);
        byte[] t = new byte[32]; rnd.nextBytes(t);
        s.setAttribute("csrf", Base64.getUrlEncoder().withoutPadding().encodeToString(t));
        audit.log(id, "Inicio de sesión");
        return ResponseEntity.ok(view(u, q));
    }

    @PostMapping("/logout")
    ResponseEntity<?> logout(HttpServletRequest q) {
        var u = auth.current(q, true); if (u != null) audit.log(u.username, "Cierre de sesión");
        var s = q.getSession(false); if (s != null) s.invalidate();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    ResponseEntity<?> whoami(HttpServletRequest q) { var u = auth.current(q, true); return u == null ? ResponseEntity.status(401).build() : ResponseEntity.ok(view(u, q)); }

    /** Datos del portal (legado). Se filtran las colecciones que ahora tienen módulo propio y, para el estudiante, los datos de terceros. */
    @GetMapping(value = "/state", produces = "application/json")
    ResponseEntity<String> getState(HttpServletRequest q) {
        var u = auth.current(q);
        if (u == null) return ResponseEntity.status(401).build();
        String raw = store.findById(1L).map(x -> x.json).orElse("");
        if (raw.isBlank()) return ResponseEntity.ok("");
        try {
            var n = (ObjectNode) om.readTree(raw);
            n.remove("tram"); n.remove("mat"); n.remove("kpi");
            if ("est".equals(u.role)) { n.remove("roster"); n.remove("students"); n.remove("backups"); n.remove("logs"); }
            return ResponseEntity.ok(om.writeValueAsString(n));
        } catch (Exception e) { return ResponseEntity.status(500).build(); }
    }

    /** Solo personal (docente, administrativo, TI) puede guardar. Antes cualquier usuario autenticado podía sobrescribir todo. */
    @PutMapping("/state")
    ResponseEntity<?> putState(@RequestBody String json, HttpServletRequest q) {
        var u = auth.current(q);
        if (u == null) return ResponseEntity.status(401).build();
        if (!Policy.canWriteState(u.role)) return ResponseEntity.status(403).build();
        if (json.length() > 30_000_000) return ResponseEntity.status(413).build();
        try {
            var n = (ObjectNode) om.readTree(json);
            n.remove("tram"); n.remove("mat"); n.remove("kpi");
            var s = new Store(); s.json = om.writeValueAsString(n); store.save(s);
        } catch (Exception e) { return ResponseEntity.badRequest().build(); }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/password")
    ResponseEntity<?> password(@RequestBody Pass b, HttpServletRequest q) {
        var u = auth.current(q, true); if (u == null) return ResponseEntity.status(401).build();
        if (b.old() == null || !enc.matches(b.old(), u.passwordHash) || !Policy.strongPassword(b.neu(), u.username) || b.neu().equals(b.old()))
            return ResponseEntity.badRequest().build();
        u.passwordHash = enc.encode(b.neu()); u.mustChange = false; users.save(u);
        audit.log(u.username, "Cambio de contraseña");
        return ResponseEntity.ok().build();
    }

    @GetMapping("/logs")
    ResponseEntity<?> logs(HttpServletRequest q) {
        var u = auth.current(q); if (u == null || !Policy.canViewLogs(u.role)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(logs.findTop100ByOrderByIdDesc());
    }

    @GetMapping("/logs/verify")
    ResponseEntity<?> verifyLogs(HttpServletRequest q) {
        var u = auth.current(q); if (u == null || !Policy.canViewLogs(u.role)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(audit.verify());
    }

    @GetMapping("/users")
    ResponseEntity<?> listUsers(HttpServletRequest q) {
        var u = auth.current(q); if (u == null || !Policy.canManageUsers(u.role)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(users.findAll().stream().map(x -> Map.of("username", x.username, "name", x.name, "role", x.role, "active", x.active, "fails", x.fails)).toList());
    }

    @PostMapping("/users")
    ResponseEntity<?> addUser(@RequestBody NewUser b, HttpServletRequest q) {
        var u = auth.current(q); if (u == null || !Policy.canManageUsers(u.role)) return ResponseEntity.status(403).build();
        if (!Policy.validUsername(b.username()) || !Policy.validRole(b.role()) || !Policy.canAssignRole(u.role, b.role()) || b.name() == null || b.name().isBlank()
            || b.name().length() > 100 || !Policy.strongPassword(b.pw(), b.username()) || users.existsById(b.username())) return ResponseEntity.badRequest().build();
        var n = new AppUser(); n.username = b.username(); n.name = b.name().trim(); n.role = b.role(); n.passwordHash = enc.encode(b.pw()); n.mustChange = true;
        users.save(n); audit.log(u.username, "Usuario creado: " + n.username + " (" + n.role + ")");
        return ResponseEntity.ok().build();
    }

    @PutMapping("/users/{id}/toggle")
    ResponseEntity<?> toggle(@PathVariable String id, HttpServletRequest q) {
        var u = auth.current(q); if (u == null || !Policy.canManageUsers(u.role)) return ResponseEntity.status(403).build();
        var t = users.findById(id).orElse(null);
        if (t == null || t.username.equals(u.username) || !Policy.canAssignRole(u.role, t.role)) return ResponseEntity.badRequest().build();
        t.active = !t.active; t.fails = 0; t.lockedUntil = null; users.save(t);
        audit.log(u.username, (t.active ? "Usuario activado: " : "Usuario desactivado: ") + t.username);
        return ResponseEntity.ok().build();
    }

    /** Niveles de seguridad de la información y parámetros vigentes (para el panel de Configuración). */
    @GetMapping("/security/policy")
    ResponseEntity<?> policy(HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        var niveles = List.of(
            Json.m("nivel", 1, "descripcion", "Información de libre acceso.", "ejemplos", "Portada, carreras, verificación de código, consulta de trámite por código", "roles", "Todos (incluye visitantes)"),
            Json.m("nivel", 2, "descripcion", "Uso institucional; cada persona ve solo lo suyo.", "ejemplos", "Matrícula propia, notas, asistencia, biblioteca", "roles", "Estudiante, docente, administrativo, TI"),
            Json.m("nivel", 3, "descripcion", "Datos personales y expedientes. Acceso por titularidad o necesidad de gestión; toda lectura queda auditada.", "ejemplos", "Mesa de partes y sus adjuntos, matrículas, pagos", "roles", "Titular y Administración (Soporte TI no lee documentos)"),
            Json.m("nivel", 4, "descripcion", "Control del sistema.", "ejemplos", "Usuarios y roles, auditoría, configuración, respaldos", "roles", "Soporte TI (usuarios y auditoría también Administración, sin crear cuentas administrativas)"));
        var parametros = List.of(
            List.of("Cierre por inactividad", "5 minutos"), List.of("Intentos fallidos permitidos", "3, luego bloqueo temporal de 15 minutos"),
            List.of("Límite de intentos de acceso", "10 cada 5 minutos por IP"), List.of("Contraseña", "mín. 10, mayúscula, minúscula y número; hash BCrypt (costo 12)"),
            List.of("Cuentas nuevas o con clave inicial", "deben cambiar la contraseña antes de usar el portal"), List.of("Anti-CSRF", "token por sesión + cookie SameSite=Strict"),
            List.of("Adjuntos", "PDF, JPG o PNG verificados por contenido; máx. 5 MB; hasta 3 por envío; huella SHA-256"),
            List.of("Cargo digital", "sello HMAC-SHA256 verificable por código"), List.of("Auditoría", "registros encadenados por hash (detecta alteraciones)"),
            List.of("Cabeceras HTTP", "CSP, X-Frame-Options, nosniff, Referrer-Policy, Permissions-Policy, HSTS bajo HTTPS"));
        return ResponseEntity.ok(Json.m("niveles", niveles, "parametros", parametros, "miNivel", Policy.clearance(u.role)));
    }

    /** Verificación pública: código de certificado (ESFAP-…) o de cargo de mesa de partes (MP-…). */
    @GetMapping("/verify/{code}")
    ResponseEntity<Map<String, Object>> verify(@PathVariable String code, HttpServletRequest q) {
        if (!rl.allow("verify:" + q.getRemoteAddr(), 30, 60_000)) return ResponseEntity.status(429).build();
        String c = code == null ? "" : code.trim().toUpperCase();
        if (c.length() > 30) return ResponseEntity.ok(Json.m("valid", false));
        var emitido = tramites.findByCodigoVerificacion(c).orElse(null);
        if (emitido != null) return ResponseEntity.ok(Json.m("valid", true, "tipo", emitido.tipo, "estado", emitido.estado));
        var cargo = tramites.findByCodigo(c).orElse(null);
        if (cargo != null) {
            boolean integro = seg.selloCargo(cargo, adjuntos.findByTramiteIdOrderByIdAsc(cargo.id).stream().filter(a -> a.inicial).toList()).equals(cargo.cargoSello);
            return ResponseEntity.ok(Json.m("valid", integro, "tipo", "Cargo digital de mesa de partes", "estado", "Recibido el " + cargo.creado));
        }
        return ResponseEntity.ok(Json.m("valid", false));
    }
}
