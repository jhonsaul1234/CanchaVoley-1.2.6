<?php

namespace App\Models;

use App\Models\Concerns\FormatoApi;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

/** CanchaVoley: una cancha del negocio. Todo lo que se reserva apunta a una cancha. */
class Cancha extends Model
{
    use FormatoApi;

    protected $table = 'canchas';

    protected $fillable = ['negocio_id', 'nombre', 'deporte', 'descripcion', 'activa', 'orden'];

    protected function casts(): array
    {
        return ['activa' => 'boolean', 'orden' => 'integer'];
    }

    public function negocio(): BelongsTo
    {
        return $this->belongsTo(Negocio::class);
    }

    public function horarios(): HasMany
    {
        return $this->hasMany(HorarioAtencion::class)->orderBy('dia_semana')->orderBy('abre');
    }

    public function tarifas(): HasMany
    {
        return $this->hasMany(Tarifa::class)->orderBy('dia_semana')->orderBy('desde');
    }

    public function reservas(): HasMany
    {
        return $this->hasMany(Reserva::class);
    }

    public function aApi(): array
    {
        return [
            'id'          => $this->id,
            'nombre'      => $this->nombre,
            'deporte'     => $this->deporte,
            'descripcion' => $this->descripcion,
            'activa'      => $this->activa,
            'orden'       => $this->orden,
            'actualizado_en' => self::fechaApi($this->updated_at),
        ];
    }
}
