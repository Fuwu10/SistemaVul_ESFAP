package pe.edu.esfap.portal;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Datos de DEMOSTRACIÓN (portal.seed-demo=true): secciones con horario, estudiantes ficticios, notas, asistencias y cuotas.
 * Solo se ejecuta si todavía no hay secciones. En producción use SEED_DEMO=false y cargue los datos reales.
 */
@Component @Order(2)
public class AcademicoSeeder implements CommandLineRunner {
    private final SeccionRepo secciones; private final InscripcionRepo insc; private final CalificacionRepo cal; private final AsistenciaRepo asis;
    private final CuotaRepo cuotas; private final UserRepo users; private final boolean demo;

    AcademicoSeeder(SeccionRepo s, InscripcionRepo i, CalificacionRepo c, AsistenciaRepo a, CuotaRepo q, UserRepo u, @Value("${portal.seed-demo:true}") boolean d) {
        secciones = s; insc = i; cal = c; asis = a; cuotas = q; users = u; demo = d;
    }

    public void run(String... args) {
        if (!demo || secciones.count() > 0) return;
        String doc = users.findAll().stream().filter(u -> "doc".equals(u.role)).map(u -> u.username).findFirst().orElse("45678912");
        String docNombre = users.findById(doc).map(u -> u.name).orElse("Prof. Carlos Ccente");
        String[][] sec = {{"DIB2", "Dibujo II", "5", "08:00", "11:00", "Aula 204"}, {"PIN1", "Pintura I", "2", "08:00", "11:00", "Taller 1"},
            {"ESC1", "Escultura I", "3", "10:30", "14:15", "Taller 3"}, {"GRA1", "Grabado I", "4", "10:00", "13:00", "Taller 2"}};
        var rnd = new Random(2026);
        String[][] alum = {{"E2024001", "Lucía Quispe Huamán"}, {"E2024002", "Mario Huaccha Rojas"}, {"E2024003", "Ana Gutiérrez Pari"}, {"E2024004", "Jorge Tello Cárdenas"},
            {"E2024005", "Rosa Mendoza Yupanqui"}, {"E2024006", "Luis Ccallocunto Vega"}, {"E2024007", "Karen Ayala Vargas"}, {"E2024008", "Diego Huamán Soto"},
            {"E2024009", "Elena Paucar Quispe"}, {"E2024010", "Wilmer Sulca Flores"}, {"E2024011", "Milagros Cusi Ramos"}, {"E2024012", "Percy Allccahuamán Torres"}};
        LocalDate hoy = LocalDate.now(AcademicoController.LIMA);
        for (var d : sec) {
            var s = new Seccion(); s.cursoId = d[0]; s.nombre = d[1]; s.dia = Integer.parseInt(d[2]); s.horaIni = d[3]; s.horaFin = d[4]; s.aula = d[5]; s.docente = doc; s.docenteNombre = docNombre; s.periodo = "2026-II";
            s = secciones.save(s);
            for (var a : alum) {
                var i = new Inscripcion(); i.seccionId = s.id; i.estudiante = a[0]; i.nombre = a[1]; insc.save(i);
                for (int e = 0; e < 3; e++) { var c = new Calificacion(); c.seccionId = s.id; c.estudiante = a[0]; c.idx = e; c.valor = 10 + rnd.nextInt(10); cal.save(c); }
                LocalDate f = hoy; while (f.getDayOfWeek() != DayOfWeek.of(s.dia) || f.isAfter(hoy)) f = f.minusDays(1);
                for (int k = 0; k < 6; k++, f = f.minusWeeks(1)) {
                    int r = rnd.nextInt(20);
                    var x = new Asistencia(); x.seccionId = s.id; x.estudiante = a[0]; x.fecha = f; x.estado = r == 0 ? "F" : r == 1 ? "T" : r == 2 && k > 1 ? "J" : "P"; asis.save(x);
                }
            }
        }
        // Cronograma de pagos del periodo (monto S/ 330 por cuota, vencimientos fijos del ejemplo)
        LocalDate[] venc = {LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 28), LocalDate.of(2026, 9, 22), LocalDate.of(2026, 10, 22), LocalDate.of(2026, 11, 22)};
        for (var a : alum) {
            int pagadas = a[0].equals("E2024001") ? 4 : 1 + rnd.nextInt(5);
            for (int n = 0; n <= 5; n++) {
                var c = new Cuota(); c.estudiante = a[0]; c.nombre = a[1]; c.periodo = "2026-II"; c.numero = n; c.monto = new BigDecimal("330.00");
                c.concepto = n == 0 ? "RATIFICACIÓN DE MATRÍCULA" : "CUOTA " + n; c.vencimiento = n == 0 ? LocalDate.of(2026, 7, 31) : venc[n - 1];
                if (n <= pagadas) {
                    c.estado = "Pagado"; c.fechaPago = c.vencimiento.plusDays(n == 0 ? -1 : rnd.nextInt(4)); if (c.fechaPago.isAfter(hoy)) c.fechaPago = hoy; c.medio = "Ventanilla"; c.registradoPor = "secretaria"; c.conciliado = rnd.nextInt(4) != 0;
                    c.mora = PagosController.mora(c, c.fechaPago);
                }
                c = cuotas.save(c); if ("Pagado".equals(c.estado)) { c.recibo = "R-2026-" + String.format("%06d", c.id); cuotas.save(c); }
            }
        }
    }
}
