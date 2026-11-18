package pe.edu.esfap.portal;

import jakarta.persistence.*;
import java.time.Instant;

/** Registro de auditoría de seguridad, encadenado por hash (prev -> hash). */
@Entity
public class LogEntry {
    @Id @GeneratedValue public Long id;
    public Instant at = Instant.now();
    public String actor, action;
    @Column(length = 64) public String prev;
    @Column(length = 64) public String hash;
}
