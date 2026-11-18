package pe.edu.esfap.portal;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AcademicoTest {
    @Test void promedioPonderado() {
        // 20 % + 30 % + 20 % + 30 %
        assertEquals(12.7, AcademicoController.promedio(new Double[]{14.0, 15.0, 12.0, 10.0}));
        assertNull(AcademicoController.promedio(new Double[]{14.0, 15.0, null, 10.0}));
    }
    @Test void asistenciaExcluyeJustificadas() {
        assertEquals(90.0, AcademicoController.pct(8, 1, 1));   // 9 de 10 clases con asistencia
        assertNull(AcademicoController.pct(0, 0, 0));
    }
    @Test void moraPorDiaDeAtraso() {
        var c = new Cuota(); c.monto = new BigDecimal("330.00"); c.vencimiento = LocalDate.of(2026, 8, 28);
        assertEquals(new BigDecimal("0.18"), PagosController.mora(c, LocalDate.of(2026, 8, 30)));   // 2 días
        assertEquals(new BigDecimal("0.27"), PagosController.mora(c, LocalDate.of(2026, 8, 31)));   // 3 días
        assertEquals(BigDecimal.ZERO, PagosController.mora(c, LocalDate.of(2026, 8, 28)));          // sin atraso
    }
    @Test void justificacionesYPagosPorRol() {
        assertTrue(Policy.canManageAcademico("doc")); assertFalse(Policy.canManageAcademico("est"));
        assertTrue(Policy.canPay("est")); assertFalse(Policy.canPay("adm")); assertTrue(Policy.canManagePagos("adm")); assertFalse(Policy.canManagePagos("doc"));
    }
    @Test void materialesAdmitenOffice() {
        assertEquals("docx", Archivos.tipoMaterial(new byte[]{'P', 'K', 3, 4, 0}, "Clase 1.DOCX"));
        assertNull(Archivos.tipoMaterial(new byte[]{'P', 'K', 3, 4, 0}, "virus.exe"));
    }
}
