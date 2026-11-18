package pe.edu.esfap.portal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MatriculaRepo extends JpaRepository<Matricula, Long> {
    Optional<Matricula> findFirstByEstudianteAndPeriodoOrderByIdDesc(String estudiante, String periodo);
    List<Matricula> findAllByOrderByIdDesc();
    long countByEstado(String estado);
}
