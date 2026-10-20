# LFD-31 P0: contrato inicial de identidad global

`identity.application.ManualIdentityInput` es un record inmutable Java 21,
reutilizable sin Spring, JDBC ni dependencias nuevas. Su constructor valida
presencia: `username` y `displayName` son obligatorios; `email = null` representa
ausencia. Un valor suministrado vacío o compuesto solo de espacios en blanco
según `String.isBlank()` se rechaza, también para el correo opcional.
No se introducen reglas de formato, longitud, nombres, proveedores ni estados.

Los tres valores se conservan literalmente, sin recortar, convertir a minúsculas
ni normalizar Unicode. La validación Java de presencia no reproduce `btrim` ni
la collation de PostgreSQL. La única autoridad de unicidad sigue siendo
[V7](../backend/src/main/resources/db/migration/V7__add_normalized_uniqueness_and_indexes.sql):
`users_username_normalized_uq` aplica globalmente `lower(btrim(username))`;
`users_verified_email_normalized_uq` aplica `lower(btrim(email))` solamente
cuando `email_verified_at IS NOT NULL`. La igualdad del record no es una prueba
de disponibilidad ni de equivalencia según esos índices. Construir dos entradas
con valores repetidos es válido; no hay consultas ni reservas de identidad.

`emailVerified()` siempre devuelve `false`, incluso con correo presente.
El constructor no acepta indicadores, fechas ni evidencia de verificación.
El futuro registro deberá mantener `email_verified_at = NULL` para este alta;
verificar requerirá evidencia confiable obtenida y validada por el servidor
mediante un contrato posterior, nunca una declaración del cliente.

Toda entrada rechazada produce `IllegalArgumentException` con el mismo mensaje
`Invalid manual identity input`, sin valores ni causas. No se consulta información
de usuarios existentes. `toString()` oculta los campos; los consumidores deberán
evitar registrar sus accesores. Los futuros errores de persistencia deberán
traducirse a errores seguros sin filtrar existencia, valores ni diagnósticos SQL.

## Dependencias pendientes y alcance

Este componente prepara F04; **F03 sigue siendo precondición**. No declara F04
ni LFD-31 completados. Quedan pendientes el caso de uso persistente y su frontera
transaccional/autorización, la traducción segura de conflictos concurrentes de
los índices V7 y el contrato de evidencia de verificación del servidor.
No se implementan registro persistente, login, contraseñas, sesiones, cookies,
HTTP ni cambios de frontend. V1–V9, RLS, roles y el lector C1–C4 se conservan;
no se reanuda la POC B2-R ni se opera sobre PostgreSQL real o compartido.

## Validación focalizada

Con JDK 21, desde `backend`:

```powershell
.\mvnw.cmd -B -ntp '-Dtest=ManualIdentityInputTest' test checkstyle:check
```

Las pruebas cubren campos obligatorios nulos, vacíos y blancos, correo ausente,
correo suministrado inválido por presencia, conservación literal, correo siempre
no verificado, ausencia de comprobaciones de disponibilidad y diagnósticos seguros.
No levantan Spring ni requieren PostgreSQL. El push final activa una ejecución
del workflow CI existente; su job PostgreSQL utiliza un clúster desechable.
