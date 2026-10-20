# LFD-25: preparación exclusiva para PostgreSQL Docker

Base de herramientas: `ef6953d3ce2c702e24b36294bf55c50e0b744ce6`.

## Alcance y uso

`scripts/provision_docker_postgres.py` implementa **solo el preflight de lectura**.
La provisión, Flyway y JDBC permanecen pendientes por el bloqueo ACL descrito abajo.
No tiene modo de aplicación, reparación, adopción o borrado. No ejecuta los scripts
existentes de Windows ni cambia sus destinos.

En una consola local interactiva, desde la raíz del repositorio:

```powershell
python scripts/provision_docker_postgres.py
# Opcional: informe sin secretos fuera del repositorio
python scripts/provision_docker_postgres.py --report "$env:TEMP\ls-core-docker-preflight.json"
```

Requiere Docker Desktop y el cliente existente
`C:\Program Files\PostgreSQL\17\bin\psql.exe`. La contraseña de `ls_core_docker`
se solicita con entrada oculta; no se acepta por argumentos, archivos ni stdin
redirigido. Nunca introducirla en el chat. Se pasa a libpq solo en el entorno del
proceso hijo; no se imprime ni se escribe en el informe.

La herramienta verifica nombre/ID, imagen `postgres:17`, estado activo, volumen
`ls-core-pg17-data`, publicación exclusiva `127.0.0.1:55432` y versión 17. Compara
el identificador físico de `pg_controldata` con `pg_control_system()` leído por SQL.
No imprime `Config.Env` de Docker. La conexión SQL fija host, puerto, usuario y base
`ls_core_docker_dev`; usa `psql -X -w` con transacciones de lectura, timeout y entorno
libpq saneado. No conecta al puerto 5432 del host.

Ante errores, conserva el diagnóstico depurado, clasifica autenticación/conexión/
permisos/SQL y muestra el SQLSTATE cuando psql lo expone. Algunos fallos libpq
anteriores a la sesión SQL no lo incluyen; no se inventa un código. El preflight
no reintenta. Un error, conflicto o ACL incompatible termina con código 1.

## Evidencia ejecutada el 20 de octubre de 2026

- Contenedor: `ls-core-pg17`, ID
  `03dfeb029dd7f36c8aa8e1af0a685f478249c0097243e72f51aa43095780d83e`.
- PostgreSQL **17.11**, volumen y publicación localhost correctos.
- Identificador físico y SQL coincidentes: `7698808171985612839`.
- Conexión autenticada como `ls_core_docker`, superusuario y propietario de
  `ls_core_docker_dev`. Base bootstrap conservada.
- `ls_core_dev` inexistente; `ls_core_migrator_dev` y `ls_core_app_dev` inexistentes.
  Por tanto, tampoco hay esquemas existentes que adoptar en la base objetivo.
- **BLOQUEADO**: `ls_core_docker_dev`, `postgres` y `template1` permiten `CONNECT`
  a `PUBLIC`. No se ejecutó DDL ni se cambiaron ACL.

El intento anterior descartó stderr y su SQLSTATE. Su causa histórica exacta no
es reconstruible a partir del informe guardado. La nueva inspección sí pasó SQL;
no hay evidencia para atribuir el fallo anterior a una contraseña incorrecta.
El bloqueo actual es una validación de seguridad de la herramienta, no un error
SQL del servidor, y por ello no tiene SQLSTATE.

## Cambio necesario: propuesta no ejecutada

La comprobación existente en `scripts/migrations/provision.sql` rechaza otras bases
con `PUBLIC CONNECT`. `NOINHERIT` no elimina privilegios recibidos por `PUBLIC` y
PostgreSQL no permite un `DENY CONNECT` individual que los anule.

La corrección mínima a revisar requeriría autorización específica para cambiar
ACL de estas **otras bases dentro del contenedor verificado**:

```sql
-- PROPUESTA; NO EJECUTADA. Solo en el clúster Docker cuya identidad se verificó.
REVOKE CONNECT ON DATABASE ls_core_docker_dev FROM PUBLIC;
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
REVOKE CONNECT ON DATABASE template1 FROM PUBLIC;
```

Esto no borra bases ni datos, pero impide nuevas conexiones de roles que dependan
exclusivamente de `PUBLIC CONNECT`; los propietarios conservan sus privilegios.
Cualquier acceso adicional necesario deberá revisarse explícitamente. La herramienta
no aplica estos cambios ni ofrece un bypass de la validación.

Tras resolver y autorizar ese cambio, quedan pendientes: repetir el preflight,
implementar y revisar la fase de aplicación Docker-only, crear la base nueva con
propietario administrativo, los dos roles sin membresías ni privilegios elevados
(límite 3), CONNECT únicamente a `ls_core_dev` y sin CREATE/TEMP, ocho esquemas
propiedad del migrador y sin privilegios PUBLIC/runtime. Las contraseñas nuevas
deberán establecerse mediante `psql \password` con SCRAM y entrada local segura.

Finalmente deberá ejecutarse Flyway V1–V9 original como migrador restringido contra
`jdbc:postgresql://127.0.0.1:55432/ls_core_dev`, verificar historial/checksums,
29 tablas de dominio más historial técnico, siete catálogos con 28 semillas V8,
22 tablas con ENABLE/FORCE RLS V9 y sin políticas, ausencia de acceso empresarial
del runtime y conexión JDBC/SELECT 1 con `ls_core_app_dev` sin endpoints de negocio.
**Estas operaciones y comprobaciones aún no se han ejecutado.**

No se modifican V1–V9, PostgreSQL de Windows, documentos locales de diseño ni
los destinos de `manage_migrations.py`. No se ejecutan B2-R ni pruebas G2/G3.
