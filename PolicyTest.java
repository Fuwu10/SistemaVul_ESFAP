package pe.edu.esfap.portal;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PolicyTest {
    @Test void bloqueoTrasTresIntentos() { assertFalse(Policy.locked(2)); assertTrue(Policy.locked(3)); }
    @Test void contrasenaFuerte() {
        assertTrue(Policy.strongPassword("Portal2026Seg")); assertFalse(Policy.strongPassword("Estud2026")); // 9 caracteres
        assertFalse(Policy.strongPassword("corta1A")); assertFalse(Policy.strongPassword("sololetrasAAAA"));
        assertFalse(Policy.strongPassword("todominusculas123")); assertFalse(Policy.strongPassword(null));
        assertFalse(Policy.strongPassword("Secretaria2026x", "secretaria"));
    }
    @Test void roles() { assertTrue(Policy.canViewLogs("ti")); assertFalse(Policy.canViewLogs("est")); assertTrue(Policy.validRole("doc")); assertFalse(Policy.validRole("root")); assertFalse(Policy.canManageUsers("doc")); }
    @Test void nombresDeUsuario() { assertTrue(Policy.validUsername("E2024001")); assertFalse(Policy.validUsername("a'b")); assertFalse(Policy.validUsername("ab")); }
    @Test void sinEscaladaDePrivilegios() { assertTrue(Policy.canAssignRole("ti", "adm")); assertTrue(Policy.canAssignRole("adm", "est")); assertFalse(Policy.canAssignRole("adm", "ti")); assertFalse(Policy.canAssignRole("adm", "adm")); }
    @Test void nivelesDeSeguridad() {
        assertTrue(Policy.canAccess("est", Policy.N_INTERNO)); assertFalse(Policy.canAccess("est", Policy.N_CONFIDENCIAL));
        assertTrue(Policy.canAccess("adm", Policy.N_CONFIDENCIAL)); assertFalse(Policy.canAccess("adm", Policy.N_CRITICO)); assertTrue(Policy.canAccess("ti", Policy.N_CRITICO));
    }
    @Test void expedientesSoloTitularOAdministracion() {
        assertTrue(Policy.canReadExpediente("est", "E1", "E1")); assertFalse(Policy.canReadExpediente("est", "E1", "E2"));
        assertTrue(Policy.canReadExpediente("adm", "E1", "secretaria")); assertFalse(Policy.canReadExpediente("ti", "E1", "soporte"));
    }
    @Test void flujoDeEstados() {
        assertTrue(Policy.transicionValida("Recibido", "En revisión")); assertTrue(Policy.transicionValida("Aprobado", "Emitido"));
        assertFalse(Policy.transicionValida("Recibido", "Emitido")); assertFalse(Policy.transicionValida("Emitido", "Recibido")); assertFalse(Policy.transicionValida("Observado", "Aprobado"));
    }
    @Test void archivosPorContenidoNoPorExtension() {
        assertEquals("pdf", Archivos.tipo("%PDF-1.7".getBytes())); assertEquals("png", Archivos.tipo(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13}));
        assertEquals("jpg", Archivos.tipo(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0}));
        assertNull(Archivos.tipo("<script>alert(1)</script>".getBytes())); assertNull(Archivos.tipo(new byte[]{'M', 'Z', 0, 0, 0}));
        assertEquals("evil.pdf", Archivos.limpiarNombre("..\\..\\x/evil.pdf"));
    }
}
