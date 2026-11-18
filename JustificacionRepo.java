package pe.edu.esfap.portal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface JustificacionRepo extends JpaRepository<Justificacion, Long> {
    List<Justificacion> findByEstudianteOrderByIdDesc(String estudiante);
    List<Justificacion> findBySeccionIdInOrderByIdDesc(Collection<Long> ids);
    List<Justificacion> findAllByOrderByIdDesc();
    boolean existsBySeccionIdAndEstudianteAndFechaAndEstadoIn(Long seccionId, String estudiante, LocalDate fecha, Collection<String> estados);
    long countByEstado(String estado);
}
