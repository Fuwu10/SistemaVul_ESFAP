package pe.edu.esfap.portal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CalificacionRepo extends JpaRepository<Calificacion, Long> {
    List<Calificacion> findBySeccionId(Long seccionId);
    List<Calificacion> findBySeccionIdAndEstudiante(Long seccionId, String estudiante);
    Optional<Calificacion> findBySeccionIdAndEstudianteAndIdx(Long seccionId, String estudiante, int idx);
}
