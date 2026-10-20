# Lector global C3

`GlobalCatalogReader` expone `applications()` y `modules(UUID applicationId)` con
resultados inmutables y SQL fijo. No publica endpoints. Solo lee columnas públicas
de `catalog.applications` y `catalog.modules`; no otorga autorización empresarial.

Deshabilitado por defecto. Para registrar el bean, configurar externamente:

```text
LS_CATALOG_ENABLED=true
LS_CATALOG_DB_HOST=<host>
LS_CATALOG_DB_PORT=<puerto>
LS_CATALOG_DB_NAME=<base>
LS_CATALOG_DB_PASSWORD=<secreto externo>
```

La identidad es fija: `ls_core_catalog_service_dev`. Cada consulta abre una
conexión física independiente, verifica su identidad, inicia una transacción
read-only y ejecuta `SET LOCAL ROLE ls_core_catalog_reader_dev`. Materializa los
resultados, hace commit (rollback ante error) y cierra la conexión incluso si
falla la limpieza. No usa el pool empresarial ni `LS_DB_*`. Los errores públicos
no contienen diagnósticos JDBC ni secretos.

Prueba reproducible, sin configurar credenciales reales:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$env:LS_PG_BIN = 'C:\Program Files\PostgreSQL\17\bin'
python scripts/tests/postgres_catalog_backend_contract.py
```

El arnés crea PGDATA/puerto/contraseñas efímeros, aplica V1–V9 y C1/C2 sin
modificarlos, autentica Java realmente como C2 y elimina exclusivamente su clúster.
También se integra al arnés CI existente. No ejecutarlo contra PostgreSQL compartido.

Límites: conexiones por llamada (coste de autenticación; C2 limita a tres sesiones),
sin paginación ni reintentos automáticos. La configuración remota/TLS, entrega y
rotación de secretos requieren revisión operativa antes de un despliegue real.
