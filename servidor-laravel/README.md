# CanchaVoley — servidor (Laravel)

Servidor central del sistema de reservas de la cancha de vóley: guarda clientes, reservas,
pagos (efectivo, Yape, Plin), adelantos y la configuración del negocio, y atiende a la app
de Windows y a la app Android (con y sin conexión). Es una copia independiente de ProLech;
ProLech original y sus bases no se modifican.

- Base de datos: **solo** `cancha_voley` (las bases de ProLech están protegidas).
- API: `/api/cv/*` (ver `routes/canchavoley.php`).
- Código propio: `app/Services/CanchaVoley`, `app/Http/Controllers/Api/CanchaVoley`,
  `app/Models` (Negocio, Cancha, Reserva, Pago…), `database/migrations/2026_11_16_1000NN_*`,
  `tests/Feature/CanchaVoley`, `app/Console/Commands/CanchaVoleyInstalar.php`.

## Puesta en marcha rápida

```
scripts\actualizar_base.cmd      # pruebas, respaldo y migraciones (pide escribir SI)
php artisan canchavoley:instalar --negocio="…" --cancha="Cancha 1" --admin-nombre="…" --admin-email="…" --admin-password="…"
php artisan serve --host=0.0.0.0 --port=8001
```

## Documentación

- [docs/INSTALACION.md](docs/INSTALACION.md) — instalación paso a paso, respaldos y problemas frecuentes.
- [docs/API.md](docs/API.md) — rutas, permisos, protección contra duplicados y sincronización.
- [docs/BASE_DE_DATOS.md](docs/BASE_DE_DATOS.md) — tablas, estados y reglas configurables.
- [docs/PRUEBAS.md](docs/PRUEBAS.md) — pruebas automáticas, pruebas simultáneas y resultados.
- Manual de usuario y compilación de las apps: `../cancha-app/docs/`.
