<?php

namespace App\Http\Controllers\Api\CanchaVoley;

use App\Http\Controllers\Controller;
use App\Models\Notificacion;
use App\Models\Reserva;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

/** CanchaVoley: notificaciones del usuario (leídas y no leídas). */
class NotificacionController extends Controller
{
    /** GET /api/cv/notificaciones */
    public function index(Request $request): JsonResponse
    {
        $usuario = $request->user();
        $lista = Notificacion::where('user_id', $usuario->id)->orderByDesc('id')->limit(100)->get();
        // Para abrir el detalle al tocar el aviso: uuid de la reserva (solo si el usuario puede verla).
        $ids = $lista->filter(fn (Notificacion $n) => $n->referencia_id && preg_match('/^(reserva|pago)_/', (string) $n->tipo))->pluck('referencia_id')->unique();
        $reservas = $ids->isEmpty() ? collect() : Reserva::whereIn('id', $ids)
            ->when(! $usuario->esPersonal(), fn ($q) => $q->where('cliente_id', $usuario->cliente?->id ?? 0))
            ->pluck('uuid', 'id');

        return response()->json([
            'no_leidas'      => Notificacion::where('user_id', $usuario->id)->where('leida', false)->count(),
            'notificaciones' => $lista->map(fn (Notificacion $n) => [
                'id' => $n->id, 'titulo' => $n->titulo, 'mensaje' => $n->mensaje, 'tipo' => $n->tipo,
                'referencia_id' => $n->referencia_id,
                'reserva_uuid' => preg_match('/^(reserva|pago)_/', (string) $n->tipo) ? ($reservas[$n->referencia_id] ?? null) : null,
                'leida' => $n->leida, 'fecha' => $n->created_at?->toIso8601String(),
            ])->values(),
        ]);
    }

    /** POST /api/cv/notificaciones/{id}/leer — marca una como leída (al abrirla). */
    public function leer(Request $request, int $id): JsonResponse
    {
        $actualizadas = Notificacion::where('user_id', $request->user()->id)->whereKey($id)->update(['leida' => true]);

        return response()->json(['ok' => $actualizadas > 0]);
    }

    /** POST /api/cv/notificaciones/leer — marca todas como leídas. */
    public function leerTodas(Request $request): JsonResponse
    {
        Notificacion::where('user_id', $request->user()->id)->where('leida', false)->update(['leida' => true]);

        return response()->json(['ok' => true]);
    }
}
