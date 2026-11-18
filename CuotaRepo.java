package pe.edu.esfap.portal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CuotaRepo extends JpaRepository<Cuota, Long> {
    List<Cuota> findByEstudianteOrderByVencimientoAsc(String estudiante);
    List<Cuota> findAllByOrderByVencimientoAsc();
    boolean existsByEstudianteAndPeriodo(String estudiante, String periodo);
}
