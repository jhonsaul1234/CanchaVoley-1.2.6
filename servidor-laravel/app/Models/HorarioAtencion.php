<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/** CanchaVoley: turno de atención de una cancha en un día de la semana (1 = lunes … 7 = domingo). */
class HorarioAtencion extends Model
{
    protected $table = 'horarios_atencion';

    protected $fillable = ['cancha_id', 'dia_semana', 'abre', 'cierra'];

    protected function casts(): array
    {
        return ['dia_semana' => 'integer'];
    }

    public function cancha(): BelongsTo
    {
        return $this->belongsTo(Cancha::class);
    }

    /** El turno termina al día siguiente (cierra <= abre). */
    public function cruzaMedianoche(): bool
    {
        return substr($this->cierra, 0, 5) <= substr($this->abre, 0, 5);
    }

    public function aApi(): array
    {
        return [
            'id'         => $this->id,
            'cancha_id'  => $this->cancha_id,
            'dia_semana' => $this->dia_semana,
            'abre'       => substr($this->abre, 0, 5),
            'cierra'     => substr($this->cierra, 0, 5),
        ];
    }
}
