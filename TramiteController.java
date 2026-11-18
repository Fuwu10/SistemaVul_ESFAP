package pe.edu.esfap.portal;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * RF-04 / RF-05 Mesa de partes virtual: recepción de documentos con CARGO DIGITAL, seguimiento por estados,
 * subsanación de observaciones y emisión con código verificable. Expedientes y adjuntos son nivel N3.
 */
@RestController @RequestMapping("/api/mp")
public class TramiteController {
    static final long MAX = 5L * 1024 * 1024;
    static final int MAX_ARCHIVOS = 3, MAX_POR_TRAMITE = 10;
    private static final Path DIR = Path.of("data", "uploads").toAbsolutePath().normalize();
    record Cambio(String estado, String nota) {}
    record Archivo(String original, String ext, byte[] datos) {}
    record Lectura(List<Archivo> ok, String error) {}

    private final TramiteRepo tr; private final AdjuntoRepo ar; private final EventoRepo er; private final MatriculaRepo mr;
    private final Auth auth; private final Audit audit; private final Seguridad seg; private final RateLimiter rl;
    private final SecureRandom rnd = new SecureRandom();

    public TramiteController(TramiteRepo t, AdjuntoRepo a, EventoRepo e, MatriculaRepo m, Auth au, Audit ad, Seguridad s, RateLimiter r) {
        tr = t; ar = a; er = e; mr = m; auth = au; audit = ad; seg = s; rl = r;
    }

    private ResponseEntity<Map<String, Object>> err(int s, String m) { return ResponseEntity.status(s).body(Json.m("error", m)); }

    // ---------- utilidades ----------
    private Lectura leer(MultipartFile[] fs, int max) throws IOException {
        var l = new ArrayList<Archivo>();
        if (fs != null) for (var f : fs) {
            if (f == null || f.isEmpty()) continue;
            if (f.getSize() > MAX) return new Lectura(l, "Cada archivo debe pesar como máximo 5 MB.");
            byte[] d = f.getBytes();
            String ext = Archivos.tipo(d);
            if (ext == null) return new Lectura(l, "Formato no permitido: solo PDF, JPG o PNG.");
            l.add(new Archivo(Archivos.limpiarNombre(f.getOriginalFilename()), ext, d));
        }
        if (l.isEmpty()) return new Lectura(l, "Adjunte al menos un documento.");
        if (l.size() > max) return new Lectura(l, "Puede adjuntar hasta " + max + " archivos por envío.");
        return new Lectura(l, null);
    }

    private Adjunto guardar(Long tid, Archivo a, String por, boolean inicial) throws IOException {
        Files.createDirectories(DIR);
        var x = new Adjunto();
        x.tramiteId = tid; x.original = a.original(); x.stored = UUID.randomUUID() + "." + a.ext(); x.mime = Archivos.mime(a.ext());
        x.tam = a.datos().length; x.sha256 = Seguridad.sha256(a.datos()); x.por = por; x.inicial = inicial;
        Files.write(DIR.resolve(x.stored), a.datos());
        return ar.save(x);
    }

    private void evento(Long tid, String actor, String estado, String nota) {
        var e = new Evento(); e.tramiteId = tid; e.actor = actor; e.estado = estado; e.nota = nota == null ? null : nota.substring(0, Math.min(500, nota.length())); er.save(e);
    }

    private Map<String, Object> resumen(Tramite t) {
        return Json.m("id", t.id, "codigo", t.codigo, "tipo", t.tipo, "asunto", t.asunto, "estado", t.estado, "nivel", t.nivel,
            "estudiante", t.estudiante, "nombre", t.nombre, "creado", t.creado, "actualizado", t.actualizado,
            "recepcion", t.recepcion, "finalizado", t.finalizado, "area", t.area != null ? t.area : Policy.AREA.getOrDefault(t.tipo, "Mesa de partes"),
            "requisitos", Policy.REQUISITOS.getOrDefault(t.tipo, ""));
    }

    private Map<String, Object> detalle(Tramite t, AppUser u) {
        var ad = ar.findByTramiteIdOrderByIdAsc(t.id);
        var adj = ad.stream().map(a -> Json.m("id", a.id, "nombre", a.original, "mime", a.mime, "tam", a.tam, "sha256", a.sha256, "fecha", a.fecha, "inicial", a.inicial)).toList();
        var ev = er.findByTramiteIdOrderByIdAsc(t.id).stream().map(e -> Json.m("fecha", e.fecha, "actor", e.actor, "estado", e.estado, "nota", e.nota)).toList();
        var cargo = Json.m("codigo", t.codigo, "fecha", t.creado, "sello", t.cargoSello, "algoritmo", "HMAC-SHA256",
            "archivos", ad.stream().filter(a -> a.inicial).map(a -> Json.m("nombre", a.original, "sha256", a.sha256)).toList());
        var d = resumen(t);
        d.put("descripcion", t.descripcion); d.put("codigoVerificacion", t.codigoVerificacion);
        d.put("adjuntos", adj); d.put("eventos", ev); d.put("cargo", cargo);
        d.put("siguientes", Policy.canReviewTramites(u.role) ? Policy.siguientes(t.estado) : List.of());
        d.put("puedeSubsanar", "Observado".equals(t.estado) && t.estudiante.equals(u.username));
        return d;
    }

    /** Devuelve el trámite solo si el usuario puede leerlo; si no, "no existe" (evita enumerar expedientes). */
    private Tramite acceso(Long id, AppUser u) {
        var t = tr.findById(id).orElse(null);
        return t != null && Policy.canReadExpediente(u.role, t.estudiante, u.username) ? t : null;
    }

    // ---------- estudiante / docente ----------
    @PostMapping(value = "/tramites", consumes = "multipart/form-data")
    ResponseEntity<?> crear(@RequestParam String tipo, @RequestParam String asunto, @RequestParam(required = false) String descripcion,
                            @RequestParam("files") MultipartFile[] files, HttpServletRequest q) throws IOException {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!Policy.canSubmitTramite(u.role)) return ResponseEntity.status(403).build();
        if (!rl.allow("mp:" + u.username, 10, 3_600_000)) return err(429, "Ha enviado demasiados trámites en poco tiempo. Intente más tarde.");
        if (!Policy.TIPOS.containsKey(tipo)) return err(400, "Tipo de trámite no válido.");
        asunto = asunto == null ? "" : asunto.trim();
        if (asunto.length() < 5 || asunto.length() > 120) return err(400, "El asunto debe tener entre 5 y 120 caracteres.");
        if (descripcion != null && descripcion.length() > 1500) return err(400, "La descripción es demasiado larga.");
        var lec = leer(files, MAX_ARCHIVOS);
        if (lec.error() != null) return err(400, lec.error());

        var t = new Tramite();
        t.codigo = "PEND"; t.estudiante = u.username; t.nombre = u.name; t.tipo = tipo; t.asunto = asunto;
        t.area = Policy.AREA.getOrDefault(tipo, "Mesa de partes");
        t.descripcion = descripcion == null || descripcion.isBlank() ? null : descripcion.trim(); t.nivel = Policy.nivelTramite(tipo);
        t = tr.save(t);
        t.codigo = "MP-" + java.time.Year.now() + "-" + String.format("%06d", t.id);
        var guardados = new ArrayList<Adjunto>();
        for (var a : lec.ok()) guardados.add(guardar(t.id, a, u.username, true));
        t.cargoSello = seg.selloCargo(t, guardados);
        t = tr.save(t);
        evento(t.id, u.username, "Recibido", "Documentos recibidos y cargo digital emitido.");
        audit.log(u.username, "Trámite recibido " + t.codigo + " (" + guardados.size() + " archivo/s)");
        return ResponseEntity.status(201).body(detalle(t, u));
    }

    @GetMapping("/tramites")
    ResponseEntity<?> listar(HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        List<Tramite> l;
        if (Policy.canReviewTramites(u.role)) l = tr.findAllByOrderByIdDesc();
        else if (Policy.canSubmitTramite(u.role)) l = tr.findByEstudianteOrderByIdDesc(u.username);
        else return ResponseEntity.status(403).build();
        return ResponseEntity.ok(l.stream().limit(500).map(this::resumen).toList());
    }

    @GetMapping("/tramites/{id}")
    ResponseEntity<?> ver(@PathVariable Long id, HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        var t = acceso(id, u); if (t == null) return ResponseEntity.notFound().build();
        if (!t.estudiante.equals(u.username)) audit.log(u.username, "Consulta de expediente " + t.codigo);
        return ResponseEntity.ok(detalle(t, u));
    }

    @GetMapping("/tramites/{id}/adjuntos/{aid}")
    ResponseEntity<byte[]> bajar(@PathVariable Long id, @PathVariable Long aid, HttpServletRequest q) throws IOException {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        var t = acceso(id, u); if (t == null) return ResponseEntity.notFound().build();
        var a = ar.findById(aid).filter(x -> id.equals(x.tramiteId)).orElse(null); if (a == null) return ResponseEntity.notFound().build();
        Path p = DIR.resolve(a.stored).normalize();
        if (!p.startsWith(DIR) || !Files.exists(p)) return ResponseEntity.notFound().build();
        audit.log(u.username, "Descarga de adjunto " + t.codigo + " / " + a.original);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(a.mime))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(a.original, StandardCharsets.UTF_8).build().toString())
            .body(Files.readAllBytes(p));
    }

    @PostMapping(value = "/tramites/{id}/subsanar", consumes = "multipart/form-data")
    ResponseEntity<?> subsanar(@PathVariable Long id, @RequestParam(required = false) String nota, @RequestParam("files") MultipartFile[] files, HttpServletRequest q) throws IOException {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        var t = tr.findById(id).filter(x -> x.estudiante.equals(u.username)).orElse(null);
        if (t == null) return ResponseEntity.notFound().build();
        if (!"Observado".equals(t.estado)) return err(400, "El trámite no tiene observaciones por subsanar.");
        var lec = leer(files, MAX_ARCHIVOS); if (lec.error() != null) return err(400, lec.error());
        if (ar.findByTramiteIdOrderByIdAsc(id).size() + lec.ok().size() > MAX_POR_TRAMITE) return err(400, "Se alcanzó el máximo de documentos del expediente.");
        for (var a : lec.ok()) guardar(id, a, u.username, false);
        t.estado = "En revisión"; t.actualizado = Instant.now(); tr.save(t);
        evento(id, u.username, "En revisión", "Subsanación enviada (" + lec.ok().size() + " archivo/s)." + (nota == null || nota.isBlank() ? "" : " " + nota.trim()));
        audit.log(u.username, "Subsanación enviada " + t.codigo);
        return ResponseEntity.ok(detalle(t, u));
    }

    // ---------- administración ----------
    @PutMapping("/tramites/{id}/estado")
    ResponseEntity<?> estado(@PathVariable Long id, @RequestBody Cambio b, HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!Policy.canReviewTramites(u.role)) return ResponseEntity.status(403).build();
        var t = tr.findById(id).orElse(null); if (t == null) return ResponseEntity.notFound().build();
        if (b.estado() == null || !Policy.transicionValida(t.estado, b.estado())) return err(400, "Cambio de estado no permitido desde «" + t.estado + "».");
        String nota = b.nota() == null ? "" : b.nota().trim();
        if (("Observado".equals(b.estado()) || "Rechazado".equals(b.estado())) && nota.length() < 5) return err(400, "Indique el motivo u observación (mín. 5 caracteres).");
        t.estado = b.estado(); t.actualizado = Instant.now();
        if ("En revisión".equals(t.estado) && t.recepcion == null) t.recepcion = Instant.now();
        if ("Rechazado".equals(t.estado) || "Emitido".equals(t.estado)) t.finalizado = Instant.now();
        if ("Emitido".equals(t.estado) && t.codigoVerificacion == null) {
            String al = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; var sb = new StringBuilder("ESFAP-");
            for (int i = 0; i < 6; i++) sb.append(al.charAt(rnd.nextInt(al.length())));
            t.codigoVerificacion = sb.toString();
        }
        tr.save(t); evento(id, u.username, t.estado, nota.isEmpty() ? null : nota);
        audit.log(u.username, "Trámite " + t.codigo + " → " + t.estado);
        return ResponseEntity.ok(detalle(t, u));
    }

    @GetMapping("/kpi")
    ResponseEntity<?> kpi(HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!Policy.canReviewTramites(u.role)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(Json.m("matPend", mr.countByEstado("Pendiente"), "tramProc", tr.countByEstadoIn(List.of("Recibido", "En revisión"))));
    }

    // ---------- público (nivel N1) ----------
    /** Consulta pública: exige el código del cargo Y el identificador del solicitante. Muestra solo estados y fechas, sin datos personales. */
    @GetMapping("/seguimiento")
    ResponseEntity<?> seguimiento(@RequestParam String codigo, @RequestParam String id, HttpServletRequest q) {
        if (!rl.allow("seg:" + q.getRemoteAddr(), 15, 300_000)) return err(429, "Demasiadas consultas. Espere unos minutos.");
        if (codigo.length() > 30 || id.length() > 60) return err(404, "No se encontró un trámite con esos datos.");
        var t = tr.findByCodigo(codigo.trim().toUpperCase()).filter(x -> x.estudiante.equalsIgnoreCase(id.trim())).orElse(null);
        if (t == null) return err(404, "No se encontró un trámite con esos datos.");
        var ev = er.findByTramiteIdOrderByIdAsc(t.id).stream().map(e -> Json.m("fecha", e.fecha, "estado", e.estado)).toList();
        return ResponseEntity.ok(Json.m("codigo", t.codigo, "tipo", t.tipo, "estado", t.estado, "creado", t.creado, "actualizado", t.actualizado, "eventos", ev));
    }
}
