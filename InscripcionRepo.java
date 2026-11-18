package pe.edu.esfap.portal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface InscripcionRepo extends JpaRepository<Inscripcion, Long> {
    List<Inscripcion> findBySeccionIdOrderByNombreAsc(Long seccionId);
    List<Inscripcion> findByEstudiante(String estudiante);
    long countBySeccionId(Long seccionId);
    boolean existsBySeccionIdAndEstudiante(Long seccionId, String estudiante);
}
