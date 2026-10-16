# ADR 0001 — Arquitectura base y stack

- Fecha de registro: 2026-10-16.
- Estado: Aceptado; registro retrospectivo de la base incorporada en LFD-22.
- Trazabilidad: [LFD-22](https://lssoluciones.atlassian.net/browse/LFD-22),
  [LFD-2](https://lssoluciones.atlassian.net/browse/LFD-2),
  [LFD-82](https://lssoluciones.atlassian.net/browse/LFD-82).
- Alcance: estructura técnica existente y convenciones modulares; no dominios futuros.

## Contexto

LS Core necesita una base reproducible común para desarrollar su administración
de forma secuencial. F01 no contiene reglas de negocio ni un sistema multi-tenant
funcional. La organización debe permitir crecer sin introducir servicios,
dependencias entre aplicaciones o paquetes vacíos sin una responsabilidad real.

## Decisión

Mantener un monorepo con backend y frontend independientes:

| Área | Decisión adoptada | Fuente |
| --- | --- | --- |
| Backend | Java 21 LTS, Spring Boot 4.0.8, un artefacto Maven | [pom.xml](../../backend/pom.xml) |
| Construcción Java | Maven 3.9.14, Wrapper 3.3.4 | [Wrapper](../../backend/.mvn/wrapper/maven-wrapper.properties) |
| Frontend | React/React DOM 19.3.1, TypeScript 7.0.2, Vite 8.3.4 | [package.json](../../frontend/package.json) |
| Entorno frontend | Node 24 LTS (`>=24.14.0 <25`), npm 11; packageManager 11.12.1 | [package.json](../../frontend/package.json) |
| Tipos y resolución | TypeScript estricto y dependencias bloqueadas | [tsconfig](../../frontend/tsconfig.json), [lock](../../frontend/package-lock.json) |

El backend es un monolito modular: una aplicación Spring Boot bajo
`com.lssoluciones.lscore`, sin submódulos Maven ni servicios distribuidos. El
frontend React tiene su propia construcción y arranque; hoy presenta una pantalla
independiente y no consume una API de negocio.

Se conserva la [organización por módulos](../architecture/modules.md). Cuando
exista contenido, cada módulo puede separar presentación, aplicación, dominio e
infraestructura. Las colaboraciones deben usar contratos explícitos de aplicación,
sin acceder a repositorios o clases internas de otro módulo, y mantener el grafo
acíclico. Estas son convenciones acordadas; no existen todavía pruebas automáticas
que impongan esas fronteras. Solo está materializada la infraestructura JDBC
opcional en `operations/infrastructure/jdbc`, además del arranque técnico.

## Justificación

Un único artefacto backend reduce la coordinación de despliegues en esta etapa.
Las fronteras por módulo permiten ordenar futuras responsabilidades dentro de ese
artefacto. Separar React del backend permite comprobar y desarrollar la pantalla
sin conexión JDBC ni servidor Java. El Wrapper y el lock reducen diferencias de
herramientas y resolución entre estaciones y CI. Java 21 LTS y Node 24 LTS son las
bases fijadas por el proyecto; este registro no propone actualizarlas.

## Alternativas y consecuencias

Las alternativas se registran para explicar el alcance actual; no se afirma una
evaluación experimental o comparativa que no consta en F01.

| Alternativa no adoptada | Motivo frente a la decisión actual |
| --- | --- |
| Microservicios o múltiples artefactos backend | Añadirían coordinación operativa sin dominios implementados que lo justifiquen |
| Servir y construir toda la UI dentro de Maven | Acoplaría ciclos de trabajo que hoy son independientes |
| Crear desde ahora todos los paquetes de negocio | Produciría estructuras sin implementación ni contratos verificables |
| Actualizar versiones sin ticket específico | Cambiaría la base validada sin una necesidad de F01 |

Se requieren dos entornos de construcción y disciplina de contratos. Un monolito
no garantiza modularidad por sí solo. No hay aislamiento tenant, autenticación,
autorización ni preparación productiva. Los módulos previstos en la guía son
orientaciones futuras, no componentes entregados ni nuevas aprobaciones de diseño.
Flyway, RLS, tenants/sucursales, identidad y DTE/SII quedan para sus historias.

Evidencia: [LFD-22](../verification-lfd-22.md),
[calidad LFD-24](../verification-lfd-24.md). Complementan esta decisión los
[ADR 0002](0002-postgresql-y-conectividad.md) y
[0003](0003-calidad-ci-y-flujo-git.md).
