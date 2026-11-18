package pe.edu.esfap.portal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Hash SHA-256 y sello HMAC-SHA256 del cargo digital. La clave se genera una vez en ./data/secret.key. */
@Component
public class Seguridad {
    private final byte[] key;

    public Seguridad() throws IOException {
        Path p = Path.of("data", "secret.key");
        Files.createDirectories(p.toAbsolutePath().getParent());
        if (Files.exists(p)) key = Files.readAllBytes(p);
        else {
            key = new byte[32];
            new SecureRandom().nextBytes(key);
            Files.write(p, key);
            try { Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------")); } catch (Exception ignore) { /* Windows */ }
        }
    }

    public static String hex(byte[] b) {
        var sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public static String sha256(byte[] data) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public String hmac(String data) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Sello del cargo: enlaza código, solicitante, fecha y huella de cada documento recibido. */
    public String selloCargo(Tramite t, List<Adjunto> iniciales) {
        var sb = new StringBuilder(t.codigo).append('|').append(t.estudiante).append('|').append(t.creado);
        for (var a : iniciales) sb.append('|').append(a.sha256);
        return hmac(sb.toString());
    }
}
