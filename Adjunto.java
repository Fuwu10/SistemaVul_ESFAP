package pe.edu.esfap.portal;

import jakarta.persistence.*;
import java.time.Instant;

/** Documento adjunto. Se guarda en ./data/uploads con nombre UUID (fuera de la carpeta pública). */
@Entity
public class Adjunto {
    @Id @GeneratedValue public Long id;
    public Long tramiteId;
    public String original, stored, mime, por;
    @Column(length = 64) public String sha256;
    public long tam;
    public boolean inicial;
    public Instant fecha = Instant.now();
}
