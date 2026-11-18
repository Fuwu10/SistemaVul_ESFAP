package pe.edu.esfap.portal;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Archivo de clase publicado por el docente. */
@Entity
public class Material {
    @Id @GeneratedValue public Long id;
    public Long seccionId;
    public String titulo, stored, original, mime, por;
    @Column(length = 500) public String descripcion;
    @Column(length = 64) public String sha256;
    public long tam;
    public Instant fecha = Instant.now();
}
