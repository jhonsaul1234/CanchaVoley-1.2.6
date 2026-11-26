# CanchaVoley — instalación del servidor (Laravel + MySQL)

El servidor guarda la información central: clientes, reservas, pagos y configuración.
La app de Windows y la app Android se conectan a él y, sin internet, guardan lo hecho en
su propio SQLite y lo envían después.

> **Base de datos:** el servidor trabaja **solo** con la base `cancha_voley`.
> Las bases `prolech`, `prolech_db` y `prolech_pass` están protegidas: si el `.env` apuntara
> a una de ellas, `migrate`, `db:seed`, `db:wipe`, `migrate:fresh` y comandos similares se
> detienen con un error antes de tocar nada (ver `AppServiceProvider::verificarBaseProtegida`).

## 1. Requisitos

- Laragon con la misma versión de PHP que ya usa ProLech (el `vendor/` exige PHP 8.5).
- MySQL 8 (el de Laragon).
- La carpeta `CanchaVoley\cancha-backend` con su `vendor\` (ya está instalada).

## 2. Revisar el `.env`

Abre `cancha-backend\.env` y confirma estas líneas (no las copies de ProLech):

```
APP_URL=http://localhost:8001
APP_TIMEZONE=America/Lima
DB_CONNECTION=mysql
DB_HOST=127.0.0.1
DB_PORT=3306
DB_DATABASE=cancha_voley
```

## 3. Crear la base (si todavía no existe) y respaldarla

En HeidiSQL o en la consola de MySQL de Laragon:

```sql
CREATE DATABASE IF NOT EXISTS cancha_voley CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Antes de migrar, guarda un respaldo (no borra nada):

```
mysqldump -u root cancha_voley > respaldo_cancha_voley_antes_de_migrar.sql
```

## 4. Crear las tablas

**Forma recomendada (un solo paso):** doble clic en `scripts\actualizar_base.cmd`, o en PowerShell:

```
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\actualizar_base.ps1
```

El script se detiene si el `.env` no apunta a `cancha_voley`; luego ejecuta `php artisan test`
(SQLite en memoria, no toca MySQL), muestra `migrate:status` y `migrate --pretend`, guarda un
respaldo con `mysqldump` en `respaldos\` (carpeta que no se sube a Git; la clave no se muestra)
y **solo migra si escribes SI**. Con `-SoloRevisar` no respalda ni migra nada.

Forma manual, desde `CanchaVoley\cancha-backend`:

```
php artisan migrate:status
php artisan migrate --pretend
php artisan migrate
```

- `migrate:status` muestra qué migraciones faltan; `--pretend` muestra el SQL sin ejecutarlo.
- Las migraciones de CanchaVoley (`database/migrations/2026_11_16_1000NN_*`) **solo crean**
  11 tablas nuevas; no borran ni modifican tablas existentes. Ver `docs/BASE_DE_DATOS.md`.
- En la PC del negocio, la etapa 0 ya dejó aplicadas 67 migraciones (43 tablas heredadas de
  ProLech); ahora `migrate` agrega solo las 11 de CanchaVoley.

## 5. Crear el negocio, la primera cancha y la cuenta de la administradora

```
php artisan canchavoley:instalar --negocio="Nombre del negocio" --cancha="Cancha 1" --admin-nombre="Nombre de la administradora" --admin-email="correo@ejemplo.com" --admin-password="UnaClaveSegura"
```

- Es seguro repetirlo: si el negocio, la cancha o el usuario ya existen, no los duplica.
- **No** crea horarios, tarifas, adelantos ni reglas: la administradora los ingresa desde la
  app (Tarifas y horarios). Mientras no haya tarifa, al reservar se pide el precio acordado.
- La contraseña no queda guardada en ningún archivo; anótala en un lugar seguro.
- Usa un correo propio del negocio y una clave nueva: el comando rechaza los correos
  `@demo.canchavoley` y `@prolech.com` y la clave pública de la demostración.

### Seguridad de las cuentas (demostración y cuentas heredadas)

- Las cuentas de la demostración (`@demo.canchavoley`) tienen una clave pública. El servidor solo
  las deja entrar cuando trabaja con una base de demostración (nombre terminado en `_demo`); con la
  base real responde «Las cuentas de demostración solo funcionan con el servidor de demostración».
- Nadie puede crear cuentas con esos correos en la base real ni usar la clave de la demostración
  al crear una cuenta o al cambiar una clave (app de escritorio, registro desde Android e
  `canchavoley:instalar`).
- La base real `cancha_voley` conserva cuentas de ejemplo heredadas de ProLech (`@prolech.com`,
  creadas el 15/11/2026). CanchaVoley no las deja entrar y no aparecen en la lista de cuentas del
  personal. Para revisarlas: `php artisan canchavoley:cuentas-heredadas`. Para desactivarlas (no se
  borran; se pide confirmar): `php artisan canchavoley:cuentas-heredadas --desactivar`, solo con la
  autorización del responsable de la base y después de un respaldo.

## 6. Encender el servidor

Lo más simple es el script (revisa la carpeta y el `.env`, enciende el MySQL de Laragon si está
apagado, avisa si el puerto está ocupado o faltan migraciones y muestra las direcciones):

- Doble clic en `scripts\iniciar_servidor.cmd` (base real `cancha_voley`).
- Para celulares de la misma Wi-Fi: `powershell -ExecutionPolicy Bypass -File .\scripts\iniciar_servidor.ps1 -Red`
  (con la demostración: doble clic en `scripts\iniciar_servidor_demo_red.cmd`). La ventana muestra la
  dirección para escribir en la app del celular (engranaje «Servidor»).
- Para una **demostración con datos ficticios**: primero `scripts\preparar_demo.cmd` (crea la base
  `cancha_voley_demo` y carga datos inventados; nunca toca `cancha_voley`) y luego
  `scripts\iniciar_servidor_demo.cmd`. Para volver a dejar la demostración como al inicio:
  `scripts\reiniciar_demo.cmd` (pide escribir SI). Cuentas de la demostración, con clave
  `Demo2026` (solo sirven con el servidor de demostración; ver «Seguridad de las cuentas»):
  `admin@demo.canchavoley`, `recepcion@demo.canchavoley`, `cliente@demo.canchavoley`.
  La demostración trae 11 reservas para los próximos días y 14 ya jugadas en las dos semanas
  anteriores (para que Reportes y la caja de días pasados tengan datos). Después de reiniciarla,
  las apps piden volver a entrar y, al hacerlo, descartan solas su copia local vieja.

A mano, para usarlo solo en la PC:

```
php artisan serve --port=8001
```

Para que también se conecten los celulares de la misma red Wi-Fi:

```
php artisan serve --host=0.0.0.0 --port=8001
```

- En la app de escritorio, el servidor es `http://127.0.0.1:8001` (viene así por defecto).
- En el celular, usa la IP de la PC: ejecuta `ipconfig` y busca «Dirección IPv4»
  (por ejemplo `http://192.168.1.10:8001`). Se escribe en la pantalla de inicio de sesión,
  en «Servidor».
- Si Windows pregunta por el firewall, permite el acceso en **redes privadas**.

### Recomendado si varias personas reservan a la vez

`php artisan serve` atiende una petición a la vez en Windows. Funciona bien para un negocio
pequeño, pero para varios equipos y celulares al mismo tiempo conviene publicar la carpeta
`cancha-backend\public` con el Apache de Laragon (Menú → Apache → sites-enabled), en el
puerto 8001. Las reglas contra reservas y pagos duplicados funcionan igual en ambos casos,
porque están en la base de datos (transacciones con bloqueo e identificadores únicos).

## 7. Copias de seguridad

Programa un respaldo diario de la base (por ejemplo con el Programador de tareas):

```
mysqldump -u root cancha_voley > D:\respaldos\cancha_voley_%date:~-4%%date:~3,2%%date:~0,2%.sql
```

## 8. Problemas frecuentes

| Mensaje | Qué significa | Qué hacer |
|---|---|---|
| «La base `prolech…` está protegida» | El `.env` apunta a una base de ProLech | Corrige `DB_DATABASE=cancha_voley` |
| 401 / «Tu sesión venció» | Token vencido o cerrado en otro equipo | Inicia sesión de nuevo |
| 409 «Ese horario ya está ocupado» | Otra reserva (o un cierre) usa ese horario | Elige una de las sugerencias |
| 409 «Ese número de operación ya fue registrado» | Yape/Plin ya usado en otro pago | Revisa el voucher; no se duplica |
| El celular no conecta | IP o firewall | Usa la IP de `ipconfig`, `--host=0.0.0.0` y permite redes privadas |
| «Address already in use» en el puerto 8001 | Un Ctrl+C anterior dejó vivo `php.exe` | Cierra los `php.exe` cuya línea de comando contiene `8001` (Administrador de tareas → Detalles) |
