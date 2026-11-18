package pe.edu.esfap.portal;

import java.util.List;
import java.util.Set;

/** Oferta académica del periodo y requisitos. Demostración: cursos aprobados fijos (enlazar con el módulo de notas). */
final class Catalogo {
    record Curso(String id, String nombre, String pre) {}
    static final String PERIODO = "2026-II";
    static final List<Curso> CURSOS = List.of(
        new Curso("DIB1", "Dibujo I", null), new Curso("DIB2", "Dibujo II", "DIB1"),
        new Curso("PIN1", "Pintura I", null), new Curso("GRA1", "Grabado I", null), new Curso("ESC1", "Escultura I", null));
    private Catalogo() {}
    static Curso buscar(String id) { return CURSOS.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null); }
    static Set<String> aprobados(String estudiante) { return Set.of("DIB1"); }
}
