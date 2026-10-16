# Verificación LFD-22

## Seguimiento documental LFD-82 — 2026-10-16

El contenido original de abajo conserva lo observado el 2026-10-15, incluidos
sus pendientes. No describe el estado actual completo: Jira confirma LFD-22,
LFD-23 y LFD-24 Listo; PostgreSQL/JDBC se validó posteriormente según
[seguimiento LFD-23](verification-lfd-23.md), y smoke/lint/CI se incorporaron según
[LFD-24](verification-lfd-24.md), integrada mediante
[PR #2](https://github.com/rialespe/LS_Framework/pull/2).
Esta nota no convierte pruebas antiguas no ejecutadas en pruebas realizadas;
la comprobación DOM de LFD-24 tampoco equivale a una inspección visual en navegador.

## Evidencia original

Fecha local: 2026-10-15, Windows PowerShell, repositorio inicialmente vacío en
`main`, sin commits y sin cambios previos. No se hizo commit, push, despliegue
ni modificación de Jira.

## Resultados observados

| Verificación | Resultado | Evidencia |
| --- | --- | --- |
| Maven Wrapper | OK | Wrapper 3.3.4 descarga y ejecuta Maven 3.9.14 con Java 21.0.10 |
| Backend `clean verify` | OK | `BUILD SUCCESS`; una clase compilada con `release 21`; JAR ejecutable generado |
| Suite automatizada backend | NO EJECUTADA | No hay tests; Maven informa `No tests to run`; LFD-24 ampliará controles |
| Arranque Spring Boot | OK | Boot 4.0.8, perfil dev, Tomcat 8080; `Started LsCoreApplication in 2.763 seconds` |
| Arranque sin PostgreSQL | OK | No se suministraron credenciales ni configuración de BD; no hay driver, JPA ni Spring Data |
| Salud técnica | OK | `GET /actuator/health`: HTTP 200, `status: UP`, sin componentes ni detalles |
| Superficie técnica | OK | `/actuator/env`, `/actuator/beans` y `/login`: HTTP 404 |
| Cierre backend | OK | Ctrl+C; `Graceful shutdown complete`; puerto 8080 sin listener después |
| Instalación npm | OK | `npm install` y después `npm ci`; 26 paquetes instalados, 0 vulnerabilidades reportadas |
| Resolución frontend | OK | `npm ls --depth=0` sin errores; lock sin versiones prerelease |
| TypeScript estricto | OK | `npm run build` ejecutó `tsc --noEmit`, salida 0 |
| Build frontend | OK | Vite 8.3.4: 18 módulos, `built in 162ms`, archivos en dist |
| Vite independiente | OK | Listo en 213 ms, antes de arrancar backend; `/` y `/src/main.tsx` devuelven HTTP 200 |
| Pantalla en navegador / consola | NO EJECUTADA | Se intentó abrir navegador local; herramienta devuelve `Browser is not available: iab`; inventario sin navegadores ni apps |
| Cierre frontend | OK | Ctrl+C y confirmación S del proceso por lotes; puerto 5173 sin listener después |
| Exclusiones Git | OK | `git check-ignore` confirma target, node_modules, dist, .env.local y application-local.yml; .env.example no se ignora |
| Comandos POSIX | NO EJECUTADA | Documentados; entorno disponible Windows |

La respuesta de salud también enumera los grupos técnicos `liveness` y `readiness`;
no publica información de configuración. No equivale a validación de seguridad,
tenant, integración frontend/backend ni aptitud de producción.

## Comandos relevantes ejecutados

Desde backend, con `JAVA_HOME` apuntando al JDK 21 y su bin en PATH:

```powershell
mvn.cmd -B -ntp org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper '-Dtype=only-script' '-Dmaven=3.9.14'
.\mvnw.cmd --version
.\mvnw.cmd -B -ntp clean verify
java -jar target/ls-core-0.0.1-SNAPSHOT.jar
.\mvnw.cmd -B -ntp dependency:tree '-Dincludes=org.springframework.security:*,org.springframework.data:*,org.postgresql:*,org.hibernate.orm:*'
```

El árbol filtrado no mostró dependencias de seguridad, persistencia ni PostgreSQL.
Desde frontend:

```powershell
npm.cmd install
npm.cmd ci
npm.cmd run build
npm.cmd run dev
npm.cmd ls --depth=0
```

Desde la raíz se consultaron salud HTTP, rutas no expuestas y exclusiones Git:

```powershell
Invoke-WebRequest http://127.0.0.1:8080/actuator/health -UseBasicParsing
Invoke-WebRequest http://127.0.0.1:5173/ -UseBasicParsing
Invoke-WebRequest http://127.0.0.1:5173/src/main.tsx -UseBasicParsing
git status --short --untracked-files=all
git check-ignore backend/target/probe frontend/node_modules/probe frontend/dist/probe frontend/.env.local backend/src/main/resources/application-local.yml
git check-ignore frontend/.env.example
```

La última consulta devuelve código 1 porque el ejemplo no está ignorado, resultado
esperado. Se revisan los nuevos archivos con `git diff --no-index --check` contra
`/dev/null` porque todavía no existe HEAD.

## Incidencias y pendientes

- El primer intento de generar Wrapper falló porque PowerShell dividió un
  argumento `-D` sin comillas. Se repitió con argumentos entrecomillados y terminó
  correctamente; Wrapper y compilación posterior verificados.
- `JAVA_HOME` del equipo apuntaba inicialmente a Java 8 aunque `java` en PATH era
  Java 25. Se seleccionó JDK 21 solo para los procesos de verificación, sin cambiar
  configuración global del equipo.
- La terminación por Ctrl+C dejó código 1 en las sesiones de PowerShell; los logs
  confirman cierre graceful de Spring y ambos puertos quedaron libres.
- Falta validar visualmente la pantalla y la consola en un navegador conectado.
  No se presenta la respuesta HTTP como evidencia de renderizado React.
- El perfil test y los overrides por entorno se han revisado en configuración;
  no se ha ejecutado una matriz de entornos ni un despliegue productivo.

Recomendación: revisión técnica del scaffold, con validación visual pendiente antes
de cerrar LFD-22. LFD-23 no se ha implementado.
