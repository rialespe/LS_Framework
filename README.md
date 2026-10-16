# LS Core Multi-Tenant v4.0

Base técnica del framework central de administración de LS Soluciones SpA.
Scaffold **LFD-22**, conectividad JDBC opcional **LFD-23** y calidad/CI **LFD-24**,
integrados en `main`, de la historia LFD-2 y épica LFD-1. **LFD-82** completa
la documentación para revisión; no cierra por sí sola la historia F01.

## Documentación del proyecto

- [Arquitectura y fronteras modulares](docs/architecture/modules.md).
- [Índice de ADR](docs/adr/README.md): stack, PostgreSQL y calidad/flujo Git.
- [Guía operativa de Codex](docs/codex/README.md).
- [Plantilla de prompts por incidencia Jira](docs/codex/prompt-template.md).
- [PostgreSQL local y credenciales externas](docs/postgresql-local.md).
- [Migraciones PostgreSQL LFD-25](docs/postgresql-migrations.md): infraestructura
  administrativa y [evidencia parcial](docs/verification-lfd-25.md); modelo de tablas pendiente.
- Evidencias fechadas: [LFD-22](docs/verification-lfd-22.md),
  [LFD-23](docs/verification-lfd-23.md), [LFD-24](docs/verification-lfd-24.md)
  y [verificación documental LFD-82](docs/verification-lfd-82.md).

## Alcance actual

Backend Spring Boot y frontend React independientes, pantalla de identificación,
configuración inicial y documentación modular. El modo base no depende de PostgreSQL;
el perfil explícito `postgres` comprueba una conexión real mediante JDBC.
Solo se expone salud técnica mediante `/actuator/health`, sin detalles ni componentes.
No existen endpoints de negocio, login, usuarios, tenants, permisos, RLS ni integraciones.
LFD-25 prepara Flyway como herramienta Maven separada del runtime. La base física
de dominio aún está pendiente de definición; no se ha migrado PostgreSQL real.

## Requisitos y versiones

| Tecnología | Versión seleccionada |
| --- | --- |
| Java | 21 LTS; verificado con JDK 21.0.10 |
| Spring Boot | 4.0.8 |
| PostgreSQL local detectado / cliente psql | 17.2 / 17.2 |
| pgJDBC / HikariCP | 42.7.13 / 7.0.2; gestionados por Spring Boot |
| Maven | 3.9.14, descargado por Maven Wrapper 3.3.4 |
| Node.js | 24 LTS; verificado con 24.14.0; rango admitido `>=24.14.0 <25` |
| npm | 11.12.1; rango admitido 11.x |
| React / React DOM | 19.3.1 |
| TypeScript | 7.0.2 |
| Vite | 8.3.4 |
| Plugin React de Vite | 6.1.2 |
| Tipos React / React DOM / Node | 19.3.0 / 19.3.0 / 24.19.2 |

Dependencias directas fijadas sin rangos; `package-lock.json` fija el árbol npm.
No se seleccionaron dependencias prerelease. La versión SNAPSHOT del artefacto
backend identifica el desarrollo local, no una dependencia prerelease.

Compatibilidad consultada antes de fijar versiones:
[Spring Boot 4.0](https://docs.spring.io/spring-boot/4.0/system-requirements.html)
admite Java 21 y Maven 3.9.14;
[Vite](https://vite.dev/guide/) admite Node 24.
Se consultaron también versiones, `engines` y `peerDependencies` en el registro
npm: el plugin React 6.1.2 admite Vite 8; sus peers adicionales son opcionales.
La resolución real y las compilaciones confirmaron la combinación seleccionada.

Se requiere acceso a Maven Central y al registro npm en la primera instalación.
No hace falta Maven global para ejecutar el proyecto con Wrapper.

## Estructura

```text
backend/
  .mvn/wrapper/          Distribución Maven fijada
  mvnw, mvnw.cmd         Wrapper oficial, variante only-script
  pom.xml
  src/main/java/com/lssoluciones/lscore/LsCoreApplication.java
  src/main/resources/
    application.yml     Configuración común y perfil dev por defecto
    application-test.yml Puerto efímero para arranques técnicos aislados
frontend/
  src/
    app/                Composición, configuración pública y estilos
    components/         Identidad visual reutilizable
    main.tsx            Entrada React
  .env.example          Ejemplo sin secretos
  package.json, package-lock.json
  tsconfig.json, vite.config.ts, index.html
docs/architecture/      Fronteras y convenciones para futuras historias
docs/adr/               Decisiones adoptadas y sus límites
docs/codex/             Guía operativa y plantilla de prompts
```

No se crean directorios vacíos de dominio o rutas. Las suites smoke existentes
están en `backend/src/test/` y junto a App en frontend. Ver las
[fronteras modulares](docs/architecture/modules.md).

## Backend en Windows PowerShell

Abrir una terminal en la raíz. Configurar `JAVA_HOME` con un JDK 21 instalado
(el directorio siguiente es el usado en la verificación; adaptarlo al equipo):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location backend
.\mvnw.cmd --version
.\mvnw.cmd -B -ntp clean verify
.\mvnw.cmd spring-boot:run
```

Alternativa usando el artefacto generado:

```powershell
java -jar target/ls-core-0.0.1-SNAPSHOT.jar
```

Desde otra terminal:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
```

Debe devolver `status: UP`. Finalizar con **Ctrl+C** y esperar
`Graceful shutdown complete`. `clean verify` compila, ejecuta las pruebas smoke
de los perfiles `dev` y `test`, empaqueta y aplica Checkstyle a Java principal y de pruebas.

## Frontend en Windows PowerShell

En una segunda terminal, desde la raíz:

```powershell
Set-Location frontend
npm.cmd ci
# Opcional: personalizar la URL pública de API
Copy-Item .env.example .env.local
npm.cmd run dev
```

Abrir `http://127.0.0.1:5173`. La pantalla debe identificar LS Core Multi-Tenant
v4.0. No hace peticiones al backend ni representa conectividad, usuarios o empresas.
Para detener Vite, usar **Ctrl+C**; si Windows pregunta por el trabajo por lotes,
responder `S`.

```powershell
npm.cmd run typecheck
npm.cmd run build
npm.cmd run preview
```

`build` ya ejecuta `typecheck` y genera `dist/`; `preview` sirve ese build en
`http://127.0.0.1:4173`. El servidor preview no es un servidor de producción.

## Equivalentes POSIX

Con JDK 21 en `JAVA_HOME` y `PATH`, desde la raíz:

```sh
cd backend
sh ./mvnw -B -ntp clean verify
sh ./mvnw spring-boot:run
# Otra terminal: curl --fail http://127.0.0.1:8080/actuator/health
```

En otra terminal desde la raíz:

```sh
cd frontend
npm ci
cp .env.example .env.local # opcional
npm run dev
# Para verificar: npm run build
```

Se usa `sh ./mvnw` para no depender del bit ejecutable al crear el repositorio
desde Windows. Los comandos de verificación de backend/frontend se ejecutaron en
Ubuntu mediante CI de LFD-24; no se verificaron arranques interactivos POSIX ni macOS.

## PostgreSQL local opcional (LFD-23)

Ver la [guía de PostgreSQL en Windows/POSIX](docs/postgresql-local.md) y la
[evidencia LFD-23](docs/verification-lfd-23.md). El arranque anterior sigue siendo
el modo base; no requiere ninguna variable `LS_DB_*`. Para PostgreSQL, configurar
las cinco variables obligatorias `LS_DB_HOST`, `LS_DB_PORT`, `LS_DB_NAME`,
`LS_DB_USERNAME`, `LS_DB_PASSWORD` y activar `postgres` explícitamente.

El script `scripts/Start-PostgresBackend.ps1` solicita la contraseña localmente y
la transmite solo por entorno de proceso, sin archivos ni argumentos de contraseña.
El inicializador inspecciona recursos y requiere confirmación antes de aplicar:

```powershell
.\scripts\Initialize-LocalPostgres.ps1           # Solo inspección y plan
.\scripts\Initialize-LocalPostgres.ps1 -Apply    # Confirmar escribiendo APPLY
# Repetir -Apply para comprobar NO CHANGES, sin restablecer contraseñas
# Añadir -StartBackend para arrancar el perfil postgres con el script existente
```

Usa psql -W y \password para introducir credenciales únicamente en el terminal local.
No modifica servicios Windows, otras bases ni la configuración del servidor.

La aprobación del propietario registrada el 2026-10-16 en
[LFD-23](https://lssoluciones.atlassian.net/browse/LFD-23?focusedCommentId=10023)
confirma PostgreSQL 17.2, base `ls_core_dev`, rol `ls_core_app_dev`, conexión de
aplicación/SELECT 1, segunda inicialización `NO CHANGES` y perfil `postgres` con
HTTP 200 y JSON `status: UP`. Se observó CONNECT efectivo en otras cinco bases;
no se modificaron sus permisos y no se garantiza exclusividad de conexión.
Ver el [ADR 0002](docs/adr/0002-postgresql-y-conectividad.md).

## Configuración por entorno

| Variable | Valor predeterminado | Uso |
| --- | --- | --- |
| `JAVA_HOME` | Depende del equipo | JDK 21 para Wrapper y comandos Java |
| `SPRING_PROFILES_ACTIVE` | `dev` | Seleccionar `dev`, `test`, `prod` o `postgres` explícito |
| `SERVER_ADDRESS` | `127.0.0.1` | Interfaz del backend |
| `SERVER_PORT` | `8080` | Puerto backend; perfil `test` usa `0`, salvo override por variable |
| `SPRING_CONFIG_ADDITIONAL_LOCATION` | Sin valor | Ubicación externa de configuración Spring opcional |
| `VITE_API_BASE_URL` | `http://127.0.0.1:8080` | URL pública de API, leída durante dev/build |

No hay variables sensibles obligatorias en modo base. Spring Boot **no lee** archivos
`.env` automáticamente: usar variables del proceso o configuración externa.
Vite permite `.env.local`, `.env.development.local`, `.env.production.local`
y modos explícitos mediante `--mode`; sus valores `VITE_*` son públicos en el bundle.
Cambiar la URL para producción exige reconstruir el frontend.

Para backend, `application.yml` contiene valores comunes seguros y el perfil
`test` cambia solo el puerto. `prod` selecciona el entorno pero hereda los valores
comunes: sus diferencias reales deben proporcionarse externamente cuando existan.
No se añaden archivos de producción vacíos ni credenciales de ejemplo.

Los servidores escuchan en loopback por defecto, con HTTP solo para desarrollo
local. HTTPS, certificados y exposición productiva quedan pendientes de una historia
de despliegue. No se configura CORS ni proxy: todavía no hay consumo REST desde UI.

## Convenciones

- Backend: raíz `com.lssoluciones.lscore`, Java 21, cuatro espacios.
- Frontend: componentes funcionales, TypeScript estricto, dos espacios.
- Ambos proyectos se construyen y ejecutan por separado, sin orquestador raíz.
- Separar presentación, aplicación, dominio e infraestructura dentro de cada
  módulo cuando aparezcan implementaciones. Evitar ciclos y acoplamiento con LS Fiscal/PV.
- No guardar secretos ni registrarlos en logs. `.gitignore` excluye entornos
  locales, llaves, salidas de build, dependencias e IDEs; `.env.example` sí se versiona.
- No añadir estado global, router, diseño, persistencia o seguridad sin necesidad.
- Spring Security forma parte de la arquitectura futura. No se añade el starter
  en este ticket para evitar usuarios generados y autenticación predeterminada.

## Verificación y limitaciones

Los resultados originales y sus seguimientos fechados están en las verificaciones
de [LFD-22](docs/verification-lfd-22.md), [LFD-23](docs/verification-lfd-23.md)
y [LFD-24](docs/verification-lfd-24.md).
Este scaffold no proporciona aislamiento tenant ni seguridad funcional y no está
preparado para exposición productiva. LFD-24 incorpora las comprobaciones descritas abajo.

LFD-23 incorpora driver JDBC y pool opcionales, sin entidades, migraciones,
credenciales versionadas ni contenedores. La validación autenticada del propietario
está registrada en el seguimiento LFD-23; los intentos antiguos no ejecutados por
Codex se conservan como evidencia histórica. Esa aprobación local no acredita
seguridad multi-tenant, RLS, exclusividad de conexión ni preparación productiva.

## Pruebas, lint y CI (LFD-24)

Desde la raíz, con JDK 21, Node 24 y npm 11:

```powershell
Set-Location backend
.\mvnw.cmd -B -ntp clean verify
# Solo estilo Java:
.\mvnw.cmd -B -ntp checkstyle:check
Set-Location ../frontend
npm.cmd ci
npm.cmd run lint
npm.cmd run test
npm.cmd run typecheck
npm.cmd run build
```

En POSIX, usar `sh ./mvnw` y `npm` en los mismos directorios.
Backend: JUnit y Spring Boot Test prueban arranque, ausencia de infraestructura JDBC,
salud HTTP 200/UP y no exposición de env/beans/configprops en puertos aleatorios.
Frontend: Vitest, React Testing Library y jsdom prueban identidad, estructura accesible
y renderizado sin solicitudes al backend. La pantalla conserva su diseño.

ESLint comprueba JavaScript/TSX y reglas React/hooks con parser Babel independiente
del compilador TypeScript. `lint` incluye `typecheck`: TypeScript 7 estricto comprueba
tipos, símbolos y variables no utilizadas, evitando instalar typescript-eslint con
un peer incompatible. `test` termina tras ejecutar las pruebas, sin modo watch.

[CI](.github/workflows/ci.yml) ejecuta jobs independientes backend/frontend en
Ubuntu 24.04 en push a `feature/**` y PR dirigidos a `main`, con cachés y permisos
`contents: read`. No requiere variables `LS_DB_*`, secretos ni PostgreSQL.
LFD-24 está integrada mediante [PR #2](https://github.com/rialespe/LS_Framework/pull/2),
merge `2c52c544ce2923de72de63a5adcd67eb53c8efa0`; el SHA final de su rama pasó
[CI backend/frontend](https://github.com/rialespe/LS_Framework/actions/runs/38911300657).
Las pruebas de bootstrap PostgreSQL se mantienen locales en Windows:

```powershell
python scripts/tests/test_postgres_bootstrap.py
```

Crean clústeres desechables con los binarios PostgreSQL 17, sin usar el servidor
compartido. No se integran en Linux porque el harness existente fija rutas y
ejecutables Windows; portarlo excedería el alcance del CI inicial.
Ver [resultados, compatibilidad y limitaciones de LFD-24](docs/verification-lfd-24.md).
