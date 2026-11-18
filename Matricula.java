package pe.edu.esfap.portal;

import jakarta.persistence.*;
import java.time.Instant;

/** Solicitud de matrícula virtual de un periodo. Estados: Pendiente, Aprobada, Rechazada. */
@Entity
public class Matricula {
    @Id @GeneratedValue public Long id;
    public String codigo, estudiante, nombre, periodo, estado = "Pendiente", revisadoPor;
    @Column(length = 500) public String cursos, nota;   // cursos: ids separados por coma
    public Instant creado = Instant.now(), actualizado = Instant.now();
}
