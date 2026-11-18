package pe.edu.esfap.portal;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Resuelve el usuario de la sesión. Mientras deba cambiar su contraseña inicial, solo puede usar /me, /password y /logout. */
@Component
public class Auth {
    private final UserRepo users;
    public Auth(UserRepo u) { users = u; }

    public AppUser current(HttpServletRequest q) { return current(q, false); }

    public AppUser current(HttpServletRequest q, boolean allowMustChange) {
        var s = q.getSession(false);
        if (s == null || s.getAttribute("u") == null) return null;
        var u = users.findById((String) s.getAttribute("u")).filter(x -> x.active).orElse(null);
        if (u == null) return null;
        if (!allowMustChange && Boolean.TRUE.equals(u.mustChange)) return null;
        return u;
    }
}
