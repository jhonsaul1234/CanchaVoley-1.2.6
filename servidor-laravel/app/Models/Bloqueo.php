<?php

namespace App\Models;

use App\Models\Concerns\FormatoApi;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\SoftDeletes;

/** CanchaVoley: cierre puntual de una cancha o de todo el local (cancha_id NULL). */
class Bloqueo extends Model
{
    use FormatoApi, SoftDeletes;

    protected $table = 'bloqueos';

    protected $fillable = ['negocio_id', 'cancha_id', 'inicio', 'fin', 'motivo', 'creado_por'];

    protected function casts(): array
    {
        return ['inicio' => 'datetime', 'fin' => 'datetime'];
    }

    public function cancha(): BelongsTo
    {
        return $this->belongsTo(Cancha::class);
    }

    public function aApi(): array
    {
        return [
            'id'        => $this->id,
            'cancha_id' => $this->cancha_id,
            'inicio'    => self::fechaApi($this->inicio),
            'fin'       => self::fechaApi($this->fin),
            'motivo'    => $this->motivo,
            'eliminado' => $this->trashed(),
            'actualizado_en' => self::fechaApi($this->updated_at),
        ];
    }
}
