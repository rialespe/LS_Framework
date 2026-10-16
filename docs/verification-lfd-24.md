# Verificación LFD-24

Fecha: 2026-10-16. Rama: `feature/LFD-24-agregar-pruebas-smoke-lint-y-ci-inicial`.
Base actualizada de `origin/main`: `576523e20ef43f4813184fda6b183cf8c59666d9`.
Repositorio limpio antes de crear/publicar la rama, sin rama LFD-24 previa.
La rama se publicó antes de editar código. Sin PR, merge ni cambios de estado Jira.

## Archivos

Nuevos:

- `.github/workflows/ci.yml`.
- `backend/checkstyle.xml`.
- `backend/src/test/java/com/lssoluciones/lscore/AbstractBackendSmokeTest.java`.
- `backend/src/test/java/com/lssoluciones/lscore/DevProfileSmokeTest.java`.
- `backend/src/test/java/com/lssoluciones/lscore/TestProfileSmokeTest.java`.
- `frontend/eslint.config.js`.
- `frontend/src/test/setup.ts`.
- `frontend/src/app/App.test.tsx`.
- `docs/verification-lfd-24.md`.

Modificados: `backend/pom.xml`, `frontend/package.json`, `frontend/package-lock.json`,
`frontend/vite.config.ts`, `frontend/src/main.tsx` (solo llaves exigidas por lint),
y `README.md`. Sin cambios en App, estilos, configuración JDBC/Actuator ni scripts SQL.

## Herramientas y compatibilidad

- JDK local 21.0.10 explícito; Java predeterminado del equipo es 25 y no se usó para Maven.
- Spring Boot starter-test 4.0.8 gestionado por el parent: JUnit Jupiter, AssertJ y JsonPath.
  `@SpringBootTest(RANDOM_PORT)` arranca HTTP real; HttpClient de Java 21 evita
  dependencias de clientes de prueba específicos y puertos fijos.
- Checkstyle Maven Plugin 3.6.0 con motor 10.26.1 compatible con sintaxis Java 21.
  Reglas acotadas: imports, nombres, sangría de cuatro espacios, llaves, sentencias
  vacías, tabulaciones y fin de archivo; aplica a fuentes principales y pruebas.
  El goal `check` está enlazado a `verify` y falla ante infracciones.
- Node 24.14.0, npm 11.12.1. Stack existente sin cambios de versión.
- Vitest 5.0.5 declara peer Vite `^6.4 || ^7 || ^8` y Node 24; Testing Library React
  16.3.3 admite React 19. DOM 10.4.2 y jest-dom 7.0.1 complementan las aserciones.
- jsdom 29.1.1 admite Node 24.14; jsdom 30.1.3 exige al menos 24.15 y fue descartado.
- ESLint 10.12.0, @eslint/js 10.0.1, parser/core Babel 8.0.7, hooks 7.1.1 y
  globals 17.13.0. Dependencias directas exactas y lock actualizado sin `--force`
  ni `--legacy-peer-deps`; ninguna versión previamente bloqueada cambió.
- typescript-eslint 8.71.2 declara TypeScript `>=4.8.4 <6.1.0`; no se instaló.
  Babel analiza TS/TSX sin cargar el compilador TS. Su scope manager produce falsos
  positivos con JSX y declaraciones de tipos en `no-undef`/`no-unused-vars`; solo
  esas dos reglas se delegan, en archivos TS/TSX, a `tsc --noEmit` estricto con
  `noUnusedLocals` y `noUnusedParameters`. `npm run lint` ejecuta ambas herramientas.
  No hay lint con información de tipos; la verificación de tipos sigue separada
  y también forma parte de lint/build. JavaScript conserva las reglas recomendadas.

Referencias: [Spring Boot testing](https://docs.spring.io/spring-boot/reference/testing/),
[Checkstyle Maven](https://maven.apache.org/plugins/maven-checkstyle-plugin/usage.html),
[compatibilidad typescript-eslint](https://typescript-eslint.io/users/dependency-versions/),
[parser Babel](https://babeljs.io/docs/babel-eslint-parser/).
También se consultaron `engines` y `peerDependencies` reales mediante `npm view`.

## Ejecución reproducible

PowerShell, desde la raíz:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10' # adaptar al equipo
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location backend
.\mvnw.cmd -B -ntp clean verify
.\mvnw.cmd -B -ntp checkstyle:check # comprobación individual opcional
Set-Location ../frontend
npm.cmd ci
npm.cmd run lint
npm.cmd run test
npm.cmd run typecheck
npm.cmd run build
Set-Location ..
python scripts/tests/test_postgres_bootstrap.py # opcional; binarios PG17 Windows
```

POSIX, desde la raíz con JDK 21 en `JAVA_HOME`/`PATH` y Node/npm indicados:

```sh
(cd backend && sh ./mvnw -B -ntp clean verify)
(cd backend && sh ./mvnw -B -ntp checkstyle:check)
(cd frontend && npm ci && npm run lint && npm run test && npm run typecheck && npm run build)
```

No se requieren variables `LS_DB_*`, credenciales ni backend activo para frontend.
Los tests activan explícitamente `dev`/`test`; ejecutarlos en un entorno sin
overrides externos de Spring/Actuator. `VITE_API_BASE_URL` es opcional y pública.
`dev`/`preview` se mantienen. Los resultados Windows no implican ejecución POSIX.

## Resultados locales reales

| Validación | Comando | Resultado | Evidencia |
| --- | --- | --- | --- |
| Compilación Java 21 | `backend: .\mvnw.cmd -B -ntp clean verify` | OK | javac release 21, JDK 21.0.10; 2 fuentes principales y 3 de pruebas |
| Smoke backend | mismo verify | OK | Surefire: 10 pruebas, 0 fallos/errores/omitidas; 5 por perfil |
| Modo base sin PostgreSQL | mismo verify | OK | Contextos dev/test sin DataSource ni postgresHealthIndicator; HTTP salud 200/UP |
| Actuator restringido | mismo verify | OK | env/beans/configprops HTTP 404 en ambos perfiles; salud sin details/components |
| Lint Java y Maven verify | mismo verify | OK | 0 Checkstyle violations; BUILD SUCCESS, 15.498 s |
| Instalación limpia frontend | `npm.cmd ci` | OK | 257 paquetes instalados; 0 vulnerabilidades reportadas |
| ESLint y variables sin usar | `npm.cmd run lint` | OK | ESLint sin warnings y tsc sin diagnósticos |
| Smoke frontend | `npm.cmd run test` | OK | 1 archivo, 3 pruebas aprobadas; identidad, landmarks y cero fetch/XHR |
| TypeScript estricto | `npm.cmd run typecheck` | OK | tsc --noEmit, salida 0; tsconfig conservado |
| Build Vite | `npm.cmd run build` | OK | Vite 8.3.4; 18 módulos, dist generado en 143 ms |
| Rechazo Checkstyle | `mvnw.cmd -B -ntp checkstyle:check` con fixture temporal | OK | Import wildcard rechazado con AvoidStarImport, salida 1 esperada |
| Rechazo variable sin usar | `npm.cmd run lint` con fixture temporal | OK | TS6133, salida 1 esperada |
| Rechazo hook inválido | `npm.cmd run lint` con fixture temporal | OK | react-hooks/rules-of-hooks, salida 1 esperada |
| Sintaxis/estructura CI | `actionlint -shellcheck= .github/workflows/ci.yml` | OK | actionlint v1.7.12, checksum SHA256 del binario verificado; salida 0 |
| CI real Ubuntu, backend | `sh ./mvnw -B -ntp clean verify` | OK | [Run 38911153011](https://github.com/rialespe/LS_Framework/actions/runs/38911153011), job Backend - Java 21: success |
| CI real Ubuntu, frontend | `npm ci`, lint, test, typecheck, build | OK | Mismo run, job Frontend - Node 24 y todos sus pasos: success |
| Bootstrap PostgreSQL aislado | `python scripts/tests/test_postgres_bootstrap.py` | OK | Escenarios nuevo/adopción, rechazo/rollback, permisos, idempotencia y ausencia de contraseña en logs; servidor compartido no utilizado |
| JDBC positivo sobre servidor compartido | no ejecutado | NO EJECUTADA | No se solicitan ni usan credenciales reales |
| Comparación visual en navegador | no ejecutada | NO EJECUTADA | App y CSS sin modificaciones; comprobación estructural DOM aprobada |

Los fixtures negativos se eliminaron en finally y no se versionan. Los fallos
esperados de esos fixtures prueban que las puertas de calidad rechazan errores.

## CI y seguridad

Workflow `CI`: PR hacia `main` y push a `feature/**`. Dos jobs independientes
`backend` y `frontend` en `ubuntu-24.04`, timeout 15 minutos; cachés por POM/lock,
concurrencia por workflow/ref y cancelación de ejecuciones sustituidas.
Java Temurin 21 + Maven Wrapper; Node 24.14.0 + npm fijado 11.12.1.
Acciones checkout v6, setup-java v5 y setup-node v6 resueltas contra sus tags
remotos y fijadas por SHA; checkout sin persistir credenciales. Permiso global
`contents: read`, sin pull_request_target, secretos, servicios DB ni despliegues.
Cada comando falla el job si su salida es distinta de cero.

El harness PostgreSQL LFD-23 fija rutas `C:\Program Files\PostgreSQL\17\bin` y
ejecutables `.exe`. Se mantiene intacto y documentado para Windows. Portarlo a
Linux o agregar infraestructura Windows/PG en CI incrementaría el alcance inicial;
por eso no se añade un tercer job. Su ejecución local aislada sí fue verificada.

Ejecución real [38911153011](https://github.com/rialespe/LS_Framework/actions/runs/38911153011)
en el commit `1e66564c54a6b772252cc86d1ea81c00920dbe54`: `completed / success`.
Ambos jobs y todos sus pasos concluyeron `success`, consultado mediante la API
GitHub Actions. Este resultado verifica también los comandos POSIX del workflow.
El commit posterior de documentación vuelve a disparar el mismo CI; su estado
final se comunica en el informe de entrega para evitar referencias circulares
al SHA del propio documento. No se abrió Pull Request.

## Problemas y límites

- Primera prueba de salud demasiado restrictiva: Boot incluye `groups` públicos
  liveness/readiness además de status. La aserción se corrigió para exigir UP y
  ausencia de details/components, conservando la configuración de Actuator.
- Primer lint detectó limitaciones de scope de Babel y falta de llaves en main.tsx;
  se corrigieron como se describe arriba. ESLint 9 se descartó tras advertencia
  de versión sin soporte; se usa ESLint 10 y no se fuerza ningún peer.
- Spring Test emite advertencias por autoattach de Mockito/Byte Buddy en JDK 21,
  aunque esta suite no usa mocks. No impiden verify; no se ocultaron advertencias.
- Suite smoke acotada: no valida funciones de negocio, autenticación, aislamiento
  tenant ni JDBC autenticado real. No añade funcionalidad fuera de LFD-24.
- Sin cambios en SQL, scripts de inicialización, perfil postgres o configuración
  segura Actuator. Ninguna operación sobre PostgreSQL real, main ni credenciales.
