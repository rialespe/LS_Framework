# Contexto transaccional confiable — LFD-28

Infraestructura preparada para integración futura. No hay autenticación real, RBAC funcional,
lectura autorizada de sesiones/API Keys ni aislamiento efectivo de tablas. Ninguna clase nueva
es bean Spring, controlador o adaptador de producción. El perfil postgres y su HealthIndicator
SELECT 1 permanecen intactos. No se crea una ruta alternativa de autenticación.

## Confianza y responsabilidades

`access/application/context/TrustedContextSource` es un puerto del servidor, suministrado por
composición confiable. Su `AuthenticatedIdentity` es evidencia opaca propiedad del autenticador
futuro, nunca una cookie, header, UUID o token sin verificar. La interfaz no autentica por sí sola:
un adaptador deberá comprobar que la evidencia fue emitida por su autenticador y vincularla a
la credencial, usuario y aplicación devueltos. Los records de evidencia son datos del puerto,
no contextos autorizados. Código Java arbitrario o un adaptador malicioso quedan fuera de esta
frontera; implementar la interfaz no acredita una identidad. No enlazar estos tipos a JSON/HTTP.

La fuente debe leer evidencia coherente y actual de sesión/contexto/membresía/sucursal/acceso/
habilitación o de API Key/sucursal/habilitación. Debe rechazar identidad de tipo incorrecto,
revocación y datos ausentes/indisponibles. La resolución real necesita un protocolo aprobado de
lectura y frescura/concurrencia; LFD-28 no incorpora repositorios ni concede permisos SELECT.
No suministrar un adaptador que devuelva VERIFIED por defecto.

`ContextResolver` valida las relaciones estructurales recibidas. Para SESSION compara sesión,
usuario, aplicación, contexto seleccionado, tenant, membresía, sucursal, vínculo explícito de
membresía a sucursal y habilitación tenant/aplicación. Comprueba expires_at y revoked_at de
sesión y contexto. V4 obliga a branch_id para sesiones. Un expires_at nulo del contexto no
amplía la vida de la sesión: la sesión siempre debe tener expiración futura.

Para API_KEY usa un método separado del puerto: exige credencial autenticada como API Key,
identificador propio, expiración futura, ausencia de revocación, ámbito tenant/aplicación y
sucursal coherentes con la habilitación y rama opcional. El actor es la propia API Key; no se
atribuye el usuario creador como actor. No crea membresía ni contexto de usuario para API Key.
Una sucursal nula permanece nula y no satisface un selector de sucursal concreta; no es un
comodín ni permiso de consolidación. Scopes y permisos efectivos quedan en F16/F10.

Los selectores `Scope` y contextId solamente restringen el resultado. Cambiar tenant_id desde
el cliente provoca rechazo cuando no coincide con la evidencia de la fuente. No hay método
que convierta solamente UUID en contexto autorizado. `AuthorizedContext` tiene constructor
privado, claims inmutables y consumo atómico de una sola ejecución. `Claims` es una vista de
datos y no se acepta como autorización por el ejecutor.

`Validity` representa la decisión explícita de la fuente respecto de vigencia funcional, no
un vocabulario nuevo de estados de base de datos. DENIED, UNKNOWN y null fallan. Siguen
pendientes las políticas de membresías, sucursales, acceso y habilitación empresarial; no se
deduce vigencia por existencia de una FK ni por una cadena arbitraria de status. La fuente
real deberá implementar esas políticas después de su aprobación. VERIFIED en fixtures expresa
una decisión simulada; no prueba esas políticas ni consultas contra security/tenant/catalog.

## Ciclo JDBC y límites

`operations/infrastructure/jdbc/ContextTransaction` recibe exclusivamente AuthorizedContext y
un trabajo síncrono. Consume y revalida la evidencia antes de obtener conexión, otra vez antes
de establecer variables y antes de confirmar. Expiración/revocación/indisponibilidad/cambio de
claims en cualquiera de esas comprobaciones rechaza la operación. Esto no garantiza ausencia
de carreras de revocación entre comprobaciones; el futuro adaptador debe definir su protocolo.

1. Obtiene una conexión Hikari de solo lectura y autocommit inicial verdadero; verifica que
   las nueve variables propias estén ausentes o vacías. Una contaminación previa se rechaza.
2. Desactiva autocommit. pgJDBC inicia BEGIN antes del primer SELECT transaccional. Establece
   valores mediante `SELECT set_config(?, ?, true)` parametrizado sobre esa conexión.
3. Propaga ls.tenant_id, application_id, branch_id, origin, actor_id, credential_id,
   session_context_id, membership_id y branch_membership_id (cada nombre con prefijo ls.).
   Ausencias explícitas se representan por cadena vacía; el consumidor futuro debe rechazarlas
   cuando su ámbito las requiera, no interpretarlas como acceso global.
4. Entrega solo `Queries`: consultas SQL del código confiable con parámetros y filas de cadenas
   materializadas e inmutables. No entrega Connection/Statement/ResultSet ni devuelve un resultado
   diferido. Comprueba variables y el identificador PostgreSQL de transacción antes y después de
   cada consulta y al final. Un fallo capturado por el callback también invalida el trabajo.
5. Confirma, restaura autocommit, comprueba ausencia de valores residuales y cierra el proxy para
   devolverlo al pool. PostgreSQL puede dejar el parámetro personalizado definido pero vacío:
   se comprueba ausencia del valor anterior, no inexistencia del nombre.
6. Ante error intenta rollback y expulsa físicamente la conexión con Hikari evictConnection.
   También expulsa ante fallo de rollback, baseline contaminado o estado incierto. No cierra por
   segunda vez el proxy expulsado: Hikari 7.0.2 puede intentar restaurar un PoolEntry ya separado.
   La expulsión cierra la conexión física y cancela su tarea de detección de fugas. Fallos de
   cierre normal también provocan expulsión y rechazo. No se registran diagnósticos privados.

La fachada caduca en finally y rechaza uso desde otro hilo. Un ThreadLocal acotado solamente
rechaza anidamiento en el mismo ejecutor; se elimina siempre y no almacena la identidad ni
constituye aislamiento. No hay setter de tenant ni incorporación de otro contexto a una conexión.
El callback debe publicar resultados/efectos externos solo después del retorno satisfactorio de
execute: no es posible deshacer efectos externos de código arbitrario que los publique antes.
El componente actual conserva read-only; no habilita DML empresarial.

Según [PostgreSQL 17](https://www.postgresql.org/docs/17/functions-admin.html#FUNCTIONS-ADMIN-SET),
is_local=true limita set_config a la transacción. No se usa SET SESSION ni is_local=false en
producción. El único false nuevo está en el fixture adversarial de contaminación del pool.

**SQL es código confiable, no entrada del cliente.** La fachada no es un sandbox SQL: un SQL
arbitrario con capacidades de sesión puede modificar parámetros y producir efectos antes de
ser detectado, o modificarlos y restaurarlos dentro de una sola consulta. Las comprobaciones
son defensa ante errores, no autenticación criptográfica ni garantía RLS. No exponer Queries,
SQL, el puerto o el pool mediante endpoints genéricos. Una política de roles/SQL y RLS efectiva
requiere LFD-29; su validación integral corresponde a LFD-30. No hay aislamiento multi-tenant
por el mero hecho de establecer parámetros. Los propietarios normalmente omiten RLS; revisar
FORCE RLS, superusuarios/BYPASSRLS y vías especiales explícitas y auditadas antes de adoptarla.

## Matriz propuesta para LFD-29 (sin DDL)

Cada entrada procede de V2–V7 y el diccionario. No propone una política común a todas las tablas.
No se ejecuta ENABLE/FORCE RLS ni GRANT; no se aprueba aquí una política funcional.

| Tabla | ámbito / análisis pendiente |
| --- | --- |
| identity.users | Global; acceso a identidad mediante vía limitada, nunca listado empresarial libre |
| identity.user_credentials | Global sensible; autenticador separado, no SELECT runtime genérico |
| platform.administrators | Global; vía especial explícita y auditada para OWNER/SUPERADMIN |
| catalog.applications | Catálogo global; lectura/publicación a decidir |
| catalog.modules | Catálogo global por aplicación |
| security.permissions | Catálogo global por aplicación; no concesión efectiva |
| security.role_templates | Plantillas globales versionadas |
| security.role_template_permissions | Relaciones globales por aplicación |
| security.profile_templates | Plantillas globales versionadas |
| security.profile_template_roles | Relaciones globales por aplicación |
| security.sessions | Global por usuario/aplicación, sin tenant_id; autenticador, no política tenant genérica |
| operations.maintenance_windows | Global operativo; mantenimiento no concede acceso empresarial |
| operations.operation_runs | Global operativo; vía administrativa acotada |
| tenant.tenants | Raíz empresarial: id, no columna tenant_id |
| tenant.branches | tenant_id obligatorio; sucursal contextual según operación |
| tenant.memberships | tenant_id obligatorio; identidad y vigencia por política pendiente |
| tenant.branch_memberships | tenant_id obligatorio; vínculo explícito a sucursal |
| tenant.administrators | tenant_id obligatorio; designación no equivale a bypass |
| catalog.tenant_applications | tenant_id obligatorio y aplicación; habilitación funcional pendiente |
| catalog.tenant_modules | tenant_id obligatorio, aplicación y módulo |
| security.roles | tenant_id obligatorio y aplicación |
| security.role_permissions | tenant_id obligatorio y aplicación |
| security.profiles | tenant_id obligatorio y aplicación |
| security.profile_roles | tenant_id obligatorio y aplicación |
| security.branch_profile_assignments | tenant_id obligatorio, aplicación y branch_membership; no sumar otras sucursales |
| security.session_contexts | tenant_id obligatorio, sesión/usuario/aplicación/membresía/sucursal; resolver autenticado |
| integration.api_keys | tenant_id obligatorio y aplicación, sucursal opcional sin comodín |
| integration.api_key_permissions | tenant_id obligatorio y aplicación; autorización F16/F10 |
| audit.events | tenant_id nullable; evidencia histórica sin FK, separar lectura empresarial de global auditada |

Son 13 tablas globales, 14 con tenant_id obligatorio, una raíz y una excepción de auditoría.
El historial Flyway es adicional y exclusivamente administrativo. Ninguna operación global de
mantenimiento hereda privilegios empresariales. No introducir funciones SECURITY DEFINER,
roles permanentes o permisos runtime como atajo para resolver contexto.

## Validación e integración pendientes

Ver [evidencia LFD-28](verification-lfd-28.md). Unitarias: resolver con evidencia sintética y
fallos JDBC simulados. Integración: JDBC real, pool físico de tamaño uno para demostrar
reutilización, PostgreSQL 17 desechable y rol runtime sin nuevos privilegios; identidad simulada.
El harness ejecuta las cinco pruebas PostgreSQL obligatoriamente dentro del job Migrations y
exige cero omisiones; Backend omite esas cinco deliberadamente y ejecuta unitarias y smoke.
Frontend conserva su job y pruebas. No se usan usuarios/datos reales ni servidores externos.

F04/F05 deben aprobar e implementar autenticación de sesiones e integración de evidencia;
F16, autenticación y restricciones de API Keys; F10, políticas y permisos efectivos. Para
conectar el puerto a tablas reales hacen falta revisión del protocolo de confianza/frescura,
vocabularios funcionales y privilegios mínimos de lectura. Detenerse y solicitar aprobación
antes de introducir tales adaptadores/privilegios. No se ha implementado ni validado esa parte.
LFD-29 debe resolver roles, privilegios, vías globales y RLS; LFD-30 debe verificar fugas en todas
las tablas protegidas. Esta base de pruebas no cierra ninguna de esas subtareas.

Compatible con ADR 0002: pool opcional de máximo tres, readOnly=true, SELECT 1 y logs originales.
Compatible con ADR 0004 **Propuesto**: Flyway exclusivamente administrativo; V1–V8, semillas,
checksums, ownership, ACL y scripts de provisión sin cambios. Sin nuevas dependencias.
