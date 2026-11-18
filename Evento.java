package pe.edu.esfap.portal;

import jakarta.persistence.*;
import java.time.Instant;

/** Historial de un trámite (seguimiento). */
@Entity
public class Evento {
    @Id @GeneratedValue public Long id;
    public Long tramiteId;
    public Instant fecha = Instant.now();
    public String actor, estado;
    @Column(length = 600) public String nota;
}
