# Portal ESFAP – Documentación técnica (v0.2)

## Arquitectura
Navegador (HTML/JS) -> Spring Boot (API REST + estáticos) -> base de datos (H2 en `./data`; PostgreSQL en producción).
Modelo híbrido: portal y BD del portal en la nube; BD institucional en la VLAN 20 de la sede. Respaldo: copia cifrada de `./data` a la sede.

## Trazabilidad con los requerimientos
| Código | Requerimiento | Estado en esta versión |
|---|---|---|
| RF-01 | Matrícula virtual (Alta) | **Implementado**: catálogo con requisitos validados en servidor, flujo en 3 pasos, aprobación/rechazo con motivo, constancia. |
| RF-02 | Pagos en línea (Alta) | **Implementado con pasarela simulada**: cronograma de cuotas, mora por día, pago en línea, registro en ventanilla, conciliación, recibos. **Pendiente**: integrar pasarela real (el punto de integración es `POST /api/pagos/{id}/pagar`). |
| RF-03 | Plataforma académica (Alta) | **Implementado**: horario, calificaciones (4 evaluaciones ponderadas), control y registro de asistencias, justificaciones con sustento, materiales. Autorización por sección en el servidor. |
| RF-04 | Trámites documentarios digitales (Alta) | **Implementado**: Mesa de partes con adjuntos, **cargo digital**, «Mis trámites» (fechas, área, requisitos, seguimiento, constancia), observación/subsanación, bandeja de Administración. |
| RF-05 | Certificados con constancia verificable (Media) | **Implementado**: al emitir se genera código `ESFAP-XXXXXX`; verificación pública en «Verificar documento». El cargo (`MP-AAAA-NNNNNN`) también es verificable y detecta alteraciones. |
| RF-06 | Mesa de servicio de incidencias (Alta) | Interfaz existente (tickets en el almacén legado). **Pendiente**: tabla propia, asignación y SLA. |
| RF-07 | Continuidad biblioteca / web / correo (Media) | Biblioteca y web operativas. Continuidad: respaldos y monitoreo (ver «Pendientes»). |

## Módulos por rol
- **Estudiante:** inicio (próximas clases, promedio, asistencia, deuda), matrícula, horario, notas, asistencias (con acceso directo a justificar una falta), justificaciones, materiales, pagos y cronograma, mesa de partes y «Mis trámites».
- **Docente:** mis cursos, control de asistencia (hasta 30 días atrás), registro de asistencias (exportable a CSV), calificaciones con promedio en vivo, materiales (PDF, imágenes, Word, PowerPoint, Excel), revisión de justificaciones.
- **Administración:** controla todo: asigna docentes/aulas/horarios por sección, ve y corrige notas y asistencias de cualquier sección (queda auditado con la marca `[admin]`), resuelve justificaciones, aprueba matrículas (inscribe al estudiante en sus cursos), gestiona pagos (registrar, conciliar, generar cronogramas), bandeja de mesa de partes, reportes reales, usuarios y auditoría.
- Reglas: nota aprobatoria 11 (escala 0–20); el % de asistencia excluye las justificadas (en rojo bajo 70 %); evaluaciones: Práctica 1 (20 %), Parcial (30 %), Práctica 2 (20 %), Final (30 %); mora diaria 0,027 % de la cuota.

## Datos de demostración
Con `portal.seed-demo=true` (por defecto) el primer arranque crea 4 secciones, 12 estudiantes **ficticios**, notas, asistencias y cuotas. **En producción use `SEED_DEMO=false`** y cargue datos reales (secciones y cronogramas desde Administración).

## Niveles de seguridad de la información
| Nivel | Qué protege | Quién accede |
|---|---|---|
| N1 Público | Portada, carreras, verificación de código, consulta de trámite (código + DNI/código) | Todos |
| N2 Interno | Matrícula propia, notas, asistencia, biblioteca | Estudiante, docente, administrativo, TI (cada uno ve lo suyo) |
| N3 Confidencial | Expedientes de mesa de partes y adjuntos, justificaciones con sustento, pagos, gestión de matrículas | Titular y Administración. **Soporte TI no lee documentos** (separación de funciones). Toda lectura ajena queda auditada. |
| N4 Crítico | Usuarios y roles, auditoría, configuración, respaldos | Soporte TI (usuarios y auditoría también Administración, sin poder crear/administrar cuentas administrativas) |

## Controles de seguridad (qué cambió)
- **Corregido (crítico):** `PUT /api/state` permitía a *cualquier* usuario autenticado sobrescribir todos los datos, y `GET` entregaba los datos de todos. Ahora solo escribe personal (docente/administrativo/TI) y el estudiante no recibe datos de terceros. Matrículas y trámites salieron de ese almacén a tablas propias con autorización por registro.
- Contraseñas iniciales **aleatorias** (se imprimen una vez en consola) y cambio obligatorio al primer ingreso. Bases existentes con las claves antiguas de demostración quedan marcadas para cambio.
- Política: mín. 10 caracteres con mayúscula, minúscula y número; no puede contener el usuario; BCrypt costo 12.
- Bloqueo temporal de 15 min tras 3 fallos; límite de 10 intentos/5 min por IP; tiempos de respuesta igualados para no revelar si una cuenta existe.
- Anti-CSRF con **token por sesión** (antes, cabecera fija) + cookie `HttpOnly`, `SameSite=Strict`, `Secure` bajo HTTPS (`COOKIE_SECURE=true`).
- Cabeceras: CSP, X-Frame-Options, nosniff, Referrer-Policy, Permissions-Policy, HSTS (bajo HTTPS), `Cache-Control: no-store` en la API.
- Adjuntos: validados por **contenido** (PDF/JPG/PNG), máx. 5 MB, nombre UUID en `./data/uploads` (fuera de la carpeta pública), descarga solo con sesión y autorización, `Content-Disposition: attachment`, huella SHA-256.
- Cargo digital: sello HMAC-SHA256 con clave local (`./data/secret.key`, **respáldela y protéjala**).
- Auditoría encadenada por hash; «Configuración» permite verificar su integridad.
- Sin escalada de privilegios: Administración no puede crear ni desactivar cuentas `adm`/`ti`.
- Errores sin trazas; consola H2 deshabilitada.

## API nueva
`POST /api/mp/tramites` (multipart) · `GET /api/mp/tramites[/{id}]` · `GET /api/mp/tramites/{id}/adjuntos/{aid}` · `POST /api/mp/tramites/{id}/subsanar` · `PUT /api/mp/tramites/{id}/estado` (adm) · `GET /api/mp/seguimiento?codigo=&id=` (público) · `GET/POST /api/matriculas`, `GET /api/matriculas/catalogo|mia`, `PUT /api/matriculas/{id}/estado` · `/api/ac/*` (secciones, notas, asistencia, justificaciones, materiales) · `/api/pagos/*` (cuotas, pagar, registrar, conciliar, cronograma) · `GET /api/security/policy` · `GET /api/logs/verify` · `GET /api/verify/{codigo}`.

Flujo de estados: Recibido → En revisión → (Observado → [el estudiante subsana] → En revisión) → Aprobado → Emitido; o Rechazado.

## Instalación
JDK 17+. STS: importar proyecto Maven y ejecutar `PortalApplication`. Consola: `mvn spring-boot:run`. Abrir http://localhost:8080
**Primer arranque:** copie las contraseñas que aparecen en la consola (no se vuelven a mostrar). Usuarios: `E2024001` (estudiante), `45678912` (docente), `secretaria` (administrativo), `soporte` (TI).
Producción: TLS 1.2+ en proxy inverso, `COOKIE_SECURE=true`, `FORWARD_HEADERS=native`, PostgreSQL, cifrado de disco/respaldos (Ley N.° 29733).

## Pendientes recomendados (no incluidos)
1. **Doble factor (TOTP)** para administrativo y TI, y re-autenticación para descargar documentos N3.
2. **Antivirus** (ClamAV) sobre los adjuntos antes de aceptarlos; un PDF puede traer código activo.
3. Migrar la mesa de servicio (RF-06) y la biblioteca a tablas con autorización en servidor, y eliminar `/api/state`. Integrar la pasarela de pagos real.
4. Quitar `'unsafe-inline'` de la CSP (requiere mover los `onclick` y scripts en línea a archivos y `addEventListener`).
5. Limitador de peticiones compartido (Redis/proxy) si hay más de un servidor; correo de notificación de cambios de estado.
6. Respaldos automáticos cifrados y prueba periódica de restauración (RF-07).
