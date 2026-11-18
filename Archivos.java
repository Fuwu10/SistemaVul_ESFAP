package pe.edu.esfap.portal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Validación de archivos subidos: se confía en los bytes (firma real), nunca en la extensión ni en el Content-Type. */
public final class Archivos {
    private Archivos() {}

    /** @return "pdf", "png", "jpg" o null si el contenido no es uno de los formatos permitidos. */
    public static String tipo(byte[] b) {
        if (b == null || b.length < 5) return null;
        if (b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return "pdf";
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpg";
        return null;
    }

    /** Material de clase: además de PDF/JPG/PNG admite Word, PowerPoint y Excel (contenedor ZIP «PK» + extensión). */
    public static String tipoMaterial(byte[] b, String nombre) {
        String t = tipo(b);
        if (t != null) return t;
        if (b != null && b.length > 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4 && nombre != null) {
            String n = nombre.toLowerCase();
            for (String e : new String[]{"docx", "pptx", "xlsx"}) if (n.endsWith("." + e)) return e;
        }
        return null;
    }

    public static String mime(String ext) {
        return switch (ext) {
            case "pdf" -> "application/pdf"; case "png" -> "image/png";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> "image/jpeg";
        };
    }

    public static final Path DIR = Path.of("data", "uploads").toAbsolutePath().normalize();

    /** Guarda con nombre UUID fuera de la carpeta pública y devuelve el nombre guardado. */
    public static String guardar(byte[] datos, String ext) throws IOException {
        Files.createDirectories(DIR);
        String stored = UUID.randomUUID() + "." + ext;
        Files.write(DIR.resolve(stored), datos);
        return stored;
    }

    /** Lee un archivo guardado; null si no existe o la ruta se sale del directorio. */
    public static byte[] leer(String stored) throws IOException {
        if (stored == null) return null;
        Path p = DIR.resolve(stored).normalize();
        return p.startsWith(DIR) && Files.exists(p) ? Files.readAllBytes(p) : null;
    }

    /** Nombre solo para mostrar (el archivo se guarda con un UUID): sin rutas ni caracteres raros. */
    public static String limpiarNombre(String n) {
        if (n == null || n.isBlank()) return "documento";
        n = n.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[^\\p{L}\\p{N} ._-]", "_").trim();
        if (n.length() > 80) n = n.substring(n.length() - 80);
        return n.isEmpty() ? "documento" : n;
    }
}
