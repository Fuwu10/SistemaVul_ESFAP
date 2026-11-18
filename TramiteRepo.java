package pe.edu.esfap.portal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface TramiteRepo extends JpaRepository<Tramite, Long> {
    List<Tramite> findByEstudianteOrderByIdDesc(String estudiante);
    List<Tramite> findAllByOrderByIdDesc();
    Optional<Tramite> findByCodigo(String codigo);
    Optional<Tramite> findByCodigoVerificacion(String codigo);
    long countByEstadoIn(List<String> estados);
}
