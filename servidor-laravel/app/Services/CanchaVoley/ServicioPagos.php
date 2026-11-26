<?php

namespace App\Services\CanchaVoley;

use App\Exceptions\CanchaVoley\ReservaException;
use App\Models\Negocio;
use App\Models\Pago;
use App\Models\PagoInformado;
use App\Models\Reserva;
use App\Models\User;
use Carbon\CarbonImmutable;
use Illuminate\Database\QueryException;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

/**
 * CanchaVoley: pagos y adelantos (efectivo, Yape, Plin).
 * Sin pagos duplicados: (1) uuid único frente a reintentos, (2) número de operación de Yape/Plin único,
 * (3) en efectivo, aviso (no bloqueo) si hay un pago igual y reciente de la misma reserva.
 * Los pagos no se editan ni se borran: se anulan (solo la administradora) y se registra otro.
 *
 * Reglas por estado (22/11/2026):
 *  - Reserva cancelada o rechazada: no se registran adelantos ni pagos. Única excepción: un cobro hecho
 *    SIN CONEXIÓN cuando la reserva todavía estaba activa (dinero que de verdad se recibió): se acepta
 *    como saldo a favor del cliente para que no se pierda.
 *  - Reserva activa con precio: en línea no se acepta un cobro mayor que el saldo pendiente
 *    (sin conexión se acepta y se avisa del saldo a favor).
 *  - Devolución: solo la administradora, con motivo, y nunca más de lo cobrado (neto) en la reserva.
 *  - Anulación: tampoco se anula un cobro que ya se devolvió (primero se anula la devolución).
 */
class ServicioPagos
{
    public function __construct(private readonly ServicioReservas $reservas, private readonly Avisos $avisos) {}

    /** @return array{0: Pago, 1: bool, 2: ?string} pago, creado ahora, advertencia */
    public function registrar(array $d, User $actor, array $ctx = []): array
    {
        if (! $actor->esPersonal()) {
            throw new ReservaException('Solo el personal del negocio registra pagos.', 403);
        }
        $negocio = Negocio::principalOFallar();
        $uuid    = (string) ($d['uuid'] ?? Str::uuid());
        if ($existente = Pago::where('uuid', $uuid)->first()) {
            return [$existente, false, null];
        }

        $reserva = $this->reserva($d, $negocio);
        $tipo    = $d['tipo'] ?? 'pago';
        $metodo  = $d['metodo'] ?? null;
        if (! in_array($tipo, Pago::TIPOS, true)) {
            throw new ReservaException('Tipo de pago inválido (adelanto, pago o devolución).', 422, ['falta' => 'tipo']);
        }
        if (! in_array($metodo, Pago::METODOS, true)) {
            throw new ReservaException('Método de pago inválido (efectivo, Yape o Plin).', 422, ['falta' => 'metodo']);
        }
        $centimos = (int) round(((float) ($d['monto'] ?? 0)) * 100);
        if ($centimos <= 0) {
            throw new ReservaException('El monto debe ser mayor que cero.', 422, ['falta' => 'monto']);
        }
        $observacion = trim((string) ($d['observacion'] ?? ''));
        if ($tipo === 'devolucion') {
            // Una devolución es dinero que sale de la caja: la autoriza la administradora y queda su motivo.
            if (! $actor->tieneRol('admin')) {
                throw new ReservaException('Solo la administradora registra devoluciones.', 403);
            }
            if (mb_strlen($observacion) < 3) {
                throw new ReservaException('Indica el motivo de la devolución (queda en el historial de la reserva).', 422, ['falta' => 'observacion']);
            }
        }
        $numero = $metodo === 'efectivo' ? null : (trim((string) ($d['numero_operacion'] ?? '')) ?: null);
        $pagadoEn = isset($d['pagado_en'])
            ? CarbonImmutable::parse($d['pagado_en'])->setTimezone(config('app.timezone'))
            : ($ctx['creada_en_dispositivo'] ?? CarbonImmutable::now());
        if ($pagadoEn->greaterThan(CarbonImmutable::now()->addMinutes(5))) {
            $pagadoEn = CarbonImmutable::now();            // reloj del equipo adelantado
        }
        $offline = (bool) ($ctx['offline'] ?? false);
        $hechoEnEquipo = ($ctx['creada_en_dispositivo'] ?? null) instanceof CarbonImmutable ? $ctx['creada_en_dispositivo'] : null;

        try {
            [$pago, $advertencia, $yaExistia, $confirmo] = DB::transaction(function () use ($reserva, $tipo, $metodo, $centimos, $numero, $pagadoEn, $uuid, $negocio, $actor, $ctx, $observacion, $offline, $hechoEnEquipo) {
                $r = Reserva::whereKey($reserva->id)->lockForUpdate()->firstOrFail();
                // El mismo pago enviado dos veces a la vez: el segundo espera el bloqueo y lo encuentra aquí.
                if ($yaGuardado = Pago::where('uuid', $uuid)->first()) {
                    return [$yaGuardado, null, true, false];
                }
                $r->load('pagos');

                if ($numero !== null && Pago::where('negocio_id', $negocio->id)->where('metodo', $metodo)->where('numero_operacion', $numero)->exists()) {
                    throw new ReservaException("La operación {$numero} de " . ucfirst($metodo) . ' ya está registrada.', 409, ['duplicado' => 'numero_operacion']);
                }

                $advertencia = null;
                $pagado      = $r->centimosPagados();
                if ($tipo === 'devolucion') {
                    if ($pagado <= 0) {
                        throw new ReservaException('Esta reserva no tiene pagos que devolver.', 422);
                    }
                    if ($centimos > $pagado) {
                        throw new ReservaException('La devolución no puede ser mayor que lo cobrado en esta reserva (' . $this->soles($pagado) . ').', 422);
                    }
                } elseif (! $r->ocupaLaCancha()) {
                    // Cancelada o rechazada. Solo se acepta un cobro hecho sin conexión ANTES de que dejara de estar activa.
                    $dejoDeEstarActiva = $this->momentoEnQueDejoDeEstarActiva($r);
                    $cobradoAntes = $offline && $hechoEnEquipo !== null && $dejoDeEstarActiva !== null && $hechoEnEquipo->lessThan($dejoDeEstarActiva);
                    if (! $cobradoAntes) {
                        throw new ReservaException("La reserva está {$r->estado}: no se registran adelantos ni pagos. "
                            . 'Si el cliente pagó antes, lo que corresponda se devuelve con una devolución.', 409, ['estado' => $r->estado]);
                    }
                    $advertencia = "La reserva está {$r->estado}: este pago (registrado sin conexión cuando aún estaba activa) queda como saldo a favor del cliente. Devuélvelo o acuérdalo con el cliente.";
                } elseif ($r->precio_final !== null) {
                    $saldo = (int) $r->centimosSaldoPendiente();
                    if ($centimos > $saldo) {
                        if (! $offline) {
                            throw new ReservaException($saldo === 0
                                ? 'Esta reserva ya está pagada por completo.'
                                : 'El monto (' . $this->soles($centimos) . ') supera el saldo pendiente (' . $this->soles($saldo) . ').', 422, ['falta' => 'monto']);
                        }
                        $advertencia = 'Este pago, registrado sin conexión, supera el saldo de la reserva: quedan '
                            . $this->soles($centimos - $saldo) . ' a favor del cliente.';
                    }
                }

                if ($advertencia === null && $metodo === 'efectivo') {
                    $parecido = $r->pagos->first(fn (Pago $p) => $p->estado === 'valido' && $p->metodo === 'efectivo'
                        && (int) round(((float) $p->monto) * 100) === $centimos && $p->tipo === $tipo
                        && $p->created_at && $p->created_at->greaterThan(now()->subMinutes(10)));
                    if ($parecido) {
                        $advertencia = 'Ya se registró hace poco un pago en efectivo igual para esta reserva. Verifica que no esté duplicado.';
                    }
                }

                $pago = Pago::create([
                    'uuid'             => $uuid,
                    'negocio_id'       => $negocio->id,
                    'reserva_id'       => $r->id,
                    'tipo'             => $tipo,
                    'metodo'           => $metodo,
                    'monto'            => number_format($centimos / 100, 2, '.', ''),
                    'numero_operacion' => $numero,
                    'pagado_en'        => $pagadoEn,
                    'estado'           => 'valido',
                    'registrado_por'   => $actor->id,
                    'creado_offline'   => $offline,
                    'dispositivo_uuid' => $ctx['dispositivo_uuid'] ?? null,
                    'observacion'      => $observacion === '' ? null : mb_substr($observacion, 0, 255),
                ]);
                $this->reservas->evento($r, 'pago_registrado', sprintf('%s de S/ %s por %s (comprobante %s).',
                    ucfirst($tipo === 'devolucion' ? 'devolución' : $tipo), $pago->monto, ucfirst($metodo), $pago->numeroComprobante())
                    . ($tipo === 'devolucion' ? " Motivo: {$observacion}" : ''), $actor, $ctx);

                // Si el cliente había informado este pago desde la app, queda verificado con este registro.
                if ($numero !== null) {
                    PagoInformado::where('reserva_id', $r->id)->where('metodo', $metodo)->where('numero_operacion', $numero)
                        ->where('estado', 'pendiente')->get()
                        ->each(fn (PagoInformado $pi) => $pi->fill([
                            'estado' => 'verificado', 'pago_id' => $pago->id, 'revisado_por' => $actor->id, 'revisado_en' => CarbonImmutable::now(),
                        ])->save());
                }
                $confirmo = $this->reservas->confirmarSiAdelantoCompleto($r, $actor, $ctx);

                return [$pago, $advertencia, false, $confirmo];
            });
        } catch (QueryException $e) {
            if ($yaGuardado = Pago::where('uuid', $uuid)->first()) {
                return [$yaGuardado, false, null];
            }
            if (str_contains($e->getMessage(), 'UNIQUE') || $e->getCode() === '23000') {
                throw new ReservaException('Este pago ya está registrado.', 409, ['duplicado' => 'pago']);
            }
            throw $e;
        }

        if ($yaExistia) {
            return [$pago, false, null];
        }

        // Aviso al cliente (en la app y como notificación del celular). Si el pago confirmó la reserva,
        // ya recibió «Reserva confirmada»: no se le envía un segundo aviso por el mismo hecho.
        if (! $confirmo) {
            $r = $pago->reserva->fresh(['pagos']);
            $texto = Avisos::texto($r);
            if ($tipo === 'devolucion') {
                $this->avisos->aClienteDe($r, 'Devolución registrada', "Registramos una devolución de S/ {$pago->monto} por " . ucfirst($metodo)
                    . " de tu reserva del {$texto} (comprobante {$pago->numeroComprobante()}).", 'pago_registrado');
            } else {
                $saldo = $r->ocupaLaCancha() ? $r->centimosSaldoPendiente() : null;
                $this->avisos->aClienteDe($r, 'Pago registrado', "Registramos tu pago de S/ {$pago->monto} por " . ucfirst($metodo)
                    . " para la reserva del {$texto} (comprobante {$pago->numeroComprobante()})."
                    . ($saldo !== null ? ($saldo > 0 ? ' Falta pagar ' . $this->soles($saldo) . '.' : ' Tu reserva está pagada.') : ''), 'pago_registrado');
            }
        }

        return [$pago->fresh(), true, $advertencia];
    }

    /** Cuándo la reserva pasó a cancelada o rechazada (último evento de ese tipo). */
    private function momentoEnQueDejoDeEstarActiva(Reserva $r): ?CarbonImmutable
    {
        $evento = $r->eventos()->whereIn('tipo', ['cancelada', 'rechazada'])->latest('id')->first();

        return $evento?->created_at?->toImmutable() ?? $r->updated_at?->toImmutable();
    }

    private function soles(int $centimos): string
    {
        return 'S/ ' . number_format($centimos / 100, 2, '.', '');
    }

    public function anular(Pago $pago, User $actor, string $motivo): Pago
    {
        if (! $actor->tieneRol('admin')) {
            throw new ReservaException('Solo la administradora puede anular pagos.', 403);
        }

        return DB::transaction(function () use ($pago, $actor, $motivo) {
            // Mismo orden de bloqueo que al registrar (primero la reserva, luego el pago).
            $r = Reserva::whereKey($pago->reserva_id)->lockForUpdate()->firstOrFail();
            $p = Pago::whereKey($pago->id)->lockForUpdate()->firstOrFail();
            if ($p->estado === 'anulado') {
                return $p;
            }
            // Nunca más devuelto que cobrado: si ese dinero ya se devolvió, anular el cobro dejaría la reserva
            // con un pagado negativo. Primero se anula la devolución (si fue un error) y luego el cobro.
            if ($p->tipo !== 'devolucion') {
                $r->load('pagos');
                $quedaria = $r->centimosPagados() - (int) round(((float) $p->monto) * 100);
                if ($quedaria < 0) {
                    throw new ReservaException('No se puede anular este cobro: ese dinero ya se devolvió al cliente y lo devuelto '
                        . 'quedaría por encima de lo cobrado. Si la devolución fue un error, anúlala primero.', 409, ['conflicto' => 'devolucion']);
                }
            }
            $p->fill([
                'estado'                   => 'anulado',
                'motivo_anulacion'         => mb_substr($motivo, 0, 255),
                'numero_operacion_anulado' => $p->numero_operacion,
                'numero_operacion'         => null,
                'anulado_por'              => $actor->id,
                'anulado_en'               => CarbonImmutable::now(),
            ])->save();
            $this->reservas->evento($p->reserva, 'pago_anulado', "Pago {$p->numeroComprobante()} de S/ {$p->monto} anulado: {$motivo}", $actor, []);

            return $p;
        });
    }

    private function reserva(array $d, Negocio $negocio): Reserva
    {
        $q = Reserva::where('negocio_id', $negocio->id);
        $reserva = ! empty($d['reserva_id']) ? $q->find($d['reserva_id'])
            : (! empty($d['reserva_uuid']) ? $q->where('uuid', $d['reserva_uuid'])->first() : null);

        return $reserva ?? throw new ReservaException('La reserva del pago no existe.', 422, ['falta' => 'reserva']);
    }
}
