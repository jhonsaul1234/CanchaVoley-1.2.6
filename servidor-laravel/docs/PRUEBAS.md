# CanchaVoley — pruebas del servidor

## Pruebas automáticas

```
php artisan test                              # todo el backend (ProLech + CanchaVoley)
php artisan test tests/Feature/CanchaVoley    # solo CanchaVoley
php vendor/bin/phpunit tests/Feature/CanchaVoley --testdox   # con el nombre de cada caso
```

Usan SQLite en memoria (`phpunit.xml`), con datos ficticios de `EscenarioCancha`
(horario 08:00–22:00 y tarifas de prueba S/ 30 y S/ 40). **No tocan MySQL** ni ninguna base real.

| Archivo | Casos | Qué comprueba |
|---|---|---|
| `ReservasTest` | 24 | Choques (409 con sugerencias), duraciones distintas, margen, fuera de horario, turno que pasa la medianoche, cierres, mismo `uuid` (también dos envíos a la vez), reglas de duración, precio al cruzar franjas, solicitud del cliente pendiente hasta aprobar, rechazo, cancelación y reprogramación, QR y asistencia, adelanto obligatorio y su vencimiento, cambio del precio acordado (solo administración, queda en el historial), notas editables por recepción, no se edita una reserva cancelada. |
| `PagosTest` | 10 | Efectivo/Yape/Plin y saldo, reintento sin duplicar (también dos envíos a la vez), número de operación repetido, anulación solo por la administradora, devolución ≤ lo cobrado, saldo a favor, aviso por efectivo repetido, el cliente no registra pagos, caja del día. |
| `SincronizacionTest` | 9 | Operaciones sin conexión en orden, reserva offline que choca → `rechazada` con su pago como saldo a favor, cliente offline con teléfono repetido, solicitud offline del cliente queda pendiente, permisos, operación mal formada, cursor de cambios, cambio de precio y notas hecho sin conexión (`editar_reserva`). |
| `SesionYPermisosTest` | 9 | Registro de clientes, vínculo por teléfono, login por dispositivo, roles de ProLech sin acceso, permisos de recepción, gestión de usuarios, búsqueda por teléfono, mismo cliente registrado a la vez desde dos equipos. |
| `ConfiguracionYReportesTest` | 9 | Horarios y tarifas, reglas vacías hasta configurarlas, cierres, segunda cancha, disponibilidad y recomendaciones, reportes, instalador sin duplicar, **bases de ProLech protegidas**. |

## Pruebas de reservas y pagos simultáneos

Necesitan el servidor encendido y un usuario de recepción o administración. Crean registros
reales en la base a la que apunta el `.env`: ejecútalas **antes de empezar a usar el sistema**,
con una fecha futura y un cliente de prueba, y luego cancela esa reserva desde la app.

```
php scripts/prueba_concurrencia.php --url=http://127.0.0.1:8001 --email=CORREO --password=CLAVE --cancha=1 --cliente=ID_CLIENTE_PRUEBA --inicio=2030-01-15T10:00:00-05:00 --fin=2030-01-15T11:00:00-05:00 --n=10 --precio=1
php scripts/prueba_pagos_duplicados.php --url=http://127.0.0.1:8001 --email=CORREO --password=CLAVE --reserva=UUID_DE_LA_RESERVA --n=10
```

Resultado correcto: exactamente **una** respuesta 201 y el resto 409 (reservas). Con
`--mismo-uuid` las N solicitudes reenvían la misma reserva: una 201 y el resto 200. Para pagos,
un solo pago guardado aunque se reintente o se repita el número de operación.
En Windows, `php artisan serve` atiende de a una petición: para una prueba con paralelismo
real usa el Apache de Laragon (ver INSTALACION.md).

## Resultados obtenidos (16/11/2026)

En el entorno de trabajo de Claude (Linux, PHP 8.3 con ajustes temporales de compatibilidad
que **no** están en el repositorio; SQLite en lugar de MySQL):

| Prueba | Resultado |
|---|---|
| Suite completa (`php artisan test`) | 236 de 236, 1120 aserciones |
| CanchaVoley (`tests/Feature/CanchaVoley`) | 61 de 61, 345 aserciones (Reservas 24, Pagos 10, Sincronización 9, Sesión y permisos 9, Configuración y reportes 9) |
| 10 / 20 / 25 reservas simultáneas al mismo horario (servidor con 8 procesos) | 1 creada + 9 / 19 / 24 rechazadas con 409; ninguna 500 |
| 20 envíos simultáneos de la **misma** reserva (`--mismo-uuid`), 3 veces | 1 creada (201) + 19 respuestas 200 con la misma reserva; en la base, ningún `uuid` repetido ni horarios cruzados |
| 15 envíos del mismo pago (mismo `uuid`) | 1 creado (201) + 14 respuestas 200 con el mismo pago |
| 15 pagos Yape con el mismo número de operación | 1 creado + 14 rechazados con 409; 2 pagos en la base (el del caso anterior y este) |

Corrección encontrada al revisar estas pruebas: si el envío original de una reserva o de un
pago y su reintento llegaban a la vez, el segundo podía responder 409 («horario ocupado» o
«ya registrado») por su propio registro, y la app lo habría marcado como rechazado. Ahora el
`uuid` se vuelve a buscar dentro de la transacción bloqueada y se devuelve el mismo registro
(pruebas `test_reenvio_simultaneo_del_mismo_uuid…` y `test_el_mismo_pago_enviado_dos_veces…`:
sin la corrección fallaban con 409). En SQLite la prueba simultánea real no llegó a reproducir
la carrera (SQLite hace las escrituras de a una); en MySQL sí podía ocurrir. Del mismo modo,
si dos equipos registraban a la vez un cliente con el mismo celular, el segundo recibía un
error 500; ahora se une al cliente ya guardado (`test_mismo_cliente_registrado_a_la_vez…`).

Pendiente de verificar en la PC (PHP 8.5 y MySQL 8.4 reales):
`php artisan migrate` sobre `cancha_voley`, `php artisan test` y las dos pruebas simultáneas.
El script `scripts/actualizar_base.ps1` hace las dos primeras de forma segura: verifica que el
`.env` apunte a `cancha_voley`, ejecuta las pruebas, muestra las migraciones pendientes, guarda
un respaldo con `mysqldump` y solo migra si escribes SI.
