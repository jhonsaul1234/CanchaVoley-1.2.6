<?php

namespace App\Http\Controllers\Api\CanchaVoley;

use App\Http\Controllers\Controller;
use App\Services\CanchaVoley\Reportes;
use Carbon\CarbonImmutable;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

/** CanchaVoley: estadísticas de ocupación (administradora) y caja del día (personal). */
class ReporteController extends Controller
{
    /** GET /api/cv/reportes/ocupacion?desde=&hasta=&cancha_id= */
    public function ocupacion(Request $request, Reportes $reportes): JsonResponse
    {
        $datos = $request->validate([
            'desde'     => ['required', 'date_format:Y-m-d'],
            'hasta'     => ['required', 'date_format:Y-m-d', 'after_or_equal:desde'],
            'cancha_id' => ['nullable', 'integer'],
        ]);
        $desde = CarbonImmutable::parse($datos['desde'], config('app.timezone'));
        $hasta = CarbonImmutable::parse($datos['hasta'], config('app.timezone'));
        if ($desde->diffInDays($hasta) > 366) {
            return response()->json(['message' => 'El rango máximo es de un año.'], 422);
        }

        return response()->json($reportes->ocupacion($desde, $hasta, $datos['cancha_id'] ?? null));
    }

    /** GET /api/cv/caja?fecha=YYYY-MM-DD */
    public function caja(Request $request, Reportes $reportes): JsonResponse
    {
        $datos = $request->validate(['fecha' => ['nullable', 'date_format:Y-m-d']]);
        $fecha = CarbonImmutable::parse($datos['fecha'] ?? now()->toDateString(), config('app.timezone'));

        return response()->json($reportes->caja($fecha));
    }
}
