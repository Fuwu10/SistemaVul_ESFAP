package pe.edu.esfap.portal;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Justificación de inasistencia (N3: puede incluir sustento médico). Estados: Pendiente, Aprobada, Rechazada. */
@Entity
public class Justificacion {
    @Id @GeneratedValue public Long id;
    public Long seccionId;
    public String estudiante, nombre, curso, estado = "Pendiente", resueltoPor, adjuntoStored, adjuntoNombre, adjuntoMime;
    public LocalDate fecha;
    @Column(length = 600) public String motivo, nota;
    public Instant creado = Instant.now(), actualizado = Instant.now();
}
