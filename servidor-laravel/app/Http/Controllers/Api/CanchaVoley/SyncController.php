<?php

namespace App\Http\Controllers\Api\CanchaVoley;

use App\Http\Controllers\Controller;
use App\Services\CanchaVoley\ServicioSync;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

/** CanchaVoley: sincronización offline de escritorio y Android. */
class SyncController extends Controller
{
    /** POST /api/cv/sync/operaciones — { dispositivo_uuid, operaciones: [{op_uuid, tipo, entidad_uuid, datos, creada_en}] } */
    public function operaciones(Request $request, ServicioSync $sync): JsonResponse
    {
        $datos = $request->validate([
            'dispositivo_uuid' => ['nullable', 'uuid'],
            'operaciones'      => ['required', 'array', 'max:100'],
            'operaciones.*'    => ['array'],
        ]);

        return response()->json([
            'resultados'     => $sync->procesar($datos['operaciones'], $request->user(), $datos['dispositivo_uuid'] ?? null),
            'servidor_ahora' => now()->toIso8601String(),
        ]);
    }

    /** GET /api/cv/sync/cambios?desde= */
    public function cambios(Request $request, ServicioSync $sync): JsonResponse
    {
        $datos = $request->validate(['desde' => ['nullable', 'string', 'max:40']]);

        return response()->json($sync->cambios($request->user(), $datos['desde'] ?? null));
    }
}
