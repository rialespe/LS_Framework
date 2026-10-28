# LFD-38 / F06.T02: propietario y ultimo delegado

## Alcance y estado operativo

V20 prepara protecciones PostgreSQL para futuras designaciones y revocaciones. **Las mutaciones globales siguen deshabilitadas**: `JdbcPlatformAuthorization.execute()` siempre rechaza, no hay controlador HTTP, bean escritor, credenciales operativas ni mecanismo alternativo de ejecucion. LFD-39 debe incorporar reautenticacion reciente y auditoria antes de autorizar cualquier activacion. Una `Proposal`, un resultado `READY`, una cookie Fiscal/POS o un UUID no constituyen autoridad.

La identidad propietaria de LS Soluciones SpA se instala exclusivamente por el procedimiento excepcional documentado en [LFD-37](platform-owner-lfd-37.md). V20 no modifica, reactiva ni vuelve a ejecutar un bootstrap sellado. No hay cuentas globales predeterminadas. El portal LS Core aun necesita su canal HTTP confiable; las sesiones y perfiles empresariales no lo sustituyen.

## Invariantes independientes

- Antes del bootstrap puede haber cero propietarios. Despues existe exactamente un OWNER ACTIVE: unicidad permanente, estado restringido, sin revocacion, degradacion, sustitucion, borrado o truncado mediante DML ordinario.
- La instalacion OWNER-only, sin delegados, es valida. El primer delegado inicia el estado operativo con delegacion.
- Una vez incorporados delegados, una revocacion ordinaria nunca puede retirar al ultimo SUPERADMIN ACTIVE. Debe designarse primero un reemplazo. La presencia permanente del OWNER no permite omitir esta regla.
- Solo puede designarse una identidad ACTIVE; la designacion registra al OWNER como otorgante. No puede existir mas de una designacion activa por usuario.
- La revocacion conserva el registro y su otorgante original, registra revocante OWNER y hora PostgreSQL. Una nueva delegacion del mismo usuario crea otro registro; no reactiva ni reescribe el historial.
- La FK compuesta de V19 conserva la proteccion de identidades ACTIVE tanto para designaciones activas como historicas revocadas. Es una politica conservadora explicita: esta unidad no habilita suspension ni eliminacion de antiguos delegados. Tampoco modifica credenciales, membresias ni perfiles empresariales.

## Transacciones y bloqueos

La unica ruta SQL preparada es INSERT de SUPERADMIN ACTIVE o UPDATE de SUPERADMIN ACTIVE a REVOKED desde `ls_core_platform_mutator_dev`. Sigue inaccesible operativamente.

1. La futura coordinacion autentica mediante F04; la identidad del propietario nunca se recibe como UUID HTTP. El contrato interno `PlatformMutationCoordination.inspect` exige una conexion con transaccion explicita READ COMMITTED, comprueba el principal tecnico y vuelve a verificar F04. No ejecuta cambios ni hace COMMIT.
2. Un trigger BEFORE STATEMENT exige READ COMMITTED y toma `pg_advisory_xact_lock(370019,1)`, la misma clave del bootstrap. Se adquiere antes de bloquear las filas objetivo del DML, evitando ordenes inversos entre dos escritores.
3. Despues bloquea designaciones por `id` en orden estable y confirma exactamente un OWNER con identidad ACTIVE. La coordinacion Java usa el mismo orden. El trigger no depende de que Java lo haya ejecutado.
4. El guard BEFORE ROW verifica transicion, objetivo, otorgante/revocante OWNER y campos inmutables. Antes de cada revocacion vuelve a contar delegados ACTIVE bajo el bloqueo global. Tambien cubre UPDATE de varias filas y varios cambios en una transaccion.
5. Las consultas de triggers VOLATILE bajo READ COMMITTED obtienen snapshots posteriores a la espera del bloqueo, observan commits previos y los cambios propios de la transaccion. REPEATABLE READ y SERIALIZABLE se rechazan en esta ruta; no se toma una decision con un snapshot anterior al bloqueo.
6. Las FK y los indices validan integridad y concurrencia de identidades y designaciones. Su validacion no depende de que RLS permita leer la fila referenciada. Los guards son SECURITY INVOKER; no hay SECURITY DEFINER ni confianza en GUC de identidad.
7. Ante conflicto se exige ROLLBACK completo. Un COMMIT solo es confirmado despues de su respuesta; no hay reintentos automaticos ante resultado incierto. La prueba demuestra un COMMIT, un rechazo `23514` y ROLLBACK; no promete cancelar operaciones ya ejecutandose.

`LockedDecision` distingue propietario verificado, selector objetivo, accion, condicion actual, numero de delegados y READY/DENIED. Es informacion limitada a esa transaccion, no una credencial ni una autorizacion reutilizable. El futuro ejecutor de LFD-39 debera repetir esta coordinacion en la misma transaccion que el cambio, comprobar sus nuevas garantias y confirmar el resultado; hoy no existe un ejecutor operativo.

## Roles y despliegue

Antes de V20, el operador de instalacion local ejecuta, con su acceso administrativo controlado y conexion separada:

```powershell
psql -X -v apply=true -f scripts/migrations/prepare_platform_mutator.sql
```

Se conservan las condiciones de conexion/instalacion de LFD-37. El script exige PostgreSQL 17 local, `ls_core_dev`, V19 y un rol inexistente; rechaza adopciones. Crea solamente `ls_core_platform_mutator_dev` NOLOGIN, NOINHERIT, sin password, sin CONNECT, sin membresias, sin propiedad, superusuario, BYPASSRLS ni otros atributos administrativos. No cambia el bootstrap.

El migrador restringido aplica `V20__platform_superadmin_concurrency.sql`. V1-V19 permanecen intactas. V20 otorga al rol dormido SELECT por columnas sobre designaciones/estado de usuarios, INSERT de campos de delegacion y UPDATE solo de estado/revocante/fechas. No otorga DELETE, TRUNCATE, acceso a hashes, escrituras de identidad, permisos empresariales ni rol asumible por servicios. Las tablas conservan FORCE RLS; las politicas exigen principal real `session_user=current_user`. Los servicios de sesiones y autorizacion empresarial no reciben privilegios nuevos.

La existencia del rol dormido no verifica por si sola una identidad OWNER. El campo otorgante/revocante es una comprobacion de integridad contra el OWNER vigente, no prueba F04. No se distribuyen credenciales para ese rol. Solo la futura coordinacion confiable con las protecciones de LFD-39 podra convertirse en canal de mutacion. Un DBA capaz de alterar roles, triggers o DDL permanece en la frontera administrativa de confianza; no se pretende resistir la eliminacion deliberada de restricciones por ese operador.

## Evidencia focalizada y piloto

Un unico metodo nuevo `PlatformConcurrencyPostgresTest.serializedRevocationsPreserveLastDelegateAndProtectedIdentities` usa PostgreSQL 17 propio/desechable y F04 real. Prepara OWNER y dos delegados, inicia dos conexiones JDBC con barrera, mantiene el primer cambio sin commit y observa en `pg_locks` la espera advisory de la segunda conexion antes de liberar el COMMIT. No usa sleeps como sincronizacion. Comprueba ultimo delegado, rollback atomico de UPDATE multiple, propietario, identidades, duplicados, privilegios, historial y membresias conservadas. Los servicios operativos siguen bloqueados.

Solo ese runner de pruebas asigna temporalmente SCRAM/LOGIN/CONNECT al rol dormido dentro del cluster propio. En `finally` retira LOGIN/CONNECT y password y verifica cero conexiones; tras el exito sella el bootstrap. El harness destruye el cluster tambien ante fallos. Nunca se usa 5432, 55432 ni una base compartida.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
python scripts/tests/postgres_platform_concurrency_contract.py
```

El piloto solo incorpora el prerrequisito sin credenciales y V20; no instala OWNER ni SUPERADMIN y mantiene Fiscal, HTTPS, sesiones, contexto y limpieza existentes:

```powershell
python scripts/pilot/start_connected_pilot.py
```

URL habitual: `https://127.0.0.1:5174`. No se agrega interfaz global ni se habilita `OperationAdmission.session`, API Keys o SQL empresarial general.

## Dependencia pendiente

LFD-39 debe aportar reautenticacion reciente, auditoria critica y el mecanismo confiable de activacion/ejecucion antes de cualquier uso administrativo mutable. LFD-38 implementa serializacion y restricciones reales en PostgreSQL, no expone administracion global utilizable desde HTTP.

## Validacion de entrega

La comprobacion focalizada paso: un metodo, cero fallos/errores/omisiones, con Checkstyle en el mismo `verify`. Se corrigio un fallo real de la primera ejecucion: en BEFORE UPDATE la columna generada aun no esta calculada; se excluye de la comparacion OLD/NEW y sigue protegida por su expresion y FK. La misma prueba se ejecuto de nuevo con exito, sin agregar escenarios exploratorios ni invocar metodos historicos. Se comprobo un COMMIT y un ROLLBACK concurrentes, conservando el ultimo delegado; posteriormente se valido rollback completo de una revocacion multiple. El rol excepcional quedo sin LOGIN/password/CONNECT y sin conexiones, y el cluster se elimino. Lint, TypeScript y build React pasaron; no hubo cambios visuales ni se repitio el piloto HTTPS manualmente.
