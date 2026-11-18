package pe.edu.esfap.portal;

import java.security.SecureRandom;
import java.util.Map;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Primera ejecución: crea 4 usuarios con contraseñas ALEATORIAS (se muestran una sola vez en la consola) y
 * obliga a cambiarlas al ingresar. Si la base ya existía con las claves de demostración antiguas, las marca para cambio.
 */
@Component @org.springframework.core.annotation.Order(1)
public class DataSeeder implements CommandLineRunner {
    private static final Map<String, String> ANTIGUAS = Map.of(
        "E2024001", "Estud2026", "45678912", "Docen2026", "secretaria", "Admin2026", "soporte", "Soporte2026");
    private final UserRepo users;
    DataSeeder(UserRepo u) { users = u; }

    static String generar() {
        var rnd = new SecureRandom();
        String a = "ABCDEFGHJKLMNPQRSTUVWXYZ", b = "abcdefghijkmnpqrstuvwxyz", d = "23456789";
        String all = a + b + d;
        var sb = new StringBuilder();
        sb.append(a.charAt(rnd.nextInt(a.length()))).append(b.charAt(rnd.nextInt(b.length()))).append(d.charAt(rnd.nextInt(d.length())));
        while (sb.length() < 14) sb.append(all.charAt(rnd.nextInt(all.length())));
        return sb.toString();
    }

    public void run(String... args) {
        var enc = new BCryptPasswordEncoder(12);
        if (users.count() == 0) {
            String[][] d = {{"E2024001", "Lucía Quispe Huamán", "est"}, {"45678912", "Prof. Carlos Ccente", "doc"},
                {"secretaria", "Rosa Palomino (Administración)", "adm"}, {"soporte", "Soporte TI", "ti"}};
            var out = new StringBuilder("\n===== CREDENCIALES INICIALES (se muestran UNA sola vez; deberá cambiarlas al ingresar) =====\n");
            for (var r : d) {
                String pw = generar();
                var u = new AppUser(); u.username = r[0]; u.name = r[1]; u.role = r[2]; u.passwordHash = enc.encode(pw); u.mustChange = true;
                users.save(u);
                out.append(String.format("  %-12s %-4s %s%n", r[0], r[2], pw));
            }
            System.out.println(out.append("=================================================================================\n"));
            return;
        }
        for (var u : users.findAll()) {
            String old = ANTIGUAS.get(u.username);
            if (old != null && !Boolean.TRUE.equals(u.mustChange) && enc.matches(old, u.passwordHash)) {
                u.mustChange = true; users.save(u);
                System.out.println("[SEGURIDAD] El usuario '" + u.username + "' aún usa la clave de demostración: se le exigirá cambiarla.");
            }
        }
    }
}
