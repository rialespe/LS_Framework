# LS Core Multi-Tenant v4.0

Base técnica del framework central de administración de LS Soluciones SpA.
Implementación acotada a **LFD-22**, de la historia LFD-2 y épica LFD-1.
El repositorio se encontró vacío; no había convenciones ni código que migrar.

## Alcance actual

Backend Spring Boot y frontend React independientes, pantalla de identificación,
configuración inicial y documentación modular. El backend no depende de PostgreSQL.
Solo se expone salud técnica mediante `/actuator/health`, sin detalles ni componentes.
No existen endpoints de negocio, login, usuarios, tenants, permisos, RLS ni integraciones.

## Requisitos y versiones

| Tecnología | Versión seleccionada |
| --- | --- |
| Java | 21 LTS; verificado con JDK 21.0.10 |
| Spring Boot | 4.0.8 |
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
```

No se crean directorios vacíos de dominio, rutas ni pruebas. Se incorporarán
cuando exista una implementación que los justifique. Ver las
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
`Graceful shutdown complete`. `clean verify` compila y empaqueta; todavía
no hay una suite de pruebas automatizadas. No implica validación funcional.

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
desde Windows. Los comandos POSIX se documentan como equivalentes; no se probaron
en Linux/macOS.

## Configuración por entorno

| Variable | Valor predeterminado | Uso |
| --- | --- | --- |
| `JAVA_HOME` | Depende del equipo | JDK 21 para Wrapper y comandos Java |
| `SPRING_PROFILES_ACTIVE` | `dev` | Seleccionar `dev`, `test` o `prod` |
| `SERVER_ADDRESS` | `127.0.0.1` | Interfaz del backend |
| `SERVER_PORT` | `8080` | Puerto backend; perfil `test` usa `0`, salvo override por variable |
| `SPRING_CONFIG_ADDITIONAL_LOCATION` | Sin valor | Ubicación externa de configuración Spring opcional |
| `VITE_API_BASE_URL` | `http://127.0.0.1:8080` | URL pública de API, leída durante dev/build |

No hay variables sensibles obligatorias en LFD-22. Spring Boot **no lee** archivos
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

Los resultados concretos y los impedimentos están en
[verificación de LFD-22](docs/verification-lfd-22.md).
Este scaffold no proporciona aislamiento tenant ni seguridad funcional y no está
preparado para exposición productiva. Lint, suite de pruebas y CI corresponden a LFD-24.

LFD-23 podrá añadir PostgreSQL de desarrollo y configuración segura sobre esta
base. Aquí no hay drivers JDBC, entidades, migraciones, credenciales ni contenedores.
