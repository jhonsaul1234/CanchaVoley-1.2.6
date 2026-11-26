<?php

namespace App\Models;

use App\Models\Concerns\FormatoApi;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/**
 * CanchaVoley: franja de precio por hora de una cancha en un día de la semana.
 * `hasta` = 00:00 significa hasta la medianoche.
 */
class Tarifa extends Model
{
    use FormatoApi;

    protected $table = 'tarifas';

    protected $fillable = ['cancha_id', 'dia_semana', 'desde', 'hasta', 'precio_hora', 'vigente_desde', 'vigente_hasta'];

    protected function casts(): array
    {
        return [
            'dia_semana'    => 'integer',
            'precio_hora'   => 'decimal:2',
            'vigente_desde' => 'date',
            'vigente_hasta' => 'date',
        ];
    }

    public function cancha(): BelongsTo
    {
        return $this->belongsTo(Cancha::class);
    }

    /** Minutos desde las 00:00 donde empieza y termina la franja (fin 1440 si `hasta` es 00:00). */
    public function minutosDesde(): int
    {
        [$h, $m] = array_map('intval', explode(':', substr($this->desde, 0, 5)));
        return $h * 60 + $m;
    }

    public function minutosHasta(): int
    {
        [$h, $m] = array_map('intval', explode(':', substr($this->hasta, 0, 5)));
        $min = $h * 60 + $m;
        return $min === 0 ? 1440 : $min;
    }

    public function aApi(): array
    {
        return [
            'id'            => $this->id,
            'cancha_id'     => $this->cancha_id,
            'dia_semana'    => $this->dia_semana,
            'desde'         => substr($this->desde, 0, 5),
            'hasta'         => substr($this->hasta, 0, 5),
            'precio_hora'   => self::dineroApi($this->precio_hora),
            'vigente_desde' => $this->vigente_desde?->toDateString(),
            'vigente_hasta' => $this->vigente_hasta?->toDateString(),
        ];
    }
}
