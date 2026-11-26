<?php

namespace App\Models;

use App\Models\Concerns\FormatoApi;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/** CanchaVoley: un paso del historial de una reserva. Solo se agregan filas. */
class ReservaEvento extends Model
{
    use FormatoApi;

    protected $table = 'reserva_eventos';

    public const UPDATED_AT = null;

    protected $fillable = ['reserva_id', 'tipo', 'descripcion', 'datos', 'user_id', 'op_uuid'];

    protected function casts(): array
    {
        return ['datos' => 'array'];
    }

    public function usuario(): BelongsTo
    {
        return $this->belongsTo(User::class, 'user_id');
    }

    public function aApi(): array
    {
        return [
            'tipo'        => $this->tipo,
            'descripcion' => $this->descripcion,
            'usuario'     => $this->usuario?->name,
            'fecha'       => self::fechaApi($this->created_at),
        ];
    }
}
