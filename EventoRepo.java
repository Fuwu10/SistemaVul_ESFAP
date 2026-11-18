package pe.edu.esfap.portal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface EventoRepo extends JpaRepository<Evento, Long> {
    List<Evento> findByTramiteIdOrderByIdAsc(Long tramiteId);
}
