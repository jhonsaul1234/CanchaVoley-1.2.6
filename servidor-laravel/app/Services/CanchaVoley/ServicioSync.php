<?php

namespace App\Services\CanchaVoley;

use App\Exceptions\CanchaVoley\ReservaException;
use App\Models\Bloqueo;
use App\Models\Cliente;
use App\Models\HorarioAtencion;
use App\Models\Negocio;
use App\Models\OperacionSync;
use App\Models\Pago;
use App\Models\Reserva;
use App\Models\Tarifa;
use App\Models\User;
use Carbon\CarbonImmutable;
use Illuminate\Support\Facades\Log;
use Illuminate\Validation\ValidationException;

/**
 * CanchaVoley: sincronización de los equipos (escritorio y Android).
 *
 *  - procesar(): aplica, en orden, las operaciones guardadas sin conexión. Cada una pasa por las mismas
 *    reglas que en línea. El op_uuid único hace que un reenvío reciba la respuesta guardada (nada se
 *    aplica dos veces). Una reserva offline que el servidor no puede aceptar queda «rechazada» y nunca
 *    se confirma sola.
 *  - cambios(): lo que cambió desde el último cursor, para la copia local del equipo.
 */
class ServicioSync
{
    public const TIPOS = [
        'crear_cliente', 'actualizar_cliente', 'crear_reserva', 'reprogramar_reserva', 'cancelar_reserva',
        'aprobar_reserva', 'rechazar_reserva', 'registrar_pago', 'marcar_asistencia', 'editar_reserva',
    ];

    /** Solape del cursor: se vuelve a pedir este margen para no perder cambios de transacciones largas. */
    private const SOLAPE_CURSOR_SEGUNDOS = 120;

    public function __construct(
        private readonly ServicioReservas $reservas,
        private readonly ServicioPagos $pagos,
        private readonly ServicioClientes $clientes,
    ) {}

    /** @return list<array<string, mixed>> un resultado por operación, en el mismo orden */
    public function procesar(array $operaciones, User $actor, ?string $dispositivo): array
    {
        $negocio    = Negocio::principalOFallar();
        $resultados = [];

        foreach ($operaciones as $op) {
            $opUuid = (string) ($op['op_uuid'] ?? '');
            $tipo   = (string) ($op['tipo'] ?? '');
            if (! preg_match('/^[0-9a-fA-F-]{36}$/', $opUuid) || ! in_array($tipo, self::TIPOS, true)) {
                $resultados[] = ['op_uuid' => $opUuid, 'resultado' => 'rechazada', 'codigo' => 422, 'mensaje' => 'Operación mal formada.'];
                continue;
            }
            if ($previa = OperacionSync::where('op_uuid', $opUuid)->first()) {
                $resultados[] = $previa->user_id === $actor->id
                    ? [...$previa->respuesta, 'repetida' => true]
                    : ['op_uuid' => $opUuid, 'resultado' => 'rechazada', 'codigo' => 409, 'mensaje' => 'Identificador de operación en uso.'];
                continue;
            }

            $datos = is_array($op['datos'] ?? null) ? $op['datos'] : [];
            $ctx   = [
                'offline'               => true,
                'op_uuid'               => $opUuid,
                'dispositivo_uuid'      => $dispositivo,
                'creada_en_dispositivo' => $this->fecha($op['creada_en'] ?? null),
            ];

            try {
                $respuesta = ['op_uuid' => $opUuid, 'resultado' => 'aplicada', 'codigo' => 200, ...$this->aplicar($tipo, $datos, $actor, $ctx)];
            } catch (ReservaException $e) {
                $respuesta = ['op_uuid' => $opUuid, 'resultado' => 'rechazada', 'codigo' => $e->codigo, 'mensaje' => $e->getMessage(), ...$e->datos];
                if ($tipo === 'crear_reserva' && in_array($e->codigo, [409, 422], true)) {
                    $guardada = $this->reservas->registrarRechazadaOffline($datos, $actor, $ctx, $e->getMessage());
                    if ($guardada) {
                        $respuesta['entidad_tipo'] = 'reserva';
                        $respuesta['entidad'] = $guardada->aApi();
                    }
                }
            } catch (ValidationException $e) {
                $respuesta = ['op_uuid' => $opUuid, 'resultado' => 'rechazada', 'codigo' => 422, 'mensaje' => $e->getMessage(), 'errores' => $e->errors()];
            } catch (\Throwable $e) {
                Log::error('CanchaVoley sync: error inesperado', ['op' => $opUuid, 'tipo' => $tipo, 'error' => $e->getMessage()]);
                $resultados[] = ['op_uuid' => $opUuid, 'resultado' => 'error', 'codigo' => 500, 'mensaje' => 'Error del servidor; se reintentará.'];
                continue;   // no se guarda: el equipo la reintenta más tarde
            }

            OperacionSync::create([
                'op_uuid'               => $opUuid,
                'negocio_id'            => $negocio->id,
                'user_id'               => $actor->id,
                'dispositivo_uuid'      => $dispositivo,
                'tipo'                  => $tipo,
                'entidad_uuid'          => $op['entidad_uuid'] ?? ($datos['uuid'] ?? null),
                'datos'                 => $datos,
                'resultado'             => $respuesta['resultado'],
                'respuesta'             => $respuesta,
                'creada_en_dispositivo' => $ctx['creada_en_dispositivo'],
                'recibida_en'           => CarbonImmutable::now(),
            ]);
            $resultados[] = $respuesta;
        }

        return $resultados;
    }

    private function aplicar(string $tipo, array $d, User $actor, array $ctx): array
    {
        switch ($tipo) {
            case 'crear_cliente':
                [$cliente, , $unido] = $this->clientes->crear($d, $actor);
                return ['entidad_tipo' => 'cliente', 'entidad' => $cliente->aApi(), 'unido_a_existente' => $unido];
            case 'actualizar_cliente':
                $cliente = Cliente::where('uuid', $d['uuid'] ?? '')->first() ?? $this->clientes->porUuidProvisional((string) ($d['uuid'] ?? ''));
                if (! $cliente) {
                    throw new ReservaException('El cliente no existe.', 404);
                }
                return ['entidad_tipo' => 'cliente', 'entidad' => $this->clientes->actualizar($cliente, $d, $actor)->aApi()];
            case 'crear_reserva':
                [$reserva] = $this->reservas->crear($d, $actor, $ctx);
                return ['entidad_tipo' => 'reserva', 'entidad' => $reserva->aApi()];
            case 'registrar_pago':
                [$pago, , $advertencia] = $this->pagos->registrar($d, $actor, $ctx);
                return ['entidad_tipo' => 'pago', 'entidad' => $pago->aApi(), 'advertencia' => $advertencia,
                    'reserva' => $pago->reserva->fresh()->aApi()];
        }

        $reserva = Reserva::where('uuid', $d['reserva_uuid'] ?? $d['uuid'] ?? '')->first()
            ?? throw new ReservaException('La reserva no existe.', 404);
        $reserva = match ($tipo) {
            'reprogramar_reserva' => $this->reservas->reprogramar($reserva, $actor, $d['inicio'] ?? null, $d['fin'] ?? null, $ctx),
            'cancelar_reserva'    => $this->reservas->cancelar($reserva, $actor, (string) ($d['motivo'] ?? 'Cancelada sin conexión'), $ctx),
            'aprobar_reserva'     => $this->reservas->aprobar($reserva, $actor, $d['precio_final'] ?? null, $ctx),
            'rechazar_reserva'    => $this->reservas->rechazar($reserva, $actor, (string) ($d['motivo'] ?? 'Rechazada'), $ctx),
            'marcar_asistencia'   => $this->reservas->marcarAsistencia($reserva, $actor, (bool) ($d['asistio'] ?? true), $ctx,
                (bool) ($d['corregir'] ?? false), isset($d['motivo']) ? (string) $d['motivo'] : null),
            'editar_reserva'      => $this->reservas->editar($reserva, $actor, array_intersect_key($d, array_flip(['precio_final', 'notas', 'motivo'])), $ctx),
        };

        return ['entidad_tipo' => 'reserva', 'entidad' => $reserva->aApi()];
    }

    /** Cambios desde el cursor (ISO 8601) para la copia local. Sin cursor: carga inicial. */
    public function cambios(User $actor, ?string $desde): array
    {
        $negocio = Negocio::principalOFallar();
        $ahora   = CarbonImmutable::now();
        $cursor  = $this->fecha($desde);
        $ventana = $ahora->subDays(30)->startOfDay();

        $catalogo = [
            'negocio'  => $negocio->aApi(),
            'canchas'  => $negocio->canchas()->get()->map->aApi()->values(),
            'horarios' => HorarioAtencion::whereIn('cancha_id', $negocio->canchas()->pluck('id'))->get()->map->aApi()->values(),
            'tarifas'  => Tarifa::whereIn('cancha_id', $negocio->canchas()->pluck('id'))
                ->where(fn ($q) => $q->whereNull('vigente_hasta')->orWhere('vigente_hasta', '>=', $ahora->toDateString()))
                ->get()->map->aApi()->values(),
            'reglas'   => $negocio->reglasOVacias()->aApi(),
            'bloqueos' => Bloqueo::withTrashed()->where('negocio_id', $negocio->id)->where('fin', '>=', $ventana)
                ->when($cursor, fn ($q) => $q->where('updated_at', '>=', $cursor))->get()->map->aApi()->values(),
        ];

        if ($actor->esPersonal()) {
            $clientes = Cliente::withTrashed()->where('negocio_id', $negocio->id)
                ->when($cursor, fn ($q) => $q->where('updated_at', '>=', $cursor))->get();
            $reservas = Reserva::where('negocio_id', $negocio->id)->where('fin', '>=', $ventana)
                ->when($cursor, fn ($q) => $q->where('updated_at', '>=', $cursor))->with(['cliente', 'pagos', 'pagosInformados'])->get();
            $pagos = Pago::where('negocio_id', $negocio->id)
                ->whereIn('reserva_id', Reserva::where('negocio_id', $negocio->id)->where('fin', '>=', $ventana)->select('id'))
                ->when($cursor, fn ($q) => $q->where('updated_at', '>=', $cursor))->with(['reserva', 'registrador'])->get();
        } else {
            $propio   = $actor->cliente;
            $clientes = $propio ? collect([$propio]) : collect();
            $ids      = $propio ? Reserva::where('cliente_id', $propio->id)->pluck('id') : collect();
            $reservas = Reserva::whereIn('id', $ids)->when($cursor, fn ($q) => $q->where('updated_at', '>=', $cursor))->with(['cliente', 'pagos', 'pagosInformados'])->get();
            $pagos    = Pago::whereIn('reserva_id', $ids)->when($cursor, fn ($q) => $q->where('updated_at', '>=', $cursor))->with(['reserva', 'registrador'])->get();
        }

        $respuesta = [
            'servidor_ahora' => $ahora->toIso8601String(),
            'cursor'         => $ahora->subSeconds(self::SOLAPE_CURSOR_SEGUNDOS)->toIso8601String(),
            'carga_completa' => $cursor === null,
            'catalogo'       => $catalogo,
            'clientes'       => $clientes->map->aApi()->values(),
            'reservas'       => $reservas->map->aApi()->values(),
            'pagos'          => $pagos->map->aApi()->values(),
        ];

        // La app del cliente solo tiene sus propias reservas: para consultar horarios sin conexión necesita
        // saber qué está ocupado (sin nombres ni datos de otros clientes). Se envía completa en cada descarga.
        if (! $actor->esPersonal()) {
            $regla = $negocio->reglasOVacias();
            $desde = $ahora->startOfDay();
            $dias  = max(7, min(60, (int) ($regla->anticipacion_maxima_dias ?? 30)));
            $hasta = $desde->addDays($dias + 1);
            $respuesta['ocupacion'] = [
                'generado_en' => $ahora->toIso8601String(),
                'desde'       => $desde->toIso8601String(),
                'hasta'       => $hasta->toIso8601String(),
                'intervalos'  => app(Disponibilidad::class)->ocupacionDelNegocio($negocio, $desde, $hasta),
            ];
        }

        return $respuesta;
    }

    private function fecha(mixed $valor): ?CarbonImmutable
    {
        if (! is_string($valor) || $valor === '') {
            return null;
        }
        try {
            return CarbonImmutable::parse($valor)->setTimezone(config('app.timezone'));
        } catch (\Throwable) {
            return null;
        }
    }
}
