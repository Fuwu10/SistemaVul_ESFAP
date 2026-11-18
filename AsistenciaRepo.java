package pe.edu.esfap.portal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AsistenciaRepo extends JpaRepository<Asistencia, Long> {
    List<Asistencia> findBySeccionId(Long seccionId);
    List<Asistencia> findBySeccionIdAndFecha(Long seccionId, LocalDate fecha);
    List<Asistencia> findBySeccionIdAndEstudiante(Long seccionId, String estudiante);
    Optional<Asistencia> findBySeccionIdAndEstudianteAndFecha(Long seccionId, String estudiante, LocalDate fecha);
}
