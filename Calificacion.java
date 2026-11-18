package pe.edu.esfap.portal;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Nota (0-20) de una evaluación (idx 0..3) de un estudiante en una sección. */
@Entity
public class Calificacion {
    @Id @GeneratedValue public Long id;
    public Long seccionId;
    public String estudiante;
    public int idx;
    public double valor;
}
