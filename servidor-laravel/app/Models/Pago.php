<?php

namespace App\Models;

use App\Models\Concerns\FormatoApi;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/**
 * CanchaVoley: pago de una reserva (efectivo, Yape o Plin). No se edita ni se borra.
 *  tipo: adelanto | pago | devolucion      estado: valido | anulado
 */
class Pago extends Model
{
    use FormatoApi;

    protected $table = 'pagos';

    public const TIPOS   = ['adelanto', 'pago', 'devolucion'];

    /**
     * Al guardar o anular un pago se actualiza la fecha de cambio de su reserva: así la sincronización
     * lleva a los demás equipos (y a la app del cliente) el pagado, el saldo y el estado del pago al día.
     */
    protected $touches = ['reserva'];
    public const METODOS = ['efectivo', 'yape', 'plin'];

    protected $fillable = [
        'uuid', 'negocio_id', 'reserva_id', 'tipo', 'metodo', 'monto', 'numero_operacion', 'numero_operacion_anulado', 'pagado_en',
        'estado', 'motivo_anulacion', 'anulado_por', 'anulado_en', 'registrado_por', 'creado_offline',
        'dispositivo_uuid', 'observacion',
    ];

    protected function casts(): array
    {
        return [
            'monto'          => 'decimal:2',
            'pagado_en'      => 'datetime',
            'anulado_en'     => 'datetime',
            'creado_offline' => 'boolean',
        ];
    }

    public function reserva(): BelongsTo
    {
        return $this->belongsTo(Reserva::class);
    }

    public function registrador(): BelongsTo
    {
        return $this->belongsTo(User::class, 'registrado_por');
    }

    /** Número de comprobante interno (no es un comprobante electrónico de SUNAT). */
    public function numeroComprobante(): string
    {
        return 'CV-' . str_pad((string) $this->id, 6, '0', STR_PAD_LEFT);
    }

    public function aApi(): array
    {
        return [
            'id'                 => $this->id,
            'uuid'               => $this->uuid,
            'reserva_id'         => $this->reserva_id,
            'reserva_uuid'       => $this->reserva?->uuid,
            'tipo'               => $this->tipo,
            'metodo'             => $this->metodo,
            'monto'              => self::dineroApi($this->monto),
            'numero_operacion'   => $this->numero_operacion ?? $this->numero_operacion_anulado,
            'pagado_en'          => self::fechaApi($this->pagado_en),
            'estado'             => $this->estado,
            'motivo_anulacion'   => $this->motivo_anulacion,
            'numero_comprobante' => $this->numeroComprobante(),
            'registrado_por'     => $this->registrador?->name,
            'creado_offline'     => $this->creado_offline,
            'actualizado_en'     => self::fechaApi($this->updated_at),
        ];
    }
}
