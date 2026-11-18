package pe.edu.esfap.portal;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Marca de asistencia por fecha: P presente, T tardanza, F falta, J justificada. */
@Entity
public class Asistencia {
    @Id @GeneratedValue public Long id;
    public Long seccionId;
    public String estudiante, estado;
    public LocalDate fecha;
}
