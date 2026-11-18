package pe.edu.esfap.portal;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Anti-CSRF con token por sesión (cabecera X-CSRF-Token, además de X-Requested-With) y cabeceras de seguridad HTTP.
 * El login queda exento del token (aún no hay sesión) pero exige X-Requested-With; la cookie es SameSite=Strict.
 */
@Component
public class CsrfGuard extends OncePerRequestFilter {
    private static final String CSP = "default-src 'self'; script-src 'self' 'unsafe-inline'; "
        + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' data: https://fonts.gstatic.com; "
        + "img-src 'self' data: blob:; frame-src blob:; connect-src 'self'; object-src 'none'; base-uri 'self'; "
        + "form-action 'self'; frame-ancestors 'none'";

    @Override
    protected void doFilterInternal(HttpServletRequest q, HttpServletResponse r, FilterChain c) throws ServletException, IOException {
        String uri = q.getRequestURI();
        boolean api = uri.startsWith("/api/");
        boolean mutates = !List.of("GET", "HEAD", "OPTIONS").contains(q.getMethod());
        if (api && mutates) {
            boolean login = uri.equals("/api/login") || uri.equals("/api/lab/login"); // /api/lab/login: solo laboratorio
            var s = q.getSession(false);
            String tok = s == null ? null : (String) s.getAttribute("csrf");
            String h = q.getHeader("X-CSRF-Token");
            boolean tokenOk = login || (tok != null && h != null && MessageDigest.isEqual(tok.getBytes(StandardCharsets.UTF_8), h.getBytes(StandardCharsets.UTF_8)));
            if (!"ESFAP".equals(q.getHeader("X-Requested-With")) || !tokenOk) { r.sendError(403, "CSRF"); return; }
        }
        r.setHeader("X-Content-Type-Options", "nosniff");
        r.setHeader("X-Frame-Options", "DENY");
        r.setHeader("Referrer-Policy", "same-origin");
        r.setHeader("Content-Security-Policy", CSP);
        r.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        if (q.isSecure()) r.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        if (api) { r.setHeader("Cache-Control", "no-store"); r.setHeader("Pragma", "no-cache"); }
        c.doFilter(q, r);
    }
}
