# LFD-29 D2: matriz de denegación empresarial

## Ejecución focalizada

Desde la raíz, con JDK 21 y binarios PostgreSQL 17 disponibles:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
python scripts/tests/postgres_rls_contract.py
```

El arnés existente crea un clúster nuevo en TEMP, con puerto efímero y secretos
aleatorios, y verifica su directorio físico, dirección, puerto y versión antes
de preparar el fixture. Solo detiene y elimina ese clúster. No utiliza Docker
de desarrollo ni PostgreSQL Windows compartido. CI ejecuta este contrato desde
`test_postgres_migrations.py` en su runner desechable.

## Evidencia cubierta

- Se conservan D1, sus dos tenants A/B y sus dos sucursales, creados antes de V9.
  El administrador comprueba sus filas físicas antes y después de los intentos.
- La matriz cubre SELECT, INSERT, UPDATE y DELETE sobre las 22 tablas protegidas,
  con el runtime sin privilegios y con el propietario restringido por FORCE RLS.
  Los casos base de tenants/sucursales reutilizan D1.
- Se prueban variables ausentes, UUID de A, UUID de B y tenant A/sucursal B.
  También se falsifican `ls.user_id`, `ls.session_id` y `ls.is_admin=true`.
  Cada operación verifica los valores en su misma conexión física. Estos nombres
  son entradas adversariales, no un contrato de autenticación o autorización.
- Runtime: SQLSTATE 42501 con diagnóstico ACL, no RLS. Propietario con ACL DML
  comprobadas: INSERT falla con SQLSTATE 42501 y diagnóstico RLS; SELECT devuelve
  cero y UPDATE/DELETE afectan cero filas, incluso con variables falsificadas.
  INSERT DEFAULT VALUES prueba la barrera RLS, no la validez de una alta de negocio.
- D1 aporta evidencia no vacía de SELECT/UPDATE/DELETE en tenants y sucursales.
  Las otras 20 tablas protegidas permanecen vacías; sus resultados cero no prueban
  aislamiento entre filas autorizadas de empresas diferentes.
- Ambos roles carecen de SUPERUSER, BYPASSRLS, CREATEDB, CREATEROLE, REPLICATION
  y membresías. Se conservan las comprobaciones de ausencia de políticas
  (incluidas permisivas), privilegios de tabla/columna del runtime, 22 tablas
  ENABLE/FORCE, siete catálogos, roles y checksums históricos V1-V9.
- Tras cada escenario, snapshots administrativos de las 29 tablas deben coincidir.
  La limpieza elimina solo las cuatro filas del fixture; al finalizar, todos los
  datos, incluidas las semillas, deben coincidir con el estado previo al fixture.

## Límites

Esta evidencia demuestra denegación total actual, sin políticas de autorización.
No demuestra acceso legítimo, identidad confiable, aislamiento bajo futuras
políticas (G2), revocación inmediata (G3), ni comportamiento de sesiones, cursores
o snapshots. Esos contratos y sus pruebas autorizadas siguen pendientes. No se
ejecutan B2-R, R07 ni R01-R38. F03 no se declara completada.
