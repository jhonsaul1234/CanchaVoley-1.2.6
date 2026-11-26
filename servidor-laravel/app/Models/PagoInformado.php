<?php

namespace App\Models;

use App\Models\Concerns\FormatoApi;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/**
 * CanchaVoley: pago que el cliente informa desde la app (Yape o Plin). Es solo una referencia para que el
 * personal lo verifique en la cuenta del negocio: nunca cambia el saldo ni confirma la reserva por sí solo.
 *  estado: pendiente | verificado | descartado
 */
class PagoInformado extends Model
{
    use FormatoApi;

    protected $table = 'pagos_informados';

    public const METODOS = ['yape', 'plin'];

    /** Un pago informado (o su revisión) cambia lo que ven la reserva y sus equipos: se sincroniza. */
    protected $touches = ['reserva'];

    protected $fillable = [
        'uuid', 'negocio_id', 'reserva_id', 'user_id', 'metodo', 'numero_operacion', 'monto', 'nota',
        'estado', 'pago_id', 'revisado_por', 'revisado_en', 'motivo_descarte',
    ];

    protected function casts(): array
    {
        return [
            'monto'       => 'decimal:2',
            'revisado_en' => 'datetime',
        ];
    }

    public function reserva(): BelongsTo
    {
        return $this->belongsTo(Reserva::class);
    }

    public function pago(): BelongsTo
    {
        return $this->belongsTo(Pago::class);
    }

    public function revisor(): BelongsTo
    {
        return $this->belongsTo(User::class, 'revisado_por');
    }

    public function aApi(): array
    {
        return [
            'uuid'             => $this->uuid,
            'metodo'           => $this->metodo,
            'numero_operacion' => $this->numero_operacion,
            'monto'            => self::dineroApi($this->monto),
            'nota'             => $this->nota,
            'estado'           => $this->estado,
            'informado_en'     => self::fechaApi($this->created_at),
            'revisado_en'      => self::fechaApi($this->revisado_en),
            'revisado_por'     => $this->revisor?->name,
            'motivo_descarte'  => $this->motivo_descarte,
            'comprobante'      => $this->pago?->numeroComprobante(),
        ];
    }
}
