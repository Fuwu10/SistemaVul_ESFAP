package pe.edu.esfap.portal;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reglas de seguridad puras (fáciles de probar): bloqueo, contraseñas, roles,
 * niveles de seguridad de la información y flujo de estados de trámites.
 *
 * Niveles: N1 Público · N2 Interno · N3 Confidencial · N4 Crítico.
 */
public final class Policy {
    public static final int MAX_FAILS = 3;
    public static final long LOCK_MS = 15 * 60 * 1000L;
    public static final int N_PUBLICO = 1, N_INTERNO = 2, N_CONFIDENCIAL = 3, N_CRITICO = 4;

    /** Tipos de trámite de mesa de partes y su nivel de seguridad (todos llevan datos personales: N3). */
    public static final Map<String, Integer> TIPOS = Map.of(
        "Constancia de estudios", N_CONFIDENCIAL,
        "Certificado de notas", N_CONFIDENCIAL,
        "Récord académico", N_CONFIDENCIAL,
        "Solicitud de reserva de matrícula", N_CONFIDENCIAL,
        "Solicitud de traslado", N_CONFIDENCIAL,
        "Constancia de no adeudo", N_CONFIDENCIAL,
        "Otro trámite documentario", N_CONFIDENCIAL);

    /** Área que atiende cada trámite. */
    public static final Map<String, String> AREA = Map.of(
        "Constancia de estudios", "Secretaría Académica", "Certificado de notas", "Secretaría Académica",
        "Récord académico", "Secretaría Académica", "Solicitud de reserva de matrícula", "Secretaría Académica",
        "Solicitud de traslado", "Secretaría Académica", "Constancia de no adeudo", "Área de Tesorería",
        "Otro trámite documentario", "Mesa de partes");

    /** Requisitos a adjuntar por tipo de trámite (se muestran en la columna «Requisitos»). */
    public static final Map<String, String> REQUISITOS = Map.of(
        "Constancia de estudios", "Copia de DNI. Voucher de pago de derecho de trámite.",
        "Certificado de notas", "Copia de DNI. Voucher de pago de derecho de trámite. Foto tamaño carné.",
        "Récord académico", "Copia de DNI. Voucher de pago de derecho de trámite.",
        "Solicitud de reserva de matrícula", "Solicitud fundamentada. Copia de DNI. Constancia de no adeudo.",
        "Solicitud de traslado", "Solicitud fundamentada. Copia de DNI. Certificado de estudios del instituto de origen.",
        "Constancia de no adeudo", "Copia de DNI. Voucher de los pagos realizados.",
        "Otro trámite documentario", "Documento que sustente su solicitud.");

    private static final Map<String, List<String>> FLUJO = Map.of(
        "Recibido", List.of("En revisión", "Rechazado"),
        "En revisión", List.of("Observado", "Aprobado", "Rechazado"),
        "Observado", List.of(),          // lo reactiva el estudiante al subsanar
        "Aprobado", List.of("Emitido"),
        "Rechazado", List.of(),
        "Emitido", List.of());

    private Policy() {}

    public static boolean locked(int fails) { return fails >= MAX_FAILS; }

    /** Mínimo 10 caracteres, con mayúscula, minúscula y número. */
    public static boolean strongPassword(String p) {
        return p != null && p.length() >= 10 && p.length() <= 72
            && p.matches(".*[a-z].*") && p.matches(".*[A-Z].*") && p.matches(".*\\d.*");
    }
    public static boolean strongPassword(String p, String username) {
        return strongPassword(p) && (username == null || !p.toLowerCase().contains(username.toLowerCase()));
    }

    public static boolean validRole(String r) { return r != null && Set.of("est", "doc", "adm", "ti").contains(r); }
    public static boolean validUsername(String u) { return u != null && u.matches("[A-Za-z0-9._-]{3,30}"); }
    public static boolean canViewLogs(String r) { return "ti".equals(r) || "adm".equals(r); }
    public static boolean canManageUsers(String r) { return "ti".equals(r) || "adm".equals(r); }

    /** Evita escalada de privilegios: Administración solo crea/gestiona estudiantes y docentes. */
    public static boolean canAssignRole(String actor, String target) {
        return "ti".equals(actor) || ("adm".equals(actor) && ("est".equals(target) || "doc".equals(target)));
    }

    /** Autorización (clearance) de cada rol. */
    public static int clearance(String role) {
        if (role == null) return N_PUBLICO;
        return switch (role) { case "est", "doc" -> N_INTERNO; case "adm" -> N_CONFIDENCIAL; case "ti" -> N_CRITICO; default -> N_PUBLICO; };
    }
    public static boolean canAccess(String role, int nivel) { return clearance(role) >= nivel; }

    public static boolean canWriteState(String r) { return "doc".equals(r) || "adm".equals(r) || "ti".equals(r); }
    public static boolean canSubmitTramite(String r) { return "est".equals(r) || "doc".equals(r); }
    public static boolean canReviewTramites(String r) { return "adm".equals(r); }
    public static boolean canSubmitMatricula(String r) { return "est".equals(r); }
    public static boolean canReviewMatricula(String r) { return "adm".equals(r); }

    /** Expediente N3: solo su titular o Administración. Soporte TI NO lee documentos (separación de funciones). */
    public static boolean canReadExpediente(String role, String owner, String user) {
        return (owner != null && owner.equals(user)) || (canReviewTramites(role) && canAccess(role, N_CONFIDENCIAL));
    }

    public static boolean transicionValida(String de, String a) { return FLUJO.getOrDefault(de, List.of()).contains(a); }
    public static List<String> siguientes(String de) { return FLUJO.getOrDefault(de, List.of()); }
    public static boolean canManageAcademico(String r) { return "doc".equals(r) || "adm".equals(r); }
    public static boolean canPay(String r) { return "est".equals(r); }
    public static boolean canManagePagos(String r) { return "adm".equals(r); }
    public static int nivelTramite(String tipo) { return TIPOS.getOrDefault(tipo, N_CONFIDENCIAL); }
}
