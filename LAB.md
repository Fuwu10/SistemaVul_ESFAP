# Laboratorio: Inyección (A02) y Exposición de datos sensibles (A03)

Activar (solo local): `LAB_VULN=true` (variable de entorno o `-Dportal.lab-vulnerable=true`). Sin la variable, los endpoints no existen.
Primero ejecuta con la base de datos nueva y anota las credenciales ficticias que salen en consola.

## A02 – Inyección SQL
1. `GET /api/lab/buscar?q=Lucía` → búsqueda normal.
2. Prueba `q='` → el error verboso revela la consulta.
3. Prueba con `q=' OR '1'='1` y con UNION para leer otra columna (ej. `password_hash`).
4. `POST /api/lab/login` (cabecera `X-Requested-With: ESFAP`) con `{"id":"x' OR '1'='1' -- ","pw":"x"}`.
## A03 – Exposición de datos sensibles
1. `GET /api/lab/usuarios` sin sesión → hashes de contraseña.
2. `GET /api/lab/config` → URL de BD y clave HMAC.
3. `GET /api/lab/backup` → descarga `secret.key`.
4. Revisa la consola: las claves de login se imprimen en claro; los errores muestran trazas.
## Corrección (parte 2 del laboratorio)
Consultas parametrizadas / JPA, no exponer hashes ni config, exigir autenticación y rol, cifrar respaldos, no registrar secretos, ocultar trazas.
Las versiones seguras ya están en `ApiController`.
