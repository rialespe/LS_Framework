# Fronteras modulares de LS Core

## Implementado en LFD-22

Monorepo con un único artefacto Spring Boot y una aplicación React independiente.
La raíz Java escanea `com.lssoluciones.lscore`. No hay submódulos Maven ni
implementaciones de dominio. Actuator proporciona la única ruta técnica.

## Backend previsto

Los siguientes paquetes se crearán bajo la raíz Java cuando tengan contenido:

| Módulo lógico | Responsabilidad futura |
| --- | --- |
| `common` | Componentes compartidos de uso real; no depósito general de utilidades |
| `identity` | Usuarios globales, contraseñas y sesiones propias por aplicación |
| `platform` | Propietario, superadministradores y configuración global |
| `tenant` | Empresas, sucursales y membresías |
| `access` | Perfiles contextuales, roles y permisos |
| `catalog` | Aplicaciones, módulos y navegación |
| `integration` | API Keys por tenant e integraciones externas |
| `audit` | Registro de operaciones |
| `operations` | Mantenimiento, monitoreo y respaldos |

Cada módulo podrá separar `presentation`, `application`, `domain` e
`infrastructure` cuando existan clases concretas. El dominio no dependerá de
presentación ni infraestructura; aplicación coordinará casos de uso y puertos;
infraestructura implementará adaptadores. Las colaboraciones entre módulos deben
pasar por contratos explícitos de aplicación, sin acceder a internals o repositorios
de otros módulos. Mantener el grafo acíclico y documentar cada nueva dependencia.
Estas reglas son convenciones documentadas, todavía no verificadas automáticamente.

No se conecta LS Fiscal ni LS Punto de Venta directamente a las clases internas
del núcleo. Las futuras integraciones definirán contratos externos.

Spring Security se incorporará posteriormente en la frontera de entrada para
validaciones de identidad, autorización y contexto tenant. El aislamiento mediante
`tenant_id` y PostgreSQL RLS requiere diseño y pruebas posteriores; aquí no se
implementa un tenant global, filtros vacíos ni garantías ficticias de aislamiento.
La autenticación prevista es propia, sin SSO, con sesiones independientes.

## Frontend previsto

Actualmente `app/` compone la pantalla y concentra la configuración pública;
`components/Brand.tsx` encapsula la identidad visual reutilizable.
Se crearán las siguientes carpetas solo cuando tengan una responsabilidad concreta:

| Área | Responsabilidad futura |
| --- | --- |
| `features/` | Funciones separadas por dominio; autenticación pública y portales superadministrador/tenant |
| `layouts/` | Estructuras visuales de cada portal, menú horizontal y contexto empresa/sucursal |
| `routes/` | Declaración de rutas y fronteras de acceso cuando exista navegación |
| `services/` | Transporte REST centralizado usando `app/config.ts`; tratamiento común de errores |
| `types/` | Contratos verdaderamente compartidos; tipos locales permanecen en su feature |
| `components/` | Elementos reutilizables, incluidos estados de carga/error cuando sean necesarios |

Las features no deben importar detalles internos de otras features. La composición
vive en `app/`; configuración pública no contiene secretos. No se añade router,
cliente HTTP, estado global, login ni dashboards antes de tener un caso real.

## Infraestructura JDBC opcional (LFD-23)

`operations/infrastructure/jdbc/PostgresConfiguration` existe únicamente bajo
el perfil `postgres`. Crea un pool Hikari y un indicador Actuator que ejecuta
`SELECT 1` con cierre de ResultSet, Statement y devolución de Connection al pool.
El pool se cierra al apagar Spring. No hay repositorios, transacciones de negocio
ni inicialización SQL. Solo se incorporan HikariCP y pgJDBC, con versiones del BOM
de Spring Boot: al no incorporar el módulo de autoconfiguración JDBC, el modo base
no intenta crear un DataSource ni buscar una base embebida.

Los límites locales y la supresión de diagnósticos de conexión se documentan en
[la guía PostgreSQL](../postgresql-local.md). No constituyen seguridad multi-tenant.

## Próximos trabajos

LFD-24: controles de lint,
verificaciones automatizadas más extensas y CI. RLS, autenticación, entidades,
permisos, menús dinámicos e integraciones pertenecen a historias posteriores.
