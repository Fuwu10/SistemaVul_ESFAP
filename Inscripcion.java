package pe.edu.esfap.portal;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Estudiante inscrito en una sección (se crea al aprobar la matrícula). */
@Entity
public class Inscripcion {
    @Id @GeneratedValue public Long id;
    public Long seccionId;
    public String estudiante, nombre;
}
