package pe.edu.esfap.portal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MaterialRepo extends JpaRepository<Material, Long> {
    List<Material> findBySeccionIdOrderByIdDesc(Long seccionId);
}
