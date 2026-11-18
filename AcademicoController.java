package pe.edu.esfap.portal;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Plataforma académica (RF-03): secciones y horario, calificaciones, control y registro de asistencias,
 * justificaciones y materiales. Autorización en el servidor por sección: el docente solo gestiona las suyas,
 * Administración todas, y el estudiante solo ve lo suyo.
 */
@RestController @RequestMapping("/api/ac")
public class AcademicoController {
    static final ZoneId LIMA = ZoneId.of("America/Lima");
    static final String[] EVN = {"Práctica 1", "Examen parcial", "Práctica 2", "Examen final"};
    static final int[] PESOS = {20, 30, 20, 30};
    static final long MAX_ARCHIVO = 10L * 1024 * 1024;
    record SecEdit(String docente, String aula, Integer dia, String horaIni, String horaFin) {}
    record Nota(String estudiante, Integer idx, Double valor) {}
    record NotasReq(List<Nota> notas) {}
    record Reg(String estudiante, String estado) {}
    record AsisReq(String fecha, List<Reg> registros) {}
    record Resolucion(String estado, String nota) {}

    private final SeccionRepo sr; private final InscripcionRepo ir; private final CalificacionRepo cr; private final AsistenciaRepo asr;
    private final JustificacionRepo jr; private final MaterialRepo mr; private final UserRepo users;
    private final Auth auth; private final Audit audit; private final RateLimiter rl;

    public AcademicoController(SeccionRepo s, InscripcionRepo i, CalificacionRepo c, AsistenciaRepo a, JustificacionRepo j, MaterialRepo m,
                               UserRepo u, Auth au, Audit ad, RateLimiter r) {
        sr = s; ir = i; cr = c; asr = a; jr = j; mr = m; users = u; auth = au; audit = ad; rl = r;
    }

    private ResponseEntity<Map<String, Object>> err(int s, String m) { return ResponseEntity.status(s).body(Json.m("error", m)); }
    private static LocalDate hoy() { return LocalDate.now(LIMA); }

    /** Sección que el usuario puede GESTIONAR (docente titular o Administración); si no, null. */
    private Seccion gestiona(Long id, AppUser u) {
        var s = sr.findById(id).orElse(null);
        if (s == null) return null;
        return "adm".equals(u.role) || ("doc".equals(u.role) && u.username.equals(s.docente)) ? s : null;
    }
    /** Sección que el usuario puede VER (gestiona o está inscrito). */
    private Seccion ve(Long id, AppUser u) {
        var s = gestiona(id, u);
        if (s != null) return s;
        return "est".equals(u.role) && ir.existsBySeccionIdAndEstudiante(id, u.username) ? sr.findById(id).orElse(null) : null;
    }

    static Double promedio(Double[] v) {
        double t = 0;
        for (int i = 0; i < 4; i++) { if (v[i] == null) return null; t += v[i] * PESOS[i]; }
        return Math.round(t / 100.0 * 10) / 10.0;
    }
    static Double pct(long p, long t, long f) { long d = p + t + f; return d == 0 ? null : Math.round((p + t) * 1000.0 / d) / 10.0; }   // las justificadas no cuentan

    private Map<String, Object> dto(Seccion s) {
        return Json.m("id", s.id, "cursoId", s.cursoId, "nombre", s.nombre, "docente", s.docente, "docenteNombre", s.docenteNombre,
            "aula", s.aula, "dia", s.dia, "horaIni", s.horaIni, "horaFin", s.horaFin, "periodo", s.periodo, "alumnos", ir.countBySeccionId(s.id));
    }

    // ---------- secciones ----------
    @GetMapping("/secciones")
    ResponseEntity<?> secciones(HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        List<Seccion> l;
        switch (u.role) {
            case "adm" -> l = sr.findAll();
            case "doc" -> l = sr.findByDocente(u.username);
            case "est" -> l = sr.findAllById(ir.findByEstudiante(u.username).stream().map(i -> i.seccionId).toList());
            default -> { return ResponseEntity.status(403).build(); }
        }
        return ResponseEntity.ok(l.stream().sorted(Comparator.comparingInt((Seccion s) -> s.dia).thenComparing(s -> s.horaIni)).map(this::dto).toList());
    }

    @PutMapping("/secciones/{id}")
    ResponseEntity<?> editar(@PathVariable Long id, @RequestBody SecEdit b, HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!"adm".equals(u.role)) return ResponseEntity.status(403).build();
        var s = sr.findById(id).orElse(null); if (s == null) return ResponseEntity.notFound().build();
        var d = b.docente() == null ? null : users.findById(b.docente()).filter(x -> x.active && "doc".equals(x.role)).orElse(null);
        if (d == null) return err(400, "Seleccione un docente activo.");
        if (b.dia() == null || b.dia() < 1 || b.dia() > 7 || b.horaIni() == null || b.horaFin() == null || !b.horaIni().matches("\\d{2}:\\d{2}")
            || !b.horaFin().matches("\\d{2}:\\d{2}") || b.horaIni().compareTo(b.horaFin()) >= 0 || b.aula() == null || b.aula().isBlank() || b.aula().length() > 30)
            return err(400, "Revise día, horario (HH:MM) y aula.");
        s.docente = d.username; s.docenteNombre = d.name; s.aula = b.aula().trim(); s.dia = b.dia(); s.horaIni = b.horaIni(); s.horaFin = b.horaFin();
        sr.save(s); audit.log(u.username, "Sección actualizada: " + s.nombre + " → " + d.username);
        return ResponseEntity.ok(dto(s));
    }

    @GetMapping("/docentes")
    ResponseEntity<?> docentes(HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!"adm".equals(u.role)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(users.findAll().stream().filter(x -> x.active && "doc".equals(x.role)).map(x -> Json.m("username", x.username, "name", x.name)).toList());
    }

    // ---------- calificaciones ----------
    @GetMapping("/secciones/{id}/notas")
    ResponseEntity<?> notas(@PathVariable Long id, HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        var s = gestiona(id, u); if (s == null) return ResponseEntity.notFound().build();
        var cal = cr.findBySeccionId(id);
        var al = ir.findBySeccionIdOrderByNombreAsc(id).stream().map(i -> {
            Double[] v = new Double[4];
            for (var c : cal) if (c.estudiante.equals(i.estudiante) && c.idx >= 0 && c.idx < 4) v[c.idx] = c.valor;
            return Json.m("estudiante", i.estudiante, "nombre", i.nombre, "notas", Arrays.asList(v), "promedio", promedio(v));
        }).toList();
        return ResponseEntity.ok(Json.m("evaluaciones", List.of(Json.m("nombre", EVN[0], "peso", 20), Json.m("nombre", EVN[1], "peso", 30), Json.m("nombre", EVN[2], "peso", 20), Json.m("nombre", EVN[3], "peso", 30)),
            "alumnos", al, "seccion", dto(s)));
    }

    @PutMapping("/secciones/{id}/notas")
    ResponseEntity<?> guardarNotas(@PathVariable Long id, @RequestBody NotasReq b, HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        var s = gestiona(id, u); if (s == null) return ResponseEntity.notFound().build();
        if (b.notas() == null || b.notas().size() > 2000) return err(400, "Solicitud no válida.");
        var roster = ir.findBySeccionIdOrderByNombreAsc(id).stream().map(i -> i.estudiante).collect(Collectors.toSet());
        for (var n : b.notas()) {
            if (n.estudiante() == null || !roster.contains(n.estudiante()) || n.idx() == null || n.idx() < 0 || n.idx() > 3) return err(400, "Estudiante o evaluación no válidos.");
            if (n.valor() != null && (n.valor().isNaN() || n.valor() < 0 || n.valor() > 20)) return err(400, "Las notas deben estar entre 0 y 20.");
        }
        for (var n : b.notas()) {
            var ex = cr.findBySeccionIdAndEstudianteAndIdx(id, n.estudiante(), n.idx());
            if (n.valor() == null) ex.ifPresent(cr::delete);
            else { var c = ex.orElseGet(Calificacion::new); c.seccionId = id; c.estudiante = n.estudiante(); c.idx = n.idx(); c.valor = Math.round(n.valor() * 10) / 10.0; cr.save(c); }
        }
        audit.log(u.username, "Notas registradas «" + s.nombre + "» (" + b.notas().size() + " cambios)" + ("adm".equals(u.role) ? " [admin]" : ""));
        return ResponseEntity.ok(Json.m("ok", true));
    }

    // ---------- asistencia ----------
    private LocalDate fecha(String f) { try { return LocalDate.parse(f); } catch (Exception e) { return null; } }

    @GetMapping("/secciones/{id}/asistencia")
    ResponseEntity<?> asistencia(@PathVariable Long id, @RequestParam String fecha, HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        var s = gestiona(id, u); if (s == null) return ResponseEntity.notFound().build();
        var d = fecha(fecha); if (d == null) return err(400, "Fecha no válida.");
        var m = asr.findBySeccionIdAndFecha(id, d).stream().collect(Collectors.toMap(a -> a.estudiante, a -> a.estado, (x, y) -> y));
        return ResponseEntity.ok(Json.m("fecha", d.toString(), "registrada", !m.isEmpty(), "alumnos",
            ir.findBySeccionIdOrderByNombreAsc(id).stream().map(i -> Json.m("estudiante", i.estudiante, "nombre", i.nombre, "estado", m.get(i.estudiante))).toList()));
    }

    @PutMapping("/secciones/{id}/asistencia")
    ResponseEntity<?> guardarAsistencia(@PathVariable Long id, @RequestBody AsisReq b, HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        var s = gestiona(id, u); if (s == null) return ResponseEntity.notFound().build();
        var d = fecha(b.fecha()); if (d == null || b.registros() == null) return err(400, "Solicitud no válida.");
        if (d.isAfter(hoy())) return err(400, "No se puede registrar asistencia de una fecha futura.");
        if (!"adm".equals(u.role) && d.isBefore(hoy().minusDays(30))) return err(400, "El docente solo puede registrar o corregir hasta 30 días atrás. Solicite apoyo a Administración.");
        var roster = ir.findBySeccionIdOrderByNombreAsc(id).stream().map(i -> i.estudiante).collect(Collectors.toSet());
        for (var r : b.registros()) if (r.estudiante() == null || !roster.contains(r.estudiante()) || r.estado() == null || !List.of("P", "T", "F", "J").contains(r.estado())) return err(400, "Registro no válido.");
        for (var r : b.registros()) {
            var a = asr.findBySeccionIdAndEstudianteAndFecha(id, r.estudiante(), d).orElseGet(Asistencia::new);
            a.seccionId = id; a.estudiante = r.estudiante(); a.fecha = d; a.estado = r.estado(); asr.save(a);
        }
        audit.log(u.username, "Asistencia registrada «" + s.nombre + "» " + d + ("adm".equals(u.role) ? " [admin]" : ""));
        return ResponseEntity.ok(Json.m("ok", true));
    }

    @GetMapping("/secciones/{id}/asistencia/registro")
    ResponseEntity<?> registro(@PathVariable Long id, HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        var s = gestiona(id, u); if (s == null) return ResponseEntity.notFound().build();
        var todas = asr.findBySeccionId(id);
        var fechas = todas.stream().map(a -> a.fecha).distinct().sorted().toList();
        var al = ir.findBySeccionIdOrderByNombreAsc(id).stream().map(i -> {
            var mine = todas.stream().filter(a -> a.estudiante.equals(i.estudiante)).toList();
            var marcas = new LinkedHashMap<String, String>();
            for (var a : mine) marcas.put(a.fecha.toString(), a.estado);
            long p = mine.stream().filter(a -> "P".equals(a.estado)).count(), t = mine.stream().filter(a -> "T".equals(a.estado)).count(),
                 f = mine.stream().filter(a -> "F".equals(a.estado)).count(), j = mine.stream().filter(a -> "J".equals(a.estado)).count();
            return Json.m("estudiante", i.estudiante, "nombre", i.nombre, "marcas", marcas, "p", p, "t", t, "f", f, "j", j, "pct", pct(p, t, f));
        }).toList();
        return ResponseEntity.ok(Json.m("fechas", fechas.stream().map(LocalDate::toString).toList(), "alumnos", al, "seccion", dto(s)));
    }

    // ---------- vistas del estudiante ----------
    @GetMapping("/mis/notas")
    ResponseEntity<?> misNotas(HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!"est".equals(u.role)) return ResponseEntity.status(403).build();
        var out = new ArrayList<Map<String, Object>>();
        for (var i : ir.findByEstudiante(u.username)) {
            var s = sr.findById(i.seccionId).orElse(null); if (s == null) continue;
            Double[] v = new Double[4];
            for (var c : cr.findBySeccionIdAndEstudiante(s.id, u.username)) if (c.idx >= 0 && c.idx < 4) v[c.idx] = c.valor;
            var p = promedio(v);
            out.add(Json.m("seccionId", s.id, "curso", s.nombre, "docente", s.docenteNombre, "notas", Arrays.asList(v), "promedio", p,
                "estado", p == null ? "En curso" : p >= 11 ? "Aprobado" : "Desaprobado"));
        }
        return ResponseEntity.ok(Json.m("evaluaciones", List.of(Json.m("nombre", EVN[0], "peso", 20), Json.m("nombre", EVN[1], "peso", 30), Json.m("nombre", EVN[2], "peso", 20), Json.m("nombre", EVN[3], "peso", 30)), "cursos", out));
    }

    @GetMapping("/mis/asistencia")
    ResponseEntity<?> misAsistencia(HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!"est".equals(u.role)) return ResponseEntity.status(403).build();
        var out = new ArrayList<Map<String, Object>>();
        for (var i : ir.findByEstudiante(u.username)) {
            var s = sr.findById(i.seccionId).orElse(null); if (s == null) continue;
            var l = asr.findBySeccionIdAndEstudiante(s.id, u.username).stream().sorted(Comparator.comparing((Asistencia a) -> a.fecha).reversed()).toList();
            long p = l.stream().filter(a -> "P".equals(a.estado)).count(), t = l.stream().filter(a -> "T".equals(a.estado)).count(),
                 f = l.stream().filter(a -> "F".equals(a.estado)).count(), j = l.stream().filter(a -> "J".equals(a.estado)).count();
            out.add(Json.m("seccionId", s.id, "curso", s.nombre, "p", p, "t", t, "f", f, "j", j, "pct", pct(p, t, f),
                "detalle", l.stream().map(a -> Json.m("fecha", a.fecha.toString(), "estado", a.estado)).toList()));
        }
        return ResponseEntity.ok(out);
    }

    // ---------- justificaciones ----------
    private Map<String, Object> dto(Justificacion j) {
        return Json.m("id", j.id, "seccionId", j.seccionId, "curso", j.curso, "estudiante", j.estudiante, "nombre", j.nombre, "fecha", j.fecha.toString(),
            "motivo", j.motivo, "estado", j.estado, "nota", j.nota, "resueltoPor", j.resueltoPor, "creado", j.creado, "actualizado", j.actualizado,
            "adjunto", j.adjuntoStored == null ? null : j.adjuntoNombre);
    }

    @PostMapping(value = "/justificaciones", consumes = "multipart/form-data")
    ResponseEntity<?> justificar(@RequestParam Long seccionId, @RequestParam String fecha, @RequestParam String motivo,
                                 @RequestParam(value = "file", required = false) MultipartFile file, HttpServletRequest q) throws IOException {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!"est".equals(u.role)) return ResponseEntity.status(403).build();
        if (!rl.allow("just:" + u.username, 10, 3_600_000)) return err(429, "Demasiadas solicitudes. Intente más tarde.");
        var s = ve(seccionId, u); if (s == null) return err(400, "Curso no válido.");
        var d = fecha(fecha);
        if (d == null || d.isAfter(hoy()) || d.isBefore(hoy().minusDays(15))) return err(400, "La fecha debe estar dentro de los últimos 15 días.");
        motivo = motivo == null ? "" : motivo.trim();
        if (motivo.length() < 10 || motivo.length() > 600) return err(400, "Explique el motivo (entre 10 y 600 caracteres).");
        if (jr.existsBySeccionIdAndEstudianteAndFechaAndEstadoIn(seccionId, u.username, d, List.of("Pendiente", "Aprobada"))) return err(409, "Ya existe una justificación para ese curso y fecha.");
        var j = new Justificacion(); j.seccionId = seccionId; j.estudiante = u.username; j.nombre = u.name; j.curso = s.nombre; j.fecha = d; j.motivo = motivo;
        if (file != null && !file.isEmpty()) {
            if (file.getSize() > 5L * 1024 * 1024) return err(400, "El sustento debe pesar como máximo 5 MB.");
            byte[] datos = file.getBytes(); String ext = Archivos.tipo(datos);
            if (ext == null) return err(400, "Formato no permitido: solo PDF, JPG o PNG.");
            j.adjuntoStored = Archivos.guardar(datos, ext); j.adjuntoNombre = Archivos.limpiarNombre(file.getOriginalFilename()); j.adjuntoMime = Archivos.mime(ext);
        }
        j = jr.save(j);
        audit.log(u.username, "Justificación registrada " + s.nombre + " " + d);
        return ResponseEntity.status(201).body(dto(j));
    }

    @GetMapping("/justificaciones")
    ResponseEntity<?> justificaciones(HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        List<Justificacion> l;
        switch (u.role) {
            case "est" -> l = jr.findByEstudianteOrderByIdDesc(u.username);
            case "doc" -> l = jr.findBySeccionIdInOrderByIdDesc(sr.findByDocente(u.username).stream().map(s -> s.id).toList());
            case "adm" -> l = jr.findAllByOrderByIdDesc();
            default -> { return ResponseEntity.status(403).build(); }
        }
        return ResponseEntity.ok(l.stream().limit(500).map(this::dto).toList());
    }

    private Justificacion accesoJust(Long id, AppUser u) {
        var j = jr.findById(id).orElse(null); if (j == null) return null;
        return j.estudiante.equals(u.username) || gestiona(j.seccionId, u) != null ? j : null;
    }

    @PutMapping("/justificaciones/{id}")
    ResponseEntity<?> resolver(@PathVariable Long id, @RequestBody Resolucion b, HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        var j = jr.findById(id).orElse(null);
        if (j == null || gestiona(j.seccionId, u) == null) return ResponseEntity.notFound().build();
        if (!"Pendiente".equals(j.estado) || b.estado() == null || !List.of("Aprobada", "Rechazada").contains(b.estado())) return err(400, "Operación no válida.");
        String nota = b.nota() == null ? "" : b.nota().trim();
        if ("Rechazada".equals(b.estado()) && nota.length() < 5) return err(400, "Indique el motivo del rechazo.");
        j.estado = b.estado(); j.nota = nota.isEmpty() ? null : nota.substring(0, Math.min(300, nota.length())); j.resueltoPor = u.username; j.actualizado = Instant.now();
        jr.save(j);
        if ("Aprobada".equals(j.estado)) {
            var a = asr.findBySeccionIdAndEstudianteAndFecha(j.seccionId, j.estudiante, j.fecha).orElseGet(Asistencia::new);
            a.seccionId = j.seccionId; a.estudiante = j.estudiante; a.fecha = j.fecha; a.estado = "J"; asr.save(a);
        }
        audit.log(u.username, "Justificación " + j.estado.toLowerCase() + " " + j.estudiante + " " + j.curso + " " + j.fecha);
        return ResponseEntity.ok(dto(j));
    }

    @GetMapping("/justificaciones/{id}/adjunto")
    ResponseEntity<byte[]> adjuntoJust(@PathVariable Long id, HttpServletRequest q) throws IOException {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        var j = accesoJust(id, u); if (j == null || j.adjuntoStored == null) return ResponseEntity.notFound().build();
        byte[] d = Archivos.leer(j.adjuntoStored); if (d == null) return ResponseEntity.notFound().build();
        if (!j.estudiante.equals(u.username)) audit.log(u.username, "Descarga de sustento justificación " + j.id);
        return descarga(d, j.adjuntoMime, j.adjuntoNombre);
    }

    private ResponseEntity<byte[]> descarga(byte[] d, String mime, String nombre) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nombre, StandardCharsets.UTF_8).build().toString()).body(d);
    }

    // ---------- materiales ----------
    private Map<String, Object> dto(Material m) {
        return Json.m("id", m.id, "titulo", m.titulo, "descripcion", m.descripcion, "nombre", m.original, "mime", m.mime, "tam", m.tam, "fecha", m.fecha, "por", m.por);
    }

    @GetMapping("/secciones/{id}/materiales")
    ResponseEntity<?> materiales(@PathVariable Long id, HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (ve(id, u) == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(mr.findBySeccionIdOrderByIdDesc(id).stream().map(this::dto).toList());
    }

    @PostMapping(value = "/secciones/{id}/materiales", consumes = "multipart/form-data")
    ResponseEntity<?> subirMaterial(@PathVariable Long id, @RequestParam String titulo, @RequestParam(required = false) String descripcion,
                                    @RequestParam("file") MultipartFile file, HttpServletRequest q) throws IOException {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        var s = gestiona(id, u); if (s == null) return ResponseEntity.notFound().build();
        titulo = titulo == null ? "" : titulo.trim();
        if (titulo.length() < 3 || titulo.length() > 120) return err(400, "El título debe tener entre 3 y 120 caracteres.");
        if (file == null || file.isEmpty()) return err(400, "Seleccione un archivo.");
        if (file.getSize() > MAX_ARCHIVO) return err(400, "El archivo debe pesar como máximo 10 MB.");
        byte[] d = file.getBytes(); String ext = Archivos.tipoMaterial(d, file.getOriginalFilename());
        if (ext == null) return err(400, "Formato no permitido: PDF, JPG, PNG, Word, PowerPoint o Excel.");
        var m = new Material(); m.seccionId = id; m.titulo = titulo; m.descripcion = descripcion == null || descripcion.isBlank() ? null : descripcion.trim().substring(0, Math.min(500, descripcion.trim().length()));
        m.stored = Archivos.guardar(d, ext); m.original = Archivos.limpiarNombre(file.getOriginalFilename()); m.mime = Archivos.mime(ext); m.tam = d.length; m.sha256 = Seguridad.sha256(d); m.por = u.username;
        m = mr.save(m);
        audit.log(u.username, "Material publicado «" + s.nombre + "»: " + titulo);
        return ResponseEntity.status(201).body(dto(m));
    }

    @GetMapping("/materiales/{mid}/archivo")
    ResponseEntity<byte[]> bajarMaterial(@PathVariable Long mid, HttpServletRequest q) throws IOException {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        var m = mr.findById(mid).orElse(null); if (m == null || ve(m.seccionId, u) == null) return ResponseEntity.notFound().build();
        byte[] d = Archivos.leer(m.stored); if (d == null) return ResponseEntity.notFound().build();
        return descarga(d, m.mime, m.original);
    }

    @DeleteMapping("/materiales/{mid}")
    ResponseEntity<?> borrarMaterial(@PathVariable Long mid, HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        var m = mr.findById(mid).orElse(null); if (m == null || gestiona(m.seccionId, u) == null) return ResponseEntity.notFound().build();
        mr.delete(m); audit.log(u.username, "Material eliminado: " + m.titulo);
        return ResponseEntity.ok().build();
    }

    // ---------- resumen para Administración ----------
    @GetMapping("/resumen")
    ResponseEntity<?> resumen(HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!"adm".equals(u.role)) return ResponseEntity.status(403).build();
        var alumnos = ir.findAll().stream().map(i -> i.estudiante).distinct().count();
        var todas = asr.findAll();
        long p = todas.stream().filter(a -> "P".equals(a.estado)).count(), t = todas.stream().filter(a -> "T".equals(a.estado)).count(), f = todas.stream().filter(a -> "F".equals(a.estado)).count();
        var proms = new ArrayList<Double>();
        for (var s : sr.findAll()) for (var i : ir.findBySeccionIdOrderByNombreAsc(s.id)) {
            Double[] v = new Double[4];
            for (var c : cr.findBySeccionIdAndEstudiante(s.id, i.estudiante)) if (c.idx >= 0 && c.idx < 4) v[c.idx] = c.valor;
            var pr = promedio(v); if (pr != null) proms.add(pr);
        }
        return ResponseEntity.ok(Json.m("alumnos", alumnos, "secciones", sr.count(), "asistencia", pct(p, t, f),
            "promedio", proms.isEmpty() ? null : Math.round(proms.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0,
            "justPendientes", jr.countByEstado("Pendiente")));
    }
}
