<?php

namespace App\Services\CanchaVoley;

use App\Exceptions\CanchaVoley\ReservaException;
use App\Models\Bloqueo;
use App\Models\Cancha;
use App\Models\Cliente;
use App\Models\Negocio;
use App\Models\PagoInformado;
use App\Models\ReglaReserva;
use App\Models\Reserva;
use App\Models\ReservaEvento;
use App\Models\User;
use Carbon\CarbonImmutable;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

/**
 * CanchaVoley: único punto de entrada para crear o cambiar reservas.
 *
 * Protección contra reservas cruzadas: dentro de una transacción se bloquea primero la fila de la
 * cancha (`lockForUpdate`), así dos solicitudes para la misma cancha se atienden una después de otra;
 * luego se buscan choques con una lectura también bloqueante, para leer siempre lo último confirmado
 * (en REPEATABLE READ una lectura normal podría usar una foto anterior al bloqueo).
 *
 * Contexto ($ctx): offline (bool), op_uuid, dispositivo_uuid, creada_en_dispositivo (CarbonImmutable).
 */
class ServicioReservas
{
    public function __construct(
        private readonly Disponibilidad $disponibilidad,
        private readonly CalculadoraPrecio $calculadora,
        private readonly Avisos $avisos,
    ) {}

    // ── Crear ──────────────────────────────────────────────────────────────────

    /** @return array{0: Reserva, 1: bool} la reserva y si se creó ahora (false = ya existía con ese uuid) */
    public function crear(array $d, User $actor, array $ctx = []): array
    {
        $negocio = Negocio::principalOFallar();
        $uuid    = (string) ($d['uuid'] ?? Str::uuid());

        if ($existente = Reserva::where('uuid', $uuid)->first()) {
            $this->exigirVisible($existente, $actor);
            return [$existente, false];
        }

        $esCliente = ! $actor->esPersonal();
        $cliente   = $esCliente ? $this->clienteDeLaCuenta($actor) : $this->resolverCliente($d, $negocio);
        $cancha    = $this->cancha($d['cancha_id'] ?? null, $negocio);
        [$inicio, $fin] = $this->intervalo($d['inicio'] ?? null, $d['fin'] ?? null);
        $regla     = $negocio->reglasOVacias();

        $this->validarMomento($inicio, $fin, $regla, $esCliente, $ctx);
        $this->validarReglasDeDuracion($inicio, $fin, $regla);
        $this->validarHorario($cancha, $inicio, $fin, $regla);

        [$reserva, $creada] = DB::transaction(function () use ($d, $actor, $ctx, $negocio, $cliente, $cancha, $inicio, $fin, $regla, $esCliente, $uuid) {
            $this->bloquearCancha($cancha);
            // Mismo uuid enviado dos veces a la vez (p. ej. el POST original y su reintento desde la
            // cola offline): el segundo espera el bloqueo y aquí encuentra la reserva del primero.
            if ($yaGuardada = Reserva::where('uuid', $uuid)->first()) {
                return [$yaGuardada, false];
            }
            $this->liberarPendientesVencidas($cancha);
            $this->exigirLibre($cancha, $inicio, $fin, $regla, null, $esCliente);

            $calculo = $this->calculadora->calcular($cancha, $inicio, $fin, $regla);
            $manual  = $esCliente ? null : ($d['precio_final'] ?? null);
            $precio  = $manual !== null ? number_format((float) $manual, 2, '.', '') : $calculo['precio'];
            if (! $esCliente && $precio === null) {
                throw new ReservaException(
                    ($calculo['motivo'] ?? 'No hay tarifa para este horario.') . ' Indica el precio acordado con el cliente.',
                    422, ['falta' => 'precio_final']
                );
            }

            $adelanto = $regla->adelantoPara($precio);
            [$estado, $pendientePor] = $esCliente
                ? ['pendiente', 'aprobacion']
                : ($this->exigeAdelanto($regla, $adelanto) ? ['pendiente', 'adelanto'] : ['confirmada', null]);

            $reserva = Reserva::create([
                'uuid'                  => $uuid,
                'negocio_id'            => $negocio->id,
                'cancha_id'             => $cancha->id,
                'cliente_id'            => $cliente->id,
                'inicio'                => $inicio,
                'fin'                   => $fin,
                'duracion_min'          => (int) $inicio->diffInMinutes($fin),
                'estado'                => $estado,
                'pendiente_por'         => $pendientePor,
                'origen'                => $esCliente ? 'app' : $this->origen($d['origen'] ?? null),
                'precio_calculado'      => $calculo['precio'],
                'precio_final'          => $precio,
                'precio_manual'         => $manual !== null && $precio !== $calculo['precio'],
                'adelanto_requerido'    => $adelanto,
                'adelanto_vence_en'     => $pendientePor === 'adelanto' && $regla->adelanto_plazo_min
                    ? CarbonImmutable::now()->addMinutes($regla->adelanto_plazo_min) : null,
                'codigo_qr'             => $estado === 'confirmada' ? $this->nuevoCodigoQr() : null,
                'creada_offline'        => (bool) ($ctx['offline'] ?? false),
                'creada_en_dispositivo' => $ctx['creada_en_dispositivo'] ?? null,
                'dispositivo_uuid'      => $ctx['dispositivo_uuid'] ?? null,
                'creado_por'            => $actor->id,
                'notas'                 => $d['notas'] ?? null,
            ]);

            $this->evento($reserva, 'creada', $esCliente
                ? 'Solicitud enviada desde la app (pendiente de aprobación).'
                : 'Reserva registrada' . (($ctx['offline'] ?? false) ? ' sin conexión y validada por el servidor.' : '.'), $actor, $ctx);
            if ($reserva->precio_manual) {
                $this->evento($reserva, 'precio_manual', "Precio acordado S/ {$precio} (tarifa: " . ($calculo['precio'] ?? 'sin tarifa') . ').', $actor, $ctx);
            }
            if ($estado === 'confirmada') {
                $this->evento($reserva, 'confirmada', 'Reserva confirmada.', $actor, $ctx);
            } elseif ($pendientePor === 'adelanto') {
                $this->evento($reserva, 'pendiente_adelanto', "Pendiente del adelanto de S/ {$adelanto}.", $actor, $ctx);
            }

            return [$reserva, true];
        });

        if (! $creada) {
            $this->exigirVisible($reserva, $actor);
            return [$reserva, false];
        }

        if ($esCliente) {
            $this->avisos->aAdministradoras('Nueva solicitud de reserva',
                "{$cliente->nombre} solicita la cancha el " . Avisos::texto($reserva) . '.', 'reserva_solicitada', $reserva->id);
        } else {
            $this->avisos->aClienteDe($reserva, 'Reserva registrada',
                'Tu reserva del ' . Avisos::texto($reserva) . ($reserva->estado === 'confirmada' ? ' está confirmada.' : ' está pendiente del adelanto.'),
                'reserva_registrada');
        }

        return [$reserva->fresh(), true];
    }

    /**
     * Guarda una solicitud hecha sin conexión que el servidor no pudo aceptar, para que quede en el
     * historial (y los pagos ya cobrados puedan asociarse a ella como saldo a favor).
     */
    public function registrarRechazadaOffline(array $d, User $actor, array $ctx, string $motivo): ?Reserva
    {
        try {
            $negocio = Negocio::principalOFallar();
            $uuid    = (string) ($d['uuid'] ?? '');
            if ($uuid === '' || Reserva::where('uuid', $uuid)->exists()) {
                return Reserva::where('uuid', $uuid)->first();
            }
            $cliente = $actor->esPersonal() ? $this->resolverCliente($d, $negocio) : $this->clienteDeLaCuenta($actor);
            $cancha  = $this->cancha($d['cancha_id'] ?? null, $negocio);
            [$inicio, $fin] = $this->intervalo($d['inicio'] ?? null, $d['fin'] ?? null);
        } catch (ReservaException) {
            return null;    // datos insuficientes: no hay nada que guardar
        }

        $reserva = Reserva::create([
            'uuid'                  => $uuid,
            'negocio_id'            => $negocio->id,
            'cancha_id'             => $cancha->id,
            'cliente_id'            => $cliente->id,
            'inicio'                => $inicio,
            'fin'                   => $fin,
            'duracion_min'          => (int) $inicio->diffInMinutes($fin),
            'estado'                => 'rechazada',
            'origen'                => $actor->esPersonal() ? $this->origen($d['origen'] ?? null) : 'app',
            'precio_final'          => isset($d['precio_final']) ? number_format((float) $d['precio_final'], 2, '.', '') : null,
            'motivo_estado'         => mb_substr($motivo, 0, 255),
            'creada_offline'        => true,
            'creada_en_dispositivo' => $ctx['creada_en_dispositivo'] ?? null,
            'dispositivo_uuid'      => $ctx['dispositivo_uuid'] ?? null,
            'creado_por'            => $actor->id,
            'notas'                 => $d['notas'] ?? null,
        ]);
        $this->evento($reserva, 'rechazada', "Solicitud hecha sin conexión rechazada al sincronizar: {$motivo}", $actor, $ctx);
        // El aviso va a quien la hizo sin conexión: el personal llama al cliente; el cliente elige otro horario.
        $this->avisos->aUsuario($actor->id, 'Solicitud sin conexión rechazada',
            $actor->esPersonal()
                ? "La reserva de {$cliente->nombre} para el " . Avisos::texto($reserva) . " no se pudo confirmar: {$motivo} Llama al cliente."
                : 'Tu solicitud para el ' . Avisos::texto($reserva) . " no se pudo registrar: {$motivo} Elige otro horario en la app.",
            'reserva_rechazada_sync', $reserva->id);

        return $reserva;
    }

    // ── Aprobar / rechazar (solo la administradora) ───────────────────────────

    public function aprobar(Reserva $reserva, User $actor, ?string $precioFinal = null, array $ctx = []): Reserva
    {
        $this->exigirAdmin($actor);

        $reserva = DB::transaction(function () use ($reserva, $actor, $precioFinal, $ctx) {
            $r = Reserva::whereKey($reserva->id)->lockForUpdate()->firstOrFail();
            if ($r->estado !== 'pendiente' || $r->pendiente_por !== 'aprobacion') {
                throw new ReservaException('Solo se aprueban solicitudes pendientes de aprobación.', 409, ['estado' => $r->estado]);
            }
            $regla = $r->negocio->reglasOVacias();
            if ($precioFinal !== null) {
                $r->precio_final  = number_format((float) $precioFinal, 2, '.', '');
                $r->precio_manual = $r->precio_final !== $r->precio_calculado;
            }
            if ($r->precio_final === null) {
                throw new ReservaException('Indica el precio acordado para aprobar la solicitud (no hay tarifa para ese horario).', 422, ['falta' => 'precio_final']);
            }
            $r->adelanto_requerido = $regla->adelantoPara($r->precio_final);
            $r->aprobado_por       = $actor->id;
            $r->aprobado_en        = CarbonImmutable::now();
            $r->actualizado_por    = $actor->id;
            $this->evento($r, 'aprobada', 'Solicitud aprobada por la administradora.', $actor, $ctx);

            if ($this->exigeAdelanto($regla, $r->adelanto_requerido) && $r->centimosAdelanto() < $this->centimos($r->adelanto_requerido)) {
                $r->pendiente_por     = 'adelanto';
                $r->adelanto_vence_en = $regla->adelanto_plazo_min ? CarbonImmutable::now()->addMinutes($regla->adelanto_plazo_min) : null;
                $this->evento($r, 'pendiente_adelanto', "Pendiente del adelanto de S/ {$r->adelanto_requerido}.", $actor, $ctx);
            } else {
                $this->confirmar($r, $actor, $ctx);
            }
            $r->save();

            return $r;
        });

        $this->avisos->aClienteDe($reserva, 'Solicitud aprobada', 'Tu reserva del ' . Avisos::texto($reserva)
            . ($reserva->estado === 'confirmada' ? ' fue aprobada y está confirmada.' : " fue aprobada; falta el adelanto de S/ {$reserva->adelanto_requerido}."),
            'reserva_aprobada');

        return $reserva->fresh();
    }

    public function rechazar(Reserva $reserva, User $actor, string $motivo, array $ctx = []): Reserva
    {
        $this->exigirAdmin($actor);
        $reserva = DB::transaction(function () use ($reserva, $actor, $motivo, $ctx) {
            $r = Reserva::whereKey($reserva->id)->lockForUpdate()->firstOrFail();
            if ($r->estado !== 'pendiente') {
                throw new ReservaException('Solo se rechazan reservas pendientes.', 409, ['estado' => $r->estado]);
            }
            $r->fill(['estado' => 'rechazada', 'pendiente_por' => null, 'motivo_estado' => $motivo, 'actualizado_por' => $actor->id])->save();
            $this->evento($r, 'rechazada', "Rechazada: {$motivo}", $actor, $ctx);

            return $r;
        });
        $this->avisos->aClienteDe($reserva, 'Solicitud rechazada', 'Tu solicitud del ' . Avisos::texto($reserva) . " fue rechazada: {$motivo}", 'reserva_rechazada');

        return $reserva->fresh();
    }

    // ── Cancelar / reprogramar / asistencia ───────────────────────────────────

    public function cancelar(Reserva $reserva, User $actor, string $motivo, array $ctx = []): Reserva
    {
        $this->exigirVisible($reserva, $actor);
        $reserva = DB::transaction(function () use ($reserva, $actor, $motivo, $ctx) {
            $r = Reserva::whereKey($reserva->id)->lockForUpdate()->firstOrFail();
            if ($r->estado === 'cancelada') {
                return $r;                                    // idempotente
            }
            if (! $r->ocupaLaCancha()) {
                throw new ReservaException('Esta reserva ya no está activa.', 409, ['estado' => $r->estado]);
            }
            if ($r->asistencia !== null) {
                throw new ReservaException('La reserva ya tiene la asistencia registrada («' . $this->textoAsistencia($r->asistencia)
                    . '»): no se puede cancelar.', 409, ['asistencia' => $r->asistencia]);
            }
            if (! $actor->esPersonal() && $r->estado === 'confirmada') {
                $limite = $r->negocio->reglasOVacias()->cancelacion_limite_horas;
                if ($limite === null) {
                    throw new ReservaException('Para cancelar una reserva confirmada comunícate con el negocio.', 403);
                }
                if (CarbonImmutable::now()->greaterThan($r->inicio->toImmutable()->subHours($limite))) {
                    throw new ReservaException("Las reservas confirmadas se cancelan desde la app hasta {$limite} h antes. Comunícate con el negocio.", 403);
                }
            }
            $r->fill(['estado' => 'cancelada', 'pendiente_por' => null, 'motivo_estado' => $motivo, 'actualizado_por' => $actor->id])->save();
            $this->evento($r, 'cancelada', "Cancelada: {$motivo}", $actor, $ctx);

            return $r;
        });

        if ($actor->esPersonal()) {
            $this->avisos->aClienteDe($reserva, 'Reserva cancelada', 'Tu reserva del ' . Avisos::texto($reserva) . " fue cancelada: {$motivo}", 'reserva_cancelada');
        } else {
            $this->avisos->aAdministradoras('Reserva cancelada por el cliente', "{$reserva->cliente->nombre} canceló su reserva del " . Avisos::texto($reserva) . ".", 'reserva_cancelada', $reserva->id);
        }

        return $reserva->fresh();
    }

    public function reprogramar(Reserva $reserva, User $actor, mixed $inicioTxt, mixed $finTxt, array $ctx = []): Reserva
    {
        $this->exigirVisible($reserva, $actor);
        if ($reserva->asistencia !== null) {
            throw new ReservaException('La reserva ya tiene la asistencia registrada («' . $this->textoAsistencia($reserva->asistencia)
                . '»): no se puede cambiar el horario.', 409, ['asistencia' => $reserva->asistencia]);
        }
        $esCliente = ! $actor->esPersonal();
        if ($esCliente && ! ($reserva->estado === 'pendiente' && $reserva->pendiente_por === 'aprobacion')) {
            throw new ReservaException('Para cambiar la hora de una reserva confirmada comunícate con el negocio.', 403);
        }
        [$inicio, $fin] = $this->intervalo($inicioTxt, $finTxt);
        $cancha = $reserva->cancha;
        $regla  = $reserva->negocio->reglasOVacias();
        $this->validarMomento($inicio, $fin, $regla, $esCliente, $ctx);
        $this->validarReglasDeDuracion($inicio, $fin, $regla);
        $this->validarHorario($cancha, $inicio, $fin, $regla, $reserva->id);

        return DB::transaction(function () use ($reserva, $actor, $ctx, $cancha, $inicio, $fin, $regla, $esCliente) {
            $this->bloquearCancha($cancha);
            $r = Reserva::whereKey($reserva->id)->lockForUpdate()->firstOrFail();
            if (! $r->ocupaLaCancha()) {
                throw new ReservaException('Esta reserva ya no está activa.', 409, ['estado' => $r->estado]);
            }
            if ($r->asistencia !== null) {
                throw new ReservaException('La reserva ya tiene la asistencia registrada: no se puede cambiar el horario.', 409, ['asistencia' => $r->asistencia]);
            }
            $this->liberarPendientesVencidas($cancha, $r->id);
            $this->exigirLibre($cancha, $inicio, $fin, $regla, $r->id, $esCliente);

            $antes = Avisos::texto($r);
            if (! $r->precio_manual) {
                $calculo = $this->calculadora->calcular($cancha, $inicio, $fin, $regla);
                if ($calculo['precio'] !== null) {
                    $r->precio_calculado = $calculo['precio'];
                    $r->precio_final     = $calculo['precio'];
                } elseif ($r->precio_final !== null) {
                    $r->precio_manual = true;           // se conserva el precio acordado
                }
            }
            $r->fill([
                'inicio'          => $inicio,
                'fin'             => $fin,
                'duracion_min'    => (int) $inicio->diffInMinutes($fin),
                'actualizado_por' => $actor->id,
            ])->save();
            $this->evento($r, 'reprogramada', "Cambio de horario: de {$antes} a " . Avisos::texto($r) . '.', $actor, $ctx);

            return $r->fresh();
        });
    }

    /**
     * Asistencia de una reserva confirmada (22/11/2026: reglas para no dejar estados contradictorios).
     *  - «No asistió» recién desde la hora de inicio más la tolerancia de las reglas del negocio.
     *  - Marcar lo mismo otra vez no cambia nada (no suma otra «no asistió» al cliente).
     *  - Si ya está registrada: el cliente que llega tarde (antes del fin) puede pasar de «No asistió» a
     *    «Asistió»; cualquier otro cambio es una corrección que solo hace la administradora, con motivo.
     */
    public function marcarAsistencia(Reserva $reserva, User $actor, bool $asistio, array $ctx = [], bool $corregir = false, ?string $motivo = null): Reserva
    {
        $this->exigirPersonal($actor);

        return DB::transaction(function () use ($reserva, $actor, $asistio, $ctx, $corregir, $motivo) {
            $r = Reserva::whereKey($reserva->id)->lockForUpdate()->firstOrFail();
            if ($r->estado !== 'confirmada') {
                throw new ReservaException('Solo se marca la asistencia de reservas confirmadas.', 409, ['estado' => $r->estado]);
            }
            $nueva = $asistio ? 'asistio' : 'no_asistio';
            if ($r->asistencia === $nueva) {
                return $r->fresh();                                   // idempotente: nada que cambiar
            }
            $referencia = $this->momentoDeReferencia($ctx);

            if ($r->asistencia === null) {
                if (! $asistio) {
                    $tolerancia = (int) ($r->negocio->reglasOVacias()->tolerancia_no_show_min ?? 0);
                    $desde = $r->inicio->toImmutable()->addMinutes($tolerancia);
                    if ($referencia->lessThan($desde)) {
                        throw new ReservaException('Todavía no se puede marcar «No asistió»: la reserva empieza a las ' . $r->inicio->format('H:i')
                            . ($tolerancia ? " y hay {$tolerancia} min de tolerancia" : '') . '. Se podrá desde las ' . $desde->format('H:i') . '.', 409);
                    }
                }
                $this->asignarAsistencia($r, $nueva, $referencia);
                $this->evento($r, 'asistencia', $asistio ? 'El cliente llegó (asistencia registrada).' : 'Marcada como no asistió.', $actor, $ctx);
            } elseif (! $corregir && $asistio && $r->asistencia === 'no_asistio' && $referencia->lessThan($r->fin->toImmutable())) {
                // Llegó tarde, pero dentro de su horario: se registra la llegada.
                $this->asignarAsistencia($r, 'asistio', $referencia);
                $this->evento($r, 'asistencia', 'El cliente llegó tarde: se cambió «No asistió» por «Asistió».', $actor, $ctx);
            } else {
                if (! $corregir || ! $actor->tieneRol('admin')) {
                    throw new ReservaException('La asistencia ya está registrada como «' . $this->textoAsistencia($r->asistencia)
                        . '». Solo la administradora puede corregirla, indicando el motivo.', 409, ['asistencia' => $r->asistencia]);
                }
                $motivo = trim((string) $motivo);
                if (mb_strlen($motivo) < 3) {
                    throw new ReservaException('Indica el motivo de la corrección de la asistencia.', 422, ['falta' => 'motivo']);
                }
                $antes = $this->textoAsistencia($r->asistencia);
                $this->asignarAsistencia($r, $nueva, $referencia);
                $this->evento($r, 'asistencia_corregida', "Asistencia corregida por la administradora: de «{$antes}» a «"
                    . $this->textoAsistencia($nueva) . "». Motivo: {$motivo}", $actor, $ctx);
            }
            $r->actualizado_por = $actor->id;
            $r->save();

            return $r->fresh();
        });
    }

    private function asignarAsistencia(Reserva $r, string $asistencia, CarbonImmutable $referencia): void
    {
        $r->asistencia  = $asistencia;
        $r->check_in_en = $asistencia === 'asistio' ? ($r->check_in_en ?? $referencia) : null;
    }

    private function textoAsistencia(?string $asistencia): string
    {
        return $asistencia === 'asistio' ? 'Asistió' : ($asistencia === 'no_asistio' ? 'No asistió' : 'sin registrar');
    }

    /** Hora en que se hizo la acción: la del equipo si se hizo sin conexión (acotada a las últimas 24 h). */
    private function momentoDeReferencia(array $ctx): CarbonImmutable
    {
        $ahora = CarbonImmutable::now();
        if (($ctx['offline'] ?? false) && ($ctx['creada_en_dispositivo'] ?? null) instanceof CarbonImmutable) {
            return $ctx['creada_en_dispositivo']->max($ahora->subDay())->min($ahora);
        }

        return $ahora;
    }

    /**
     * Cambia el precio acordado (solo la administradora) y/o las notas (personal) de una reserva.
     * Cada cambio queda en el historial con el valor anterior y el nuevo.
     * 22/11/2026: las notas se pueden editar en cualquier estado (p. ej. cómo se devolvió un adelanto);
     * el precio solo en reservas activas, nunca por debajo de lo ya pagado y, si la reserva ya tiene pagos
     * o asistencia registrada, con un motivo que queda en el historial.
     */
    public function editar(Reserva $reserva, User $actor, array $d, array $ctx = []): Reserva
    {
        $this->exigirPersonal($actor);
        $cambiaPrecio = array_key_exists('precio_final', $d) && $d['precio_final'] !== null && $d['precio_final'] !== '';
        $cambiaNotas  = array_key_exists('notas', $d);
        if (! $cambiaPrecio && ! $cambiaNotas) {
            throw new ReservaException('No hay cambios que guardar.', 422);
        }
        if ($cambiaPrecio) {
            if (! $actor->tieneRol('admin')) {
                throw new ReservaException('Solo la administradora cambia el precio de una reserva.', 403);
            }
        }

        return DB::transaction(function () use ($reserva, $actor, $d, $ctx, $cambiaPrecio, $cambiaNotas) {
            $r = Reserva::whereKey($reserva->id)->lockForUpdate()->firstOrFail();
            if ($cambiaPrecio) {
                if (! $r->ocupaLaCancha()) {
                    throw new ReservaException('No se cambia el precio de una reserva cancelada o rechazada.', 409, ['estado' => $r->estado]);
                }
                $nuevo = number_format((float) $d['precio_final'], 2, '.', '');
                if ((float) $nuevo < 0 || (float) $nuevo > 99999) {
                    throw new ReservaException('El precio no es válido.', 422, ['falta' => 'precio_final']);
                }
                $anterior = $r->precio_final;
                if ($anterior !== $nuevo) {
                    $r->load('pagos');
                    $pagado = $r->centimosPagados();
                    if ($this->centimos($nuevo) < $pagado) {
                        throw new ReservaException('El precio no puede ser menor que lo ya pagado (S/ ' . number_format($pagado / 100, 2, '.', '')
                            . '). Si corresponde, la administradora registra primero una devolución.', 422, ['falta' => 'precio_final']);
                    }
                    $motivo = trim((string) ($d['motivo'] ?? ''));
                    if (($pagado > 0 || $r->asistencia !== null) && mb_strlen($motivo) < 3) {
                        throw new ReservaException('Indica el motivo del cambio de precio (la reserva ya tiene pagos o asistencia registrada).', 422, ['falta' => 'motivo']);
                    }
                    $r->precio_final  = $nuevo;
                    $r->precio_manual = $nuevo !== $r->precio_calculado;
                    $this->evento($r, 'precio_cambiado', 'Precio acordado: S/ ' . ($anterior ?? 'sin precio') . " → S/ {$nuevo}."
                        . ($motivo !== '' ? " Motivo: {$motivo}" : ''), $actor, $ctx);
                }
            }
            if ($cambiaNotas) {
                $notas = trim((string) ($d['notas'] ?? ''));
                $notas = $notas === '' ? null : mb_substr($notas, 0, 2000);
                if ($notas !== $r->notas) {
                    $r->notas = $notas;
                    $this->evento($r, 'notas_cambiadas', 'Notas actualizadas.', $actor, $ctx);
                }
            }
            $r->actualizado_por = $actor->id;
            $r->save();

            return $r->fresh();
        });
    }

    // ── Pago informado por el cliente (22/11/2026) ─────────────────────────────

    /**
     * El cliente informa desde la app que pagó (Yape o Plin) con su número de operación. Es solo una
     * referencia: no cambia el saldo ni confirma la reserva. El personal verifica el ingreso en la cuenta
     * del negocio y registra el pago verificado (con el mismo número, el aviso queda «verificado»).
     *
     * @return array{0: PagoInformado, 1: bool} el aviso y si se creó ahora
     */
    public function informarPago(Reserva $reserva, User $actor, array $d): array
    {
        if ($actor->esPersonal()) {
            throw new ReservaException('El pago lo informa el cliente desde su app; el personal registra el pago ya verificado.', 403);
        }
        $this->exigirVisible($reserva, $actor);
        $metodo = (string) ($d['metodo'] ?? '');
        if (! in_array($metodo, PagoInformado::METODOS, true)) {
            throw new ReservaException('Indica si pagaste por Yape o por Plin.', 422, ['falta' => 'metodo']);
        }
        $numero = trim((string) ($d['numero_operacion'] ?? ''));
        if (mb_strlen($numero) < 4 || mb_strlen($numero) > 40) {
            throw new ReservaException('Escribe el número de operación que aparece en la constancia de tu pago.', 422, ['falta' => 'numero_operacion']);
        }
        $centimos = $this->centimos($d['monto'] ?? 0);
        if ($centimos <= 0) {
            throw new ReservaException('Indica el monto que pagaste.', 422, ['falta' => 'monto']);
        }
        $nota = trim((string) ($d['nota'] ?? ''));

        [$informado, $creado, $r] = DB::transaction(function () use ($reserva, $actor, $metodo, $numero, $centimos, $nota) {
            $r = Reserva::whereKey($reserva->id)->lockForUpdate()->firstOrFail();
            if (! $r->ocupaLaCancha()) {
                throw new ReservaException("Tu reserva está {$r->estado}: no corresponde informar un pago. Comunícate con el negocio.", 409, ['estado' => $r->estado]);
            }
            $r->load('pagos');
            $saldo = $r->centimosSaldoPendiente();
            if ($saldo !== null && $saldo <= 0) {
                throw new ReservaException('Tu reserva ya figura como pagada.', 409);
            }
            if ($saldo !== null && $centimos > $saldo) {
                throw new ReservaException('El monto informado supera lo que falta pagar (S/ ' . number_format($saldo / 100, 2, '.', '') . ').', 422, ['falta' => 'monto']);
            }
            if (\App\Models\Pago::where('negocio_id', $r->negocio_id)->where('metodo', $metodo)->where('numero_operacion', $numero)->exists()) {
                throw new ReservaException('Esa operación ya está registrada por el negocio.', 409, ['duplicado' => 'numero_operacion']);
            }
            $ya = PagoInformado::where('reserva_id', $r->id)->where('metodo', $metodo)->where('numero_operacion', $numero)
                ->whereIn('estado', ['pendiente', 'verificado'])->first();
            if ($ya) {
                return [$ya, false, $r];                              // el mismo aviso enviado otra vez
            }
            $informado = PagoInformado::create([
                'uuid'             => (string) Str::uuid(),
                'negocio_id'       => $r->negocio_id,
                'reserva_id'       => $r->id,
                'user_id'          => $actor->id,
                'metodo'           => $metodo,
                'numero_operacion' => $numero,
                'monto'            => number_format($centimos / 100, 2, '.', ''),
                'nota'             => $nota === '' ? null : mb_substr($nota, 0, 255),
                'estado'           => 'pendiente',
            ]);
            $this->evento($r, 'pago_informado', sprintf('El cliente informó un pago por %s de S/ %s (op. %s). Falta verificarlo en la cuenta del negocio.',
                ucfirst($metodo), $informado->monto, $numero), $actor, []);

            return [$informado, true, $r];
        });

        if ($creado) {
            $this->avisos->aPersonal('Pago informado por un cliente', ($r->cliente?->nombre ?? 'Un cliente') . ' informó un pago por ' . ucfirst($metodo)
                . " de S/ {$informado->monto} (op. {$numero}) para su reserva del " . Avisos::texto($r)
                . '. Verifícalo en la cuenta del negocio antes de registrarlo.', 'pago_informado', $r->id);
        }

        return [$informado->fresh(), $creado];
    }

    /** El personal no encontró el ingreso: el aviso queda «descartado» y se le explica al cliente. */
    public function descartarPagoInformado(PagoInformado $informado, User $actor, string $motivo): PagoInformado
    {
        $this->exigirPersonal($actor);
        $motivo = trim($motivo);
        if (mb_strlen($motivo) < 3) {
            throw new ReservaException('Indica el motivo (se le enviará al cliente).', 422, ['falta' => 'motivo']);
        }
        $informado = DB::transaction(function () use ($informado, $actor, $motivo) {
            $pi = PagoInformado::whereKey($informado->id)->lockForUpdate()->firstOrFail();
            if ($pi->estado !== 'pendiente') {
                throw new ReservaException('Ese pago informado ya fue revisado.', 409, ['estado' => $pi->estado]);
            }
            $pi->fill(['estado' => 'descartado', 'revisado_por' => $actor->id, 'revisado_en' => CarbonImmutable::now(), 'motivo_descarte' => mb_substr($motivo, 0, 255)])->save();
            $this->evento($pi->reserva, 'pago_informado_descartado', "Pago informado (op. {$pi->numero_operacion}) no verificado: {$motivo}", $actor, []);

            return $pi;
        });
        $this->avisos->aClienteDe($informado->reserva, 'Pago no verificado', 'No pudimos verificar tu pago por ' . ucfirst($informado->metodo)
            . " (op. {$informado->numero_operacion}) de la reserva del " . Avisos::texto($informado->reserva) . ": {$motivo} Comunícate con el negocio.",
            'pago_informado_descartado');

        return $informado->fresh();
    }

    /** Confirma una reserva pendiente del adelanto cuando ya se pagó lo requerido. */
    /** @return bool si la reserva quedó confirmada con este pago */
    public function confirmarSiAdelantoCompleto(Reserva $reserva, User $actor, array $ctx = []): bool
    {
        $reserva->load('pagos');
        if ($reserva->estado === 'pendiente' && $reserva->pendiente_por === 'adelanto'
            && $reserva->centimosAdelanto() >= $this->centimos($reserva->adelanto_requerido)) {
            $this->confirmar($reserva, $actor, $ctx);
            $reserva->save();
            $this->avisos->aClienteDe($reserva, 'Reserva confirmada', 'Recibimos tu adelanto: la reserva del ' . Avisos::texto($reserva) . ' está confirmada.', 'reserva_confirmada');

            return true;
        }

        return false;
    }

    // ── Apoyo ───────────────────────────────────────────────────────────────────

    public function exigirVisible(Reserva $reserva, User $actor): void
    {
        if ($actor->esPersonal()) {
            return;
        }
        if ($reserva->cliente?->user_id !== $actor->id) {
            throw new ReservaException('No tienes acceso a esta reserva.', 403);
        }
    }

    public function clienteDeLaCuenta(User $actor): Cliente
    {
        return $actor->cliente ?? throw new ReservaException('Tu cuenta no tiene datos de cliente.', 409);
    }

    /** Cliente por id o uuid; acepta el uuid provisional de un cliente creado sin conexión y unido a otro. */
    public function resolverCliente(array $d, Negocio $negocio): Cliente
    {
        $cliente = null;
        if (! empty($d['cliente_id'])) {
            $cliente = Cliente::where('negocio_id', $negocio->id)->find($d['cliente_id']);
        } elseif (! empty($d['cliente_uuid'])) {
            $cliente = Cliente::where('negocio_id', $negocio->id)->where('uuid', $d['cliente_uuid'])->first()
                ?? app(ServicioClientes::class)->porUuidProvisional($d['cliente_uuid']);
        }

        return $cliente ?? throw new ReservaException('Indica un cliente válido.', 422, ['falta' => 'cliente']);
    }

    private function cancha(mixed $id, Negocio $negocio): Cancha
    {
        $cancha = $id ? Cancha::where('negocio_id', $negocio->id)->with('horarios')->find($id) : null;
        if (! $cancha || ! $cancha->activa) {
            throw new ReservaException('Indica una cancha válida y activa.', 422, ['falta' => 'cancha_id']);
        }

        return $cancha;
    }

    /** @return array{0: CarbonImmutable, 1: CarbonImmutable} */
    private function intervalo(mixed $inicio, mixed $fin): array
    {
        try {
            $a = CarbonImmutable::parse((string) $inicio)->setTimezone(config('app.timezone'))->startOfMinute();
            $b = CarbonImmutable::parse((string) $fin)->setTimezone(config('app.timezone'))->startOfMinute();
        } catch (\Throwable) {
            throw new ReservaException('Fecha u hora inválida.', 422, ['falta' => 'inicio']);
        }
        if (! $inicio || ! $fin || $b->lessThanOrEqualTo($a)) {
            throw new ReservaException('La hora de fin debe ser posterior a la de inicio.', 422, ['falta' => 'fin']);
        }
        if ($a->diffInMinutes($b) > 24 * 60) {
            throw new ReservaException('Una reserva no puede durar más de 24 horas.', 422);
        }

        return [$a, $b];
    }

    private function validarMomento(CarbonImmutable $inicio, CarbonImmutable $fin, ReglaReserva $regla, bool $esCliente, array $ctx): void
    {
        $ahora = CarbonImmutable::now();
        if ($esCliente) {
            $minimo = $ahora->addMinutes($regla->anticipacion_minima_min ?? 0);
            if ($inicio->lessThan($minimo)) {
                throw new ReservaException($regla->anticipacion_minima_min
                    ? "Las solicitudes se hacen con al menos {$regla->anticipacion_minima_min} minutos de anticipación."
                    : 'No se puede reservar en el pasado.', 422);
            }
            if ($regla->anticipacion_maxima_dias !== null && $inicio->greaterThan($ahora->addDays($regla->anticipacion_maxima_dias))) {
                throw new ReservaException("Solo se puede reservar hasta {$regla->anticipacion_maxima_dias} días antes.", 422);
            }
            return;
        }
        // Personal: puede registrar una reserva en curso (cliente que llegó sin reservar), nunca una ya terminada.
        // Sin conexión, la referencia es la hora en que se registró en el equipo (no la de sincronización).
        $referencia = $ahora;
        if (($ctx['offline'] ?? false) && ($ctx['creada_en_dispositivo'] ?? null) instanceof CarbonImmutable) {
            $referencia = $ctx['creada_en_dispositivo']->max($ahora->subDay())->min($ahora);
        }
        if ($fin->lessThanOrEqualTo($referencia)) {
            throw new ReservaException('Ese horario ya terminó.', 422);
        }
    }

    private function validarReglasDeDuracion(CarbonImmutable $inicio, CarbonImmutable $fin, ReglaReserva $regla): void
    {
        $dur = (int) $inicio->diffInMinutes($fin);
        if ($regla->duracion_minima_min !== null && $dur < $regla->duracion_minima_min) {
            throw new ReservaException("La duración mínima es de {$regla->duracion_minima_min} minutos.", 422);
        }
        if ($regla->duracion_maxima_min !== null && $dur > $regla->duracion_maxima_min) {
            throw new ReservaException("La duración máxima es de {$regla->duracion_maxima_min} minutos.", 422);
        }
        if ($regla->paso_min && $dur % $regla->paso_min !== 0) {
            throw new ReservaException("La duración debe ser múltiplo de {$regla->paso_min} minutos.", 422);
        }
        if ($regla->alinear_inicio_min && ($inicio->hour * 60 + $inicio->minute) % $regla->alinear_inicio_min !== 0) {
            throw new ReservaException("La hora de inicio debe ser múltiplo de {$regla->alinear_inicio_min} minutos.", 422);
        }
    }

    private function validarHorario(Cancha $cancha, CarbonImmutable $inicio, CarbonImmutable $fin, ReglaReserva $regla, ?int $excluir = null): void
    {
        if (! $this->disponibilidad->tieneHorario($cancha)) {
            throw new ReservaException('La cancha no tiene horario de atención configurado. La administradora debe cargarlo en Configuración.', 422, ['falta' => 'horario']);
        }
        if (! $this->disponibilidad->dentroDeHorario($cancha, $inicio, $fin)) {
            throw new ReservaException('El horario solicitado está fuera del horario de atención.', 422, [
                'sugerencias' => $this->disponibilidad->sugerencias($cancha, $regla, $inicio->startOfDay(), (int) $inicio->diffInMinutes($fin), $inicio, 7, 6, $excluir),
            ]);
        }
    }

    private function bloquearCancha(Cancha $cancha): void
    {
        Cancha::whereKey($cancha->id)->lockForUpdate()->first();
    }

    /** Libera reservas pendientes del adelanto cuyo plazo venció (sin depender de tareas programadas). */
    private function liberarPendientesVencidas(Cancha $cancha, ?int $excepto = null): void
    {
        Reserva::where('cancha_id', $cancha->id)
            ->when($excepto, fn ($q) => $q->where('id', '!=', $excepto))
            ->where('estado', 'pendiente')->where('pendiente_por', 'adelanto')
            ->whereNotNull('adelanto_vence_en')->where('adelanto_vence_en', '<', CarbonImmutable::now())
            ->lockForUpdate()->get()
            ->each(function (Reserva $r) {
                $r->load('pagos');
                if ($r->centimosAdelanto() >= $this->centimos($r->adelanto_requerido)) {
                    return;
                }
                $r->fill(['estado' => 'cancelada', 'pendiente_por' => null, 'motivo_estado' => 'Venció el plazo del adelanto.'])->save();
                $this->evento($r, 'cancelada', 'Cancelada automáticamente: venció el plazo del adelanto.', null, []);
                $this->avisos->aClienteDe($r, 'Reserva liberada', 'Tu reserva del ' . Avisos::texto($r) . ' se liberó porque venció el plazo del adelanto.', 'reserva_cancelada');
            });
    }

    private function exigirLibre(Cancha $cancha, CarbonImmutable $inicio, CarbonImmutable $fin, ReglaReserva $regla, ?int $excluir, bool $esCliente): void
    {
        $m = $regla->margen();

        $bloqueo = Bloqueo::where('negocio_id', $cancha->negocio_id)
            ->where(fn ($q) => $q->whereNull('cancha_id')->orWhere('cancha_id', $cancha->id))
            ->where('inicio', '<', $fin)->where('fin', '>', $inicio)
            ->first();
        if ($bloqueo) {
            throw new ReservaException("La cancha está cerrada en ese horario ({$bloqueo->motivo}).", 409, [
                'conflicto'   => ['tipo' => 'bloqueo', 'inicio' => $bloqueo->inicio->toIso8601String(), 'fin' => $bloqueo->fin->toIso8601String(), 'motivo' => $bloqueo->motivo],
                'sugerencias' => $this->disponibilidad->sugerencias($cancha, $regla, $inicio->startOfDay(), (int) $inicio->diffInMinutes($fin), $inicio, 7, 6, $excluir),
            ]);
        }

        $choque = Reserva::where('cancha_id', $cancha->id)
            ->whereIn('estado', Reserva::ESTADOS_QUE_OCUPAN)
            ->where('inicio', '<', $fin->addMinutes($m))
            ->where('fin', '>', $inicio->subMinutes($m))
            ->when($excluir, fn ($q) => $q->where('id', '!=', $excluir))
            ->lockForUpdate()
            ->with('cliente')
            ->first();
        if ($choque) {
            $conflicto = ['tipo' => 'reserva', 'inicio' => $choque->inicio->toIso8601String(), 'fin' => $choque->fin->toIso8601String(), 'estado' => $choque->estado];
            if (! $esCliente) {
                $conflicto += ['uuid' => $choque->uuid, 'cliente' => $choque->cliente?->nombre];
            }
            throw new ReservaException('Ese horario ya está ocupado' . ($m ? " (con el margen de {$m} min entre reservas)." : '.'), 409, [
                'conflicto'   => $conflicto,
                'sugerencias' => $this->disponibilidad->sugerencias($cancha, $regla, $inicio->startOfDay(), (int) $inicio->diffInMinutes($fin), $inicio, 7, 6, $excluir),
            ]);
        }
    }

    private function confirmar(Reserva $r, ?User $actor, array $ctx): void
    {
        $r->estado        = 'confirmada';
        $r->pendiente_por = null;
        $r->adelanto_vence_en = null;
        $r->codigo_qr   ??= $this->nuevoCodigoQr();
        $this->evento($r, 'confirmada', 'Reserva confirmada.', $actor, $ctx);
    }

    private function exigeAdelanto(ReglaReserva $regla, ?string $adelanto): bool
    {
        return $regla->adelanto_obligatorio === true && $adelanto !== null && (float) $adelanto > 0;
    }

    private function nuevoCodigoQr(): string
    {
        do {
            $codigo = strtoupper(Str::random(20));
        } while (Reserva::where('codigo_qr', $codigo)->exists());

        return $codigo;
    }

    private function origen(?string $origen): string
    {
        return in_array($origen, Reserva::ORIGENES, true) && $origen !== 'app' ? $origen : 'llamada';
    }

    private function centimos(mixed $valor): int
    {
        return $valor === null ? 0 : (int) round(((float) $valor) * 100);
    }

    public function evento(Reserva $r, string $tipo, string $descripcion, ?User $actor, array $ctx): void
    {
        ReservaEvento::create([
            'reserva_id'  => $r->id,
            'tipo'        => $tipo,
            'descripcion' => mb_substr($descripcion, 0, 255),
            'user_id'     => $actor?->id,
            'op_uuid'     => $ctx['op_uuid'] ?? null,
        ]);
    }

    private function exigirAdmin(User $actor): void
    {
        if (! $actor->tieneRol('admin')) {
            throw new ReservaException('Solo la administradora puede aprobar o rechazar solicitudes.', 403);
        }
    }

    private function exigirPersonal(User $actor): void
    {
        if (! $actor->esPersonal()) {
            throw new ReservaException('No tienes permisos para esta acción.', 403);
        }
    }
}
