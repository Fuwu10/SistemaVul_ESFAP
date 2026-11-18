package pe.edu.esfap.portal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AdjuntoRepo extends JpaRepository<Adjunto, Long> {
    List<Adjunto> findByTramiteIdOrderByIdAsc(Long tramiteId);
}
