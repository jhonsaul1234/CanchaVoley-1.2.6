<?php

namespace App\Http\Controllers\Api\CanchaVoley;

use App\Exceptions\CanchaVoley\ReservaException;
use App\Http\Controllers\Controller;
use App\Models\Bloqueo;
use App\Models\Cancha;
use App\Models\HorarioAtencion;
use App\Models\Negocio;
use App\Models\ReglaReserva;
use App\Models\Reserva;
use App\Models\Tarifa;
use App\Services\AuditoriaService;
use App\Services\CanchaVoley\ServicioSync;
use Carbon\CarbonImmutable;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Validation\Rule;

/**
 * CanchaVoley: configuración del negocio (canchas, horarios, tarifas, reglas y bloqueos).
 * Lectura: todos los roles. Cambios: solo la administradora (rutas protegidas con rol:admin).
 * Nada se precarga: la administradora ingresa los datos reales del negocio.
 */
class CatalogoController extends Controller
{
    /** GET /api/cv/catalogo */
    public function catalogo(Request $request, ServicioSync $sync): JsonResponse
    {
        return response()->json($sync->cambios($request->user(), now()->toIso8601String())['catalogo']);
    }

    /** PUT /api/cv/negocio */
    public function actualizarNegocio(Request $request): JsonResponse
    {
        $numero = ['nullable', 'string', 'max:20', 'regex:/^[0-9 +]{6,20}$/'];
        $datos = $request->validate([
            'nombre'    => ['required', 'string', 'max:120'],
            'telefono'  => ['nullable', 'string', 'max:20'],
            'direccion' => ['nullable', 'string', 'max:200'],
            // Datos de pago para el adelanto (opcionales; una app anterior que no los envía no los borra).
            'pago_yape'          => $numero,
            'pago_plin'          => $numero,
            'pago_titular'       => ['nullable', 'string', 'max:120'],
            'pago_instrucciones' => ['nullable', 'string', 'max:500'],
        ], ['pago_yape.regex' => 'El número de Yape solo lleva dígitos (por ejemplo 987654321).',
            'pago_plin.regex' => 'El número de Plin solo lleva dígitos (por ejemplo 987654321).']);
        foreach (['pago_yape', 'pago_plin', 'pago_titular', 'pago_instrucciones'] as $campo) {
            if (array_key_exists($campo, $datos)) {
                $datos[$campo] = trim((string) $datos[$campo]) === '' ? null : trim((string) $datos[$campo]);
            }
        }
        $negocio = Negocio::principalOFallar();
        $negocio->fill($datos)->save();

        return response()->json($negocio->aApi());
    }

    /** POST /api/cv/canchas */
    public function crearCancha(Request $request): JsonResponse
    {
        $negocio = Negocio::principalOFallar();
        $datos = $request->validate([
            'nombre'      => ['required', 'string', 'max:80', Rule::unique('canchas')->where('negocio_id', $negocio->id)],
            'deporte'     => ['nullable', 'string', 'max:30'],
            'descripcion' => ['nullable', 'string', 'max:255'],
        ]);
        $cancha = Cancha::create([
            ...$datos, 'deporte' => $datos['deporte'] ?? 'voley', 'negocio_id' => $negocio->id,
            'orden' => (int) $negocio->canchas()->max('orden') + 1,
        ]);

        return response()->json($cancha->aApi(), 201);
    }

    /** PUT /api/cv/canchas/{cancha} */
    public function actualizarCancha(Request $request, Cancha $cancha): JsonResponse
    {
        $datos = $request->validate([
            'nombre'      => ['sometimes', 'string', 'max:80', Rule::unique('canchas')->where('negocio_id', $cancha->negocio_id)->ignore($cancha->id)],
            'deporte'     => ['sometimes', 'string', 'max:30'],
            'descripcion' => ['sometimes', 'nullable', 'string', 'max:255'],
            'activa'      => ['sometimes', 'boolean'],
            'orden'       => ['sometimes', 'integer', 'min:1'],
        ]);
        $cancha->fill($datos)->save();

        return response()->json($cancha->aApi());
    }

    /** PUT /api/cv/canchas/{cancha}/horarios — reemplaza el horario semanal completo. */
    public function guardarHorarios(Request $request, Cancha $cancha): JsonResponse
    {
        $datos = $request->validate([
            'horarios'              => ['present', 'array', 'max:50'],
            'horarios.*.dia_semana' => ['required', 'integer', 'between:1,7'],
            'horarios.*.abre'       => ['required', 'date_format:H:i'],
            'horarios.*.cierra'     => ['required', 'date_format:H:i', 'different:horarios.*.abre'],
        ]);
        foreach (collect($datos['horarios'])->groupBy('dia_semana') as $dia => $turnos) {
            $this->exigirSinSolapes($turnos->map(fn ($t) => [$this->minutos($t['abre']), $this->minutosFin($t['abre'], $t['cierra'])])->all(),
                "Los turnos del día {$dia} se superponen.");
        }

        DB::transaction(function () use ($cancha, $datos) {
            $cancha->horarios()->delete();
            foreach ($datos['horarios'] as $h) {
                $cancha->horarios()->create($h);
            }
            $cancha->touch();
        });
        $this->auditar($request, $cancha, 'horarios', null, json_encode($datos['horarios']), 'Horario de atención actualizado');

        return response()->json($cancha->horarios()->get()->map->aApi()->values());
    }

    /**
     * PUT /api/cv/canchas/{cancha}/tarifas — nueva lista de franjas de precio, vigente desde una fecha.
     * Las tarifas anteriores se cierran el día previo (las reservas ya hechas conservan su precio).
     */
    public function guardarTarifas(Request $request, Cancha $cancha): JsonResponse
    {
        $datos = $request->validate([
            'vigente_desde'         => ['nullable', 'date'],
            'tarifas'               => ['present', 'array', 'max:100'],
            'tarifas.*.dia_semana'  => ['required', 'integer', 'between:1,7'],
            'tarifas.*.desde'       => ['required', 'date_format:H:i'],
            'tarifas.*.hasta'       => ['required', 'date_format:H:i'],
            'tarifas.*.precio_hora' => ['required', 'numeric', 'gt:0', 'max:99999'],
        ]);
        foreach ($datos['tarifas'] as $t) {
            $fin = $t['hasta'] === '00:00' ? 1440 : $this->minutos($t['hasta']);
            if ($fin <= $this->minutos($t['desde'])) {
                throw new ReservaException('En cada franja, «hasta» debe ser posterior a «desde» (use 00:00 para la medianoche).', 422);
            }
        }
        foreach (collect($datos['tarifas'])->groupBy('dia_semana') as $dia => $franjas) {
            $this->exigirSinSolapes($franjas->map(fn ($t) => [$this->minutos($t['desde']), $t['hasta'] === '00:00' ? 1440 : $this->minutos($t['hasta'])])->all(),
                "Las franjas de precio del día {$dia} se superponen.");
        }
        $desde = CarbonImmutable::parse($datos['vigente_desde'] ?? now()->toDateString())->startOfDay();

        DB::transaction(function () use ($cancha, $datos, $desde) {
            Tarifa::where('cancha_id', $cancha->id)->where('vigente_desde', '>=', $desde->toDateString())->delete();
            Tarifa::where('cancha_id', $cancha->id)->where(fn ($q) => $q->whereNull('vigente_hasta')->orWhere('vigente_hasta', '>=', $desde->toDateString()))
                ->update(['vigente_hasta' => $desde->subDay()->toDateString()]);
            foreach ($datos['tarifas'] as $t) {
                $cancha->tarifas()->create([...$t, 'vigente_desde' => $desde->toDateString(), 'vigente_hasta' => null]);
            }
            $cancha->touch();
        });
        $this->auditar($request, $cancha, 'tarifas', null, json_encode($datos['tarifas']), 'Tarifas actualizadas desde ' . $desde->toDateString());

        return response()->json(Tarifa::where('cancha_id', $cancha->id)->whereNull('vigente_hasta')->get()->map->aApi()->values());
    }

    /** POST /api/cv/canchas/{cancha}/copiar-configuracion — copia horario y tarifas de otra cancha. */
    public function copiarConfiguracion(Request $request, Cancha $cancha): JsonResponse
    {
        $datos  = $request->validate(['desde_cancha_id' => ['required', 'integer', 'exists:canchas,id', 'different:' . $cancha->id]]);
        $origen = Cancha::where('negocio_id', $cancha->negocio_id)->with(['horarios', 'tarifas'])->findOrFail($datos['desde_cancha_id']);

        DB::transaction(function () use ($cancha, $origen) {
            $cancha->horarios()->delete();
            $cancha->tarifas()->delete();
            foreach ($origen->horarios as $h) {
                $cancha->horarios()->create($h->only(['dia_semana', 'abre', 'cierra']));
            }
            foreach ($origen->tarifas->whereNull('vigente_hasta') as $t) {
                $cancha->tarifas()->create([...$t->only(['dia_semana', 'desde', 'hasta', 'precio_hora']), 'vigente_desde' => now()->toDateString()]);
            }
            $cancha->touch();
        });

        return response()->json(['horarios' => $cancha->horarios()->get()->map->aApi(), 'tarifas' => $cancha->tarifas()->get()->map->aApi()]);
    }

    /** PUT /api/cv/reglas — reglas de reserva (NULL = sin configurar). */
    public function guardarReglas(Request $request): JsonResponse
    {
        $entero = ['nullable', 'integer', 'min:0', 'max:100000'];
        $datos = $request->validate([
            'duracion_minima_min'         => [...$entero, 'max:1440'],
            'duracion_maxima_min'         => [...$entero, 'max:1440'],
            'paso_min'                    => ['nullable', 'integer', 'min:5', 'max:1440'],
            'alinear_inicio_min'          => ['nullable', 'integer', 'min:5', 'max:1440'],
            'margen_entre_reservas_min'   => [...$entero, 'max:240'],
            'anticipacion_minima_min'     => $entero,
            'anticipacion_maxima_dias'    => [...$entero, 'max:366'],
            'regla_cruce_franjas'         => ['nullable', Rule::in(['prorrateo', 'franja_inicio'])],
            'adelanto_tipo'               => ['nullable', Rule::in(['monto', 'porcentaje'])],
            'adelanto_valor'              => ['nullable', 'numeric', 'min:0', 'max:99999'],
            'adelanto_plazo_min'          => $entero,
            'adelanto_obligatorio'        => ['nullable', 'boolean'],
            'cancelacion_limite_horas'    => [...$entero, 'max:720'],
            'cancelacion_devolucion'      => ['nullable', Rule::in(['total', 'parcial', 'ninguna', 'credito'])],
            'cancelacion_porcentaje'      => ['nullable', 'numeric', 'min:0', 'max:100'],
            'reprogramacion_limite_horas' => [...$entero, 'max:720'],
            'tolerancia_no_show_min'      => [...$entero, 'max:240'],
        ]);
        if (($datos['adelanto_tipo'] ?? null) === 'porcentaje' && ($datos['adelanto_valor'] ?? 0) > 100) {
            throw new ReservaException('El adelanto en porcentaje no puede pasar de 100.', 422);
        }
        if (isset($datos['duracion_minima_min'], $datos['duracion_maxima_min']) && $datos['duracion_minima_min'] > $datos['duracion_maxima_min']) {
            throw new ReservaException('La duración mínima no puede ser mayor que la máxima.', 422);
        }
        $regla = Negocio::principalOFallar()->reglasOVacias();
        $antes = json_encode($regla->aApi());
        $regla->fill([...array_intersect_key($datos, array_flip(ReglaReserva::CAMPOS)), 'updated_by' => $request->user()->id])->save();
        $this->auditar($request, $regla, 'reglas', $antes, json_encode($regla->fresh()->aApi()), 'Reglas de reserva actualizadas');

        return response()->json($regla->fresh()->aApi());
    }

    /** POST /api/cv/bloqueos — cierra una cancha (o todo el local) y lista las reservas afectadas. */
    public function crearBloqueo(Request $request): JsonResponse
    {
        $negocio = Negocio::principalOFallar();
        $datos = $request->validate([
            'cancha_id' => ['nullable', 'integer', Rule::exists('canchas', 'id')->where('negocio_id', $negocio->id)],
            'inicio'    => ['required', 'date'],
            'fin'       => ['required', 'date', 'after:inicio'],
            'motivo'    => ['required', 'string', 'max:200'],
        ]);
        $inicio = CarbonImmutable::parse($datos['inicio'])->setTimezone(config('app.timezone'));
        $fin    = CarbonImmutable::parse($datos['fin'])->setTimezone(config('app.timezone'));
        $bloqueo = Bloqueo::create([...$datos, 'inicio' => $inicio, 'fin' => $fin, 'negocio_id' => $negocio->id, 'creado_por' => $request->user()->id]);

        $afectadas = Reserva::where('negocio_id', $negocio->id)
            ->when($datos['cancha_id'] ?? null, fn ($q, $c) => $q->where('cancha_id', $c))
            ->whereIn('estado', Reserva::ESTADOS_QUE_OCUPAN)
            ->where('inicio', '<', $fin)->where('fin', '>', $inicio)->get();

        return response()->json(['bloqueo' => $bloqueo->aApi(), 'reservas_afectadas' => $afectadas->map->aApi()->values()], 201);
    }

    /** DELETE /api/cv/bloqueos/{bloqueo} */
    public function eliminarBloqueo(Bloqueo $bloqueo): JsonResponse
    {
        $bloqueo->delete();

        return response()->json($bloqueo->aApi());
    }

    private function auditar(Request $request, \Illuminate\Database\Eloquent\Model $modelo, string $campo, ?string $antes, ?string $despues, string $motivo): void
    {
        AuditoriaService::registrar($request->user(), class_basename($modelo), (int) $modelo->getKey(), 'edicion', $motivo, $campo, $antes, $despues);
    }

    private function minutos(string $hhmm): int
    {
        [$h, $m] = array_map('intval', explode(':', $hhmm));
        return $h * 60 + $m;
    }

    private function minutosFin(string $abre, string $cierra): int
    {
        $a = $this->minutos($abre);
        $c = $this->minutos($cierra);
        return $c <= $a ? $c + 1440 : $c;
    }

    private function exigirSinSolapes(array $intervalos, string $mensaje): void
    {
        usort($intervalos, fn ($x, $y) => $x[0] <=> $y[0]);
        for ($i = 1; $i < count($intervalos); $i++) {
            if ($intervalos[$i][0] < $intervalos[$i - 1][1]) {
                throw new ReservaException($mensaje, 422);
            }
        }
    }
}
