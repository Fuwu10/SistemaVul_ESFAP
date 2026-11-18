package pe.edu.esfap.portal;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** RF-01 Matrícula virtual: el estudiante solicita, Administración aprueba o rechaza. Nivel de seguridad N3 para la gestión. */
@RestController @RequestMapping("/api/matriculas")
public class MatriculaController {
    record Solicitud(List<String> cursos) {}
    record Resolucion(String estado, String nota) {}
    private final MatriculaRepo repo; private final Auth auth; private final Audit audit;
    private final SeccionRepo secciones; private final InscripcionRepo inscripciones;
    public MatriculaController(MatriculaRepo r, Auth a, Audit au, SeccionRepo s, InscripcionRepo i) { repo = r; auth = a; audit = au; secciones = s; inscripciones = i; }

    private ResponseEntity<Map<String, Object>> err(int s, String m) { return ResponseEntity.status(s).body(Json.m("error", m)); }

    private List<String> nombres(String csv) {
        return Arrays.stream(csv.split(",")).map(id -> { var c = Catalogo.buscar(id); return c == null ? id : c.nombre(); }).toList();
    }
    private Map<String, Object> dto(Matricula m) {
        return Json.m("id", m.id, "codigo", m.codigo, "estudiante", m.estudiante, "nombre", m.nombre, "periodo", m.periodo,
            "cursos", nombres(m.cursos), "estado", m.estado, "nota", m.nota, "creado", m.creado, "actualizado", m.actualizado);
    }

    @GetMapping("/catalogo")
    ResponseEntity<?> catalogo(HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        var ap = Catalogo.aprobados(u.username);
        return ResponseEntity.ok(Json.m("periodo", Catalogo.PERIODO, "cursos",
            Catalogo.CURSOS.stream().map(c -> Json.m("id", c.id(), "nombre", c.nombre(), "pre", c.pre(), "aprobado", ap.contains(c.id()))).toList()));
    }

    @GetMapping("/mia")
    ResponseEntity<?> mia(HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!Policy.canSubmitMatricula(u.role)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(repo.findFirstByEstudianteAndPeriodoOrderByIdDesc(u.username, Catalogo.PERIODO).map(this::dto).orElse(Json.m("estado", null)));
    }

    @PostMapping
    ResponseEntity<?> solicitar(@RequestBody Solicitud b, HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!Policy.canSubmitMatricula(u.role)) return ResponseEntity.status(403).build();
        var ids = b.cursos() == null ? List.<String>of() : b.cursos().stream().distinct().toList();
        if (ids.isEmpty() || ids.size() > 6) return err(400, "Seleccione entre 1 y 6 cursos.");
        var ap = Catalogo.aprobados(u.username);
        for (var id : ids) {
            var c = Catalogo.buscar(id);
            if (c == null) return err(400, "Curso no válido.");
            if (ap.contains(id)) return err(400, c.nombre() + " ya fue aprobado.");
            if (c.pre() != null && !ap.contains(c.pre()) && !ids.contains(c.pre())) return err(400, c.nombre() + " requiere aprobar " + Catalogo.buscar(c.pre()).nombre() + ".");
        }
        var previa = repo.findFirstByEstudianteAndPeriodoOrderByIdDesc(u.username, Catalogo.PERIODO).orElse(null);
        if (previa != null && !"Rechazada".equals(previa.estado)) return err(409, "Ya tiene una matrícula registrada en este periodo.");
        var m = new Matricula(); m.estudiante = u.username; m.nombre = u.name; m.periodo = Catalogo.PERIODO; m.cursos = String.join(",", ids);
        m = repo.save(m); m.codigo = "MAT-" + Catalogo.PERIODO + "-" + String.format("%05d", m.id); m = repo.save(m);
        audit.log(u.username, "Matrícula solicitada " + m.codigo);
        return ResponseEntity.status(201).body(dto(m));
    }

    @GetMapping
    ResponseEntity<?> listar(HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!Policy.canReviewMatricula(u.role)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(repo.findAllByOrderByIdDesc().stream().limit(500).map(this::dto).toList());
    }

    @PutMapping("/{id}/estado")
    ResponseEntity<?> resolver(@PathVariable Long id, @RequestBody Resolucion b, HttpServletRequest q) {
        var u = auth.current(q); if (u == null) return ResponseEntity.status(401).build();
        if (!Policy.canReviewMatricula(u.role)) return ResponseEntity.status(403).build();
        var m = repo.findById(id).orElse(null); if (m == null) return ResponseEntity.notFound().build();
        if (!"Pendiente".equals(m.estado) || b.estado() == null || !List.of("Aprobada", "Rechazada").contains(b.estado())) return err(400, "Operación no válida.");
        String nota = b.nota() == null ? "" : b.nota().trim();
        if ("Rechazada".equals(b.estado()) && nota.length() < 5) return err(400, "Indique el motivo del rechazo.");
        m.estado = b.estado(); m.nota = nota.isEmpty() ? null : nota.substring(0, Math.min(300, nota.length())); m.revisadoPor = u.username; m.actualizado = Instant.now();
        repo.save(m);
        if ("Aprobada".equals(m.estado))   // al aprobar, el estudiante queda inscrito en la primera sección de cada curso
            for (var cid : m.cursos.split(",")) secciones.findAll().stream().filter(s -> cid.equals(s.cursoId)).findFirst().ifPresent(s -> {
                if (!inscripciones.existsBySeccionIdAndEstudiante(s.id, m.estudiante)) { var i = new Inscripcion(); i.seccionId = s.id; i.estudiante = m.estudiante; i.nombre = m.nombre; inscripciones.save(i); } });
        audit.log(u.username, "Matrícula " + m.estado.toLowerCase() + " " + m.codigo);
        return ResponseEntity.ok(dto(m));
    }
}
