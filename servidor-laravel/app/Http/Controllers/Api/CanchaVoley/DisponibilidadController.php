<?php

namespace App\Http\Controllers\Api\CanchaVoley;

use App\Http\Controllers\Controller;
use App\Models\Cancha;
use App\Models\Negocio;
use App\Services\CanchaVoley\Disponibilidad;
use Carbon\CarbonImmutable;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

/** CanchaVoley: horarios libres de un día y recomendación automática de horarios. */
class DisponibilidadController extends Controller
{
    /** GET /api/cv/disponibilidad?cancha_id=&fecha=YYYY-MM-DD */
    public function dia(Request $request, Disponibilidad $disp): JsonResponse
    {
        $datos   = $request->validate(['cancha_id' => ['required', 'integer'], 'fecha' => ['required', 'date_format:Y-m-d']]);
        $negocio = Negocio::principalOFallar();
        $cancha  = Cancha::where('negocio_id', $negocio->id)->with('horarios')->findOrFail($datos['cancha_id']);
        $fecha   = CarbonImmutable::parse($datos['fecha'], config('app.timezone'))->startOfDay();
        $regla   = $negocio->reglasOVacias();
        $turnos  = $disp->turnosDelDia($cancha, $fecha);
        $esPersonal = $request->user()->esPersonal();

        $ocupados = $turnos ? $disp->ocupados($cancha, $turnos[0][0], end($turnos)[1]) : [];

        return response()->json([
            'fecha'             => $fecha->toDateString(),
            'cancha_id'         => $cancha->id,
            'horario_configurado' => $disp->tieneHorario($cancha),
            'turnos'            => array_map(fn ($t) => ['inicio' => $t[0]->toIso8601String(), 'fin' => $t[1]->toIso8601String()], $turnos),
            'ocupados'          => array_map(fn ($o) => [
                'inicio' => $o['inicio']->toIso8601String(), 'fin' => $o['fin']->toIso8601String(),
                'tipo' => $o['tipo'], 'id' => $esPersonal ? $o['id'] : null,
            ], $ocupados),
            'libres'            => array_map(fn ($l) => ['inicio' => $l[0]->toIso8601String(), 'fin' => $l[1]->toIso8601String()],
                $disp->libres($cancha, $fecha, $regla)),
            'margen_min'        => $regla->margen(),
        ]);
    }

    /** GET /api/cv/recomendaciones?cancha_id=&fecha=&duracion_min=&hora=HH:MM&dias= */
    public function recomendaciones(Request $request, Disponibilidad $disp): JsonResponse
    {
        $datos = $request->validate([
            'cancha_id'    => ['required', 'integer'],
            'fecha'        => ['required', 'date_format:Y-m-d'],
            'duracion_min' => ['required', 'integer', 'min:5', 'max:1440'],
            'hora'         => ['nullable', 'date_format:H:i'],
            'dias'         => ['nullable', 'integer', 'min:1', 'max:31'],
        ]);
        $negocio = Negocio::principalOFallar();
        $cancha  = Cancha::where('negocio_id', $negocio->id)->with('horarios')->findOrFail($datos['cancha_id']);
        $fecha   = CarbonImmutable::parse($datos['fecha'], config('app.timezone'))->startOfDay();
        $preferida = isset($datos['hora']) ? $fecha->setTimeFromTimeString($datos['hora']) : null;

        return response()->json([
            'sugerencias' => $disp->sugerencias($cancha, $negocio->reglasOVacias(), $fecha, (int) $datos['duracion_min'], $preferida, (int) ($datos['dias'] ?? 7)),
        ]);
    }
}
