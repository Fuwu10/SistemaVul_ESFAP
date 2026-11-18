package pe.edu.esfap.portal;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Cuota o pago del cronograma de un estudiante (numero 0 = ratificación de matrícula). */
@Entity
public class Cuota {
    @Id @GeneratedValue public Long id;
    public String estudiante, nombre, periodo, concepto, estado = "Pendiente", medio, recibo, registradoPor;
    public int numero;
    public LocalDate vencimiento, fechaPago;
    public BigDecimal monto = BigDecimal.ZERO, mora = BigDecimal.ZERO;
    public boolean conciliado;
}
