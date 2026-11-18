package pe.edu.esfap.portal;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * RF-02 Pagos: cronograma de cuotas con mora por día de atraso, pago en línea, registro en ventanilla y conciliación.
 * IMPORTANTE: la pasarela es SIMULADA. El endpoint /pagar es el punto donde se integra la pasarela real
 * (confirmación firmada del proveedor); hasta entonces no se cobra dinero.
 */
@RestController @RequestMapping("/api/pagos")
public class PagosController {
    static final BigDecimal TASA = new BigDecimal("0.00027");   // mora diaria sobre el monto de la cuota
    static final List<String> MEDIOS_ONLINE = List.of("Tarjeta", "Yape", "Transferencia");
    static final List<String> MEDIOS_VENTANILLA = List.of("Ventanilla", "Efectivo", "Transferencia");
    record Medio(String medio) {}
    record Cronograma(String estudiante, String nombre, String periodo, BigDecimal ratificacion, Integer cuotas, BigDecimal monto, String primerVencimiento) {}

    private final CuotaRepo repo; private final UserRepo users; private final InscripcionRepo inscripciones;
    private final Auth auth; private final Audit audit; private final RateLimiter rl;
    public PagosController(CuotaRepo r, UserRepo u, InscripcionRepo i, Auth a, Audit au, RateLimiter rt) { repo = r; users = u; inscripciones = i; auth = a; audit = au; rl = rt; }

    private ResponseEntity<Map<String, Object>> err(int s, String m) { return ResponseEntity.status(s).body(Json.m("error", m)); }
    private static LocalDate hoy() { return LocalDate.now(AcademicoController.LIMA); }

    static BigDecimal mora(Cuota c, LocalDate hasta) {
        if (c.vencimiento == null || !hasta.isAfter(c.vencimiento)) return BigDecimal.ZERO;
        return c.monto.multiply(TASA).multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(c.vencimiento, hasta))).setScale(2, RoundingMode.HALF_UP);
    }

    private Map<String, Object> dto(Cuota c) {
        boolean pend = "Pendiente".equals(c.estado);
        var m = pend ? mora(c, hoy()) : c.mora;
        return Json.m("id", c.id, "estudiante", c.estudiante, "nombre", c.nombre, "periodo", c.periodo, "concepto", c.concepto, "numero", c.numero,
            "vencimiento", c.vencimiento.toString(), "monto", c.monto, "mora", m, "total", c.monto.add(m), "estado", c.estado,
            "vencida", pend && hoy().isAfter(c.vencimiento), "fechaPago", c.fechaPago == null ? null : c.fechaPago.toString(),
            "medio", c.medio, "recibo", c.recibo, "conciliado", c.conciliado);
    }

    private void cobrar(Cuota c, String medio, String quien) {
        c.mora = mora(c, hoy()); c.estado = "Pagado"; c.fechaPago = hoy(); c.medio = medio; c.registradoPor = quien;
        c = repo.save(c); c.recibo = "R-" + hoy().getYear() + "-" + String.format("%06d", c.id); repo.save(c);
    }

    @GetMapping("/mios")
    ResponseEntity<?> mios(HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!Policy.canPay(u.role)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(repo.findByEstudianteOrderByVencimientoAsc(u.username).stream().map(this::dto).toList());
    }

    /** Pago en línea del propio estudiante (pasarela simulada). */
    @PostMapping("/{id}/pagar")
    ResponseEntity<?> pagar(@PathVariable Long id, @RequestBody Medio b, HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!Policy.canPay(u.role)) return ResponseEntity.status(403).build();
        if (!rl.allow("pay:" + u.username, 20, 3_600_000)) return err(429, "Demasiados intentos de pago. Intente más tarde.");
        var c = repo.findById(id).filter(x -> x.estudiante.equals(u.username)).orElse(null); if (c == null) return ResponseEntity.notFound().build();
        if (!"Pendiente".equals(c.estado)) return err(400, "Esta cuota ya fue pagada.");
        if (b.medio() == null || !MEDIOS_ONLINE.contains(b.medio())) return err(400, "Medio de pago no válido.");
        cobrar(c, b.medio() + " (simulado)", u.username);
        audit.log(u.username, "Pago en línea simulado " + c.recibo + " " + c.concepto);
        return ResponseEntity.ok(dto(c));
    }

    // ---------- Administración ----------
    @GetMapping
    ResponseEntity<?> todas(HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!Policy.canManagePagos(u.role)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(repo.findAllByOrderByVencimientoAsc().stream().limit(3000).map(this::dto).toList());
    }

    @PostMapping("/{id}/registrar")
    ResponseEntity<?> registrar(@PathVariable Long id, @RequestBody Medio b, HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!Policy.canManagePagos(u.role)) return ResponseEntity.status(403).build();
        var c = repo.findById(id).orElse(null); if (c == null) return ResponseEntity.notFound().build();
        if (!"Pendiente".equals(c.estado)) return err(400, "La cuota ya está pagada.");
        if (b.medio() == null || !MEDIOS_VENTANILLA.contains(b.medio())) return err(400, "Medio de pago no válido.");
        cobrar(c, b.medio(), u.username); c.conciliado = true; repo.save(c);
        audit.log(u.username, "Pago registrado en " + b.medio() + " " + c.recibo + " (" + c.estudiante + ")");
        return ResponseEntity.ok(dto(c));
    }

    @PutMapping("/{id}/conciliar")
    ResponseEntity<?> conciliar(@PathVariable Long id, HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!Policy.canManagePagos(u.role)) return ResponseEntity.status(403).build();
        var c = repo.findById(id).orElse(null); if (c == null) return ResponseEntity.notFound().build();
        if (!"Pagado".equals(c.estado)) return err(400, "Solo se concilian pagos realizados.");
        c.conciliado = true; repo.save(c); audit.log(u.username, "Pago conciliado " + c.recibo);
        return ResponseEntity.ok(dto(c));
    }

    @PostMapping("/cronograma")
    ResponseEntity<?> generar(@RequestBody Cronograma b, HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!Policy.canManagePagos(u.role)) return ResponseEntity.status(403).build();
        if (b.estudiante() == null || b.periodo() == null || !b.periodo().matches("\\d{4}-(I|II)")) return err(400, "Indique estudiante y periodo (ej. 2026-II).");
        var us = users.findById(b.estudiante()).filter(x -> "est".equals(x.role)).orElse(null);
        String nombre = us != null ? us.name : inscripciones.findByEstudiante(b.estudiante()).stream().map(i -> i.nombre).findFirst().orElse(null);
        if (nombre == null) return err(400, "No existe un estudiante con ese código.");
        int n = b.cuotas() == null ? 0 : b.cuotas();
        if (n < 1 || n > 12 || b.monto() == null || b.monto().signum() <= 0 || b.monto().compareTo(new BigDecimal("5000")) > 0) return err(400, "Revise número de cuotas (1–12) y monto (hasta S/ 5000).");
        LocalDate v; try { v = LocalDate.parse(b.primerVencimiento()); } catch (Exception e) { return err(400, "Fecha de primer vencimiento no válida."); }
        if (repo.existsByEstudianteAndPeriodo(b.estudiante(), b.periodo())) return err(409, "El estudiante ya tiene cronograma en ese periodo.");
        if (b.ratificacion() != null && b.ratificacion().signum() > 0) nueva(b.estudiante(), nombre, b.periodo(), "RATIFICACIÓN DE MATRÍCULA", 0, v.minusDays(7), b.ratificacion());
        for (int i = 1; i <= n; i++) nueva(b.estudiante(), nombre, b.periodo(), "CUOTA " + i, i, v.plusMonths(i - 1), b.monto().setScale(2, RoundingMode.HALF_UP));
        audit.log(u.username, "Cronograma generado " + b.estudiante() + " " + b.periodo());
        return ResponseEntity.status(201).body(Json.m("ok", true));
    }

    private void nueva(String est, String nombre, String per, String concepto, int num, LocalDate venc, BigDecimal monto) {
        var c = new Cuota(); c.estudiante = est; c.nombre = nombre; c.periodo = per; c.concepto = concepto; c.numero = num; c.vencimiento = venc; c.monto = monto.setScale(2, RoundingMode.HALF_UP); repo.save(c);
    }

    @GetMapping("/resumen")
    ResponseEntity<?> resumen(HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!Policy.canManagePagos(u.role)) return ResponseEntity.status(403).build();
        BigDecimal rec = BigDecimal.ZERO, pend = BigDecimal.ZERO, venc = BigDecimal.ZERO; long porConc = 0;
        for (var c : repo.findAll()) {
            if ("Pagado".equals(c.estado)) { rec = rec.add(c.monto).add(c.mora); if (!c.conciliado) porConc++; }
            else { pend = pend.add(c.monto); if (hoy().isAfter(c.vencimiento)) venc = venc.add(c.monto); }
        }
        return ResponseEntity.ok(Json.m("recaudado", rec, "pendiente", pend, "vencido", venc, "porConciliar", porConc));
    }
}
