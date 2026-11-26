<?php

/*
| CanchaVoley · API de reservas de canchas (escritorio Windows y Android).
| Prefijo: /api/cv. Login y logout reutilizan /api/login y /api/logout de ProLech.
| Roles: admin (administradora), recepcion (atiende las llamadas), cliente (app Android).
*/

use App\Http\Controllers\Api\CanchaVoley\CatalogoController;
use App\Http\Controllers\Api\CanchaVoley\ClienteController;
use App\Http\Controllers\Api\CanchaVoley\DisponibilidadController;
use App\Http\Controllers\Api\CanchaVoley\NotificacionController;
use App\Http\Controllers\Api\CanchaVoley\PagoController;
use App\Http\Controllers\Api\CanchaVoley\ReporteController;
use App\Http\Controllers\Api\CanchaVoley\ReservaController;
use App\Http\Controllers\Api\CanchaVoley\SesionController;
use App\Http\Controllers\Api\CanchaVoley\SyncController;
use App\Http\Controllers\Api\CanchaVoley\UsuarioController;
use Illuminate\Support\Facades\Route;

Route::prefix('cv')->group(function () {

    // Público: un cliente crea su cuenta desde la app Android.
    Route::post('registro', [SesionController::class, 'registro'])->middleware('throttle:registro-cv');

    Route::middleware(['auth:sanctum', 'cuenta-cv', 'rol:admin,recepcion,cliente'])->group(function () {

        // ── Todos los roles (cada servicio limita lo que ve un cliente) ──────────
        Route::get('sesion', [SesionController::class, 'sesion']);
        Route::get('catalogo', [CatalogoController::class, 'catalogo']);
        Route::get('disponibilidad', [DisponibilidadController::class, 'dia']);
        Route::get('recomendaciones', [DisponibilidadController::class, 'recomendaciones']);

        Route::get('reservas', [ReservaController::class, 'index']);
        Route::post('reservas', [ReservaController::class, 'store']);
        Route::get('reservas/{uuid}', [ReservaController::class, 'show'])->whereUuid('uuid');
        Route::post('reservas/{uuid}/cancelar', [ReservaController::class, 'cancelar'])->whereUuid('uuid');
        Route::post('reservas/{uuid}/reprogramar', [ReservaController::class, 'reprogramar'])->whereUuid('uuid');
        Route::get('reservas/{uuid}/comprobante', [ReservaController::class, 'comprobante'])->whereUuid('uuid');
        // El cliente informa que pagó su adelanto (solo referencia: el personal lo verifica y registra).
        Route::post('reservas/{uuid}/informar-pago', [ReservaController::class, 'informarPago'])->whereUuid('uuid');

        Route::get('notificaciones', [NotificacionController::class, 'index']);
        Route::post('notificaciones/leer', [NotificacionController::class, 'leerTodas']);
        Route::post('notificaciones/{id}/leer', [NotificacionController::class, 'leer'])->whereNumber('id');

        Route::post('sync/operaciones', [SyncController::class, 'operaciones']);
        Route::get('sync/cambios', [SyncController::class, 'cambios']);

        // ── Personal: administradora y recepción ──────────────────────────────
        Route::middleware('rol:admin,recepcion')->group(function () {
            Route::get('clientes', [ClienteController::class, 'index']);
            Route::post('clientes', [ClienteController::class, 'store']);
            Route::get('clientes/telefono/{telefono}', [ClienteController::class, 'porTelefono']);
            Route::get('clientes/{cliente}', [ClienteController::class, 'show'])->whereNumber('cliente');
            Route::put('clientes/{cliente}', [ClienteController::class, 'update'])->whereNumber('cliente');

            Route::post('reservas/{uuid}/asistencia', [ReservaController::class, 'asistencia'])->whereUuid('uuid');
            Route::post('reservas/{uuid}/editar', [ReservaController::class, 'editar'])->whereUuid('uuid');
            Route::get('qr/{codigo}', [ReservaController::class, 'porQr']);

            Route::post('pagos', [PagoController::class, 'store']);
            Route::post('pagos-informados/{uuid}/descartar', [PagoController::class, 'descartarInformado'])->whereUuid('uuid');
            Route::get('caja', [ReporteController::class, 'caja']);
        });

        // ── Solo la administradora ────────────────────────────────────────────
        Route::middleware('rol:admin')->group(function () {
            Route::post('reservas/{uuid}/aprobar', [ReservaController::class, 'aprobar'])->whereUuid('uuid');
            Route::post('reservas/{uuid}/rechazar', [ReservaController::class, 'rechazar'])->whereUuid('uuid');
            Route::post('pagos/{uuid}/anular', [PagoController::class, 'anular'])->whereUuid('uuid');

            Route::put('negocio', [CatalogoController::class, 'actualizarNegocio']);
            Route::post('canchas', [CatalogoController::class, 'crearCancha']);
            Route::put('canchas/{cancha}', [CatalogoController::class, 'actualizarCancha'])->whereNumber('cancha');
            Route::put('canchas/{cancha}/horarios', [CatalogoController::class, 'guardarHorarios'])->whereNumber('cancha');
            Route::put('canchas/{cancha}/tarifas', [CatalogoController::class, 'guardarTarifas'])->whereNumber('cancha');
            Route::post('canchas/{cancha}/copiar-configuracion', [CatalogoController::class, 'copiarConfiguracion'])->whereNumber('cancha');
            Route::put('reglas', [CatalogoController::class, 'guardarReglas']);
            Route::post('bloqueos', [CatalogoController::class, 'crearBloqueo']);
            Route::delete('bloqueos/{bloqueo}', [CatalogoController::class, 'eliminarBloqueo'])->whereNumber('bloqueo');

            Route::get('reportes/ocupacion', [ReporteController::class, 'ocupacion']);

            Route::get('usuarios', [UsuarioController::class, 'index']);
            Route::post('usuarios', [UsuarioController::class, 'store']);
            Route::put('usuarios/{user}', [UsuarioController::class, 'update'])->whereNumber('user');
        });
    });
});
