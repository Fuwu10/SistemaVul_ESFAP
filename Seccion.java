package pe.edu.esfap.portal;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Sección de un curso en un periodo: docente, aula y horario semanal (dia 1=lunes ... 7=domingo). */
@Entity
public class Seccion {
    @Id @GeneratedValue public Long id;
    public String cursoId, nombre, docente, docenteNombre, aula, horaIni, horaFin, periodo;
    public int dia;
}
