package pe.edu.esfap.portal;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Expediente de mesa de partes. Estados: Recibido, En revisión, Observado, Aprobado, Rechazado, Emitido. */
@Entity
public class Tramite {
    @Id @GeneratedValue public Long id;
    public String codigo, estudiante, nombre, tipo, asunto, estado = "Recibido", codigoVerificacion;
    @Column(length = 1500) public String descripcion;
    @Column(length = 64) public String cargoSello;
    public int nivel = Policy.N_CONFIDENCIAL;
    public String area;
    public Instant recepcion, finalizado;
    public Instant creado = Instant.now().truncatedTo(ChronoUnit.MILLIS), actualizado = Instant.now();
}
