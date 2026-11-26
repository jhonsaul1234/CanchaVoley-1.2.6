<?php

namespace App\Models;

use App\Models\Concerns\FormatoApi;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

/**
 * CanchaVoley: reserva de una cancha en el intervalo [inicio, fin).
 *  estado: pendiente | confirmada | cancelada | rechazada
 *  pendiente_por: aprobacion | adelanto
 */
class Reserva extends Model
{
    use FormatoApi;

    protected $table = 'reservas';

    /** Estados que ocupan la cancha (los demás la dejan libre). */
    public const ESTADOS_QUE_OCUPAN = ['pendiente', 'confirmada'];

    public const ORIGENES = ['llamada', 'presencial', 'whatsapp', 'app', 'otro'];

    protected $fillable = [
        'uuid', 'negocio_id', 'cancha_id', 'cliente_id', 'inicio', 'fin', 'duracion_min', 'estado',
        'pendiente_por', 'asistencia', 'check_in_en', 'origen', 'precio_calculado', 'precio_final',
        'precio_manual', 'adelanto_requerido', 'adelanto_vence_en', 'codigo_qr', 'motivo_estado',
        'creada_offline', 'creada_en_dispositivo', 'dispositivo_uuid', 'creado_por', 'actualizado_por',
        'aprobado_por', 'aprobado_en', 'notas',
    ];

    protected function casts(): array
    {
        return [
            'inicio'                => 'datetime',
            'fin'                   => 'datetime',
            'check_in_en'           => 'datetime',
            'adelanto_vence_en'     => 'datetime',
            'creada_en_dispositivo' => 'datetime',
            'aprobado_en'           => 'datetime',
            'duracion_min'          => 'integer',
            'precio_calculado'      => 'decimal:2',
            'precio_final'          => 'decimal:2',
            'adelanto_requerido'    => 'decimal:2',
            'precio_manual'         => 'boolean',
            'creada_offline'        => 'boolean',
        ];
    }

    public function negocio(): BelongsTo
    {
        return $this->belongsTo(Negocio::class);
    }

    public function cancha(): BelongsTo
    {
        return $this->belongsTo(Cancha::class);
    }

    public function cliente(): BelongsTo
    {
        return $this->belongsTo(Cliente::class)->withTrashed();
    }

    public function pagos(): HasMany
    {
        return $this->hasMany(Pago::class)->orderBy('pagado_en')->orderBy('id');
    }

    public function eventos(): HasMany
    {
        return $this->hasMany(ReservaEvento::class)->orderBy('id');
    }

    /** Pagos que el cliente informó desde la app (referencia para verificar; no cambian el saldo). */
    public function pagosInformados(): HasMany
    {
        return $this->hasMany(PagoInformado::class)->orderBy('id');
    }

    /** Pendiente del adelanto con el plazo ya vencido (el horario puede liberarse si otra persona lo pide). */
    public function adelantoVencido(?\Carbon\CarbonInterface $ahora = null): bool
    {
        return $this->estado === 'pendiente' && $this->pendiente_por === 'adelanto'
            && $this->adelanto_vence_en !== null && $this->adelanto_vence_en->lessThan($ahora ?? now());
    }

    /** Céntimos que faltan pagar (null si no hay precio). Nunca negativo. */
    public function centimosSaldoPendiente(): ?int
    {
        if ($this->precio_final === null) {
            return null;
        }

        return max(0, (int) round(((float) $this->precio_final) * 100) - $this->centimosPagados());
    }

    public function ocupaLaCancha(): bool
    {
        return in_array($this->estado, self::ESTADOS_QUE_OCUPAN, true);
    }

    /** Céntimos cobrados: adelantos + pagos válidos − devoluciones válidas. */
    public function centimosPagados(): int
    {
        $total = 0;
        foreach ($this->pagos as $pago) {
            if ($pago->estado !== 'valido') {
                continue;
            }
            $c = (int) round(((float) $pago->monto) * 100);
            $total += $pago->tipo === 'devolucion' ? -$c : $c;
        }

        return $total;
    }

    public function centimosAdelanto(): int
    {
        return (int) $this->pagos
            ->where('estado', 'valido')
            ->whereIn('tipo', ['adelanto', 'pago'])
            ->sum(fn (Pago $p) => (int) round(((float) $p->monto) * 100));
    }

    /** sin_precio | sin_pago | con_adelanto | pagado | saldo_a_favor */
    public function estadoPago(): string
    {
        $pagado = $this->centimosPagados();
        if (! $this->ocupaLaCancha()) {
            return $pagado > 0 ? 'saldo_a_favor' : 'sin_pago';      // cancelada o rechazada: lo cobrado es del cliente
        }
        if ($this->precio_final === null) {
            return $pagado > 0 ? 'con_adelanto' : 'sin_precio';
        }
        $precio = (int) round(((float) $this->precio_final) * 100);

        return match (true) {
            $pagado <= 0       => 'sin_pago',
            $pagado < $precio  => 'con_adelanto',
            $pagado === $precio => 'pagado',
            default            => 'saldo_a_favor',
        };
    }

    /** El último pago informado por el cliente que el personal aún no revisa. */
    public function pagoInformadoPendiente(): ?PagoInformado
    {
        return $this->relationLoaded('pagosInformados')
            ? $this->pagosInformados->where('estado', 'pendiente')->last()
            : $this->pagosInformados()->where('estado', 'pendiente')->latest('id')->first();
    }

    public function aApi(bool $conDetalle = false): array
    {
        $this->loadMissing(['cliente', 'pagos']);
        $pagado = $this->centimosPagados();
        $precio = $this->precio_final === null ? null : (int) round(((float) $this->precio_final) * 100);

        $datos = [
            'id'                 => $this->id,
            'uuid'               => $this->uuid,
            'cancha_id'          => $this->cancha_id,
            'cliente_id'         => $this->cliente_id,
            'cliente_uuid'       => $this->cliente?->uuid,
            'cliente_nombre'     => $this->cliente?->nombre,
            'cliente_telefono'   => $this->cliente?->telefono,
            'inicio'             => self::fechaApi($this->inicio),
            'fin'                => self::fechaApi($this->fin),
            'duracion_min'       => $this->duracion_min,
            'estado'             => $this->estado,
            'pendiente_por'      => $this->pendiente_por,
            'asistencia'         => $this->asistencia,
            'check_in_en'        => self::fechaApi($this->check_in_en),
            'origen'             => $this->origen,
            'precio_calculado'   => self::dineroApi($this->precio_calculado),
            'precio_final'       => self::dineroApi($this->precio_final),
            'precio_manual'      => $this->precio_manual,
            'adelanto_requerido' => self::dineroApi($this->adelanto_requerido),
            'adelanto_vence_en'  => self::fechaApi($this->adelanto_vence_en),
            'total_pagado'       => self::dineroApi($pagado / 100),
            'saldo'              => ! $this->ocupaLaCancha() ? self::dineroApi(-$pagado / 100)
                : ($precio === null ? null : self::dineroApi(($precio - $pagado) / 100)),
            'estado_pago'        => $this->estadoPago(),
            'adelanto_vencido'   => $this->adelantoVencido(),
            'pago_informado'     => $this->pagoInformadoPendiente()?->aApi(),
            'codigo_qr'          => $this->codigo_qr,
            'motivo_estado'      => $this->motivo_estado,
            'creada_offline'     => $this->creada_offline,
            'notas'              => $this->notas,
            'creado_en'          => self::fechaApi($this->created_at),
            'actualizado_en'     => self::fechaApi($this->updated_at),
        ];

        if ($conDetalle) {
            $datos['pagos']   = $this->pagos->map->aApi()->values()->all();
            $datos['pagos_informados'] = $this->pagosInformados()->with(['pago', 'revisor:id,name'])->get()->map->aApi()->values()->all();
            $datos['eventos'] = $this->eventos()->with('usuario:id,name')->get()->map->aApi()->values()->all();
        }

        return $datos;
    }
}
