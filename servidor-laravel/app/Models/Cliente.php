<?php

namespace App\Models;

use App\Models\Concerns\FormatoApi;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Database\Eloquent\SoftDeletes;

/** CanchaVoley: cliente del negocio, con cuenta en la app (user_id) o solo registrado por teléfono. */
class Cliente extends Model
{
    use FormatoApi, SoftDeletes;

    protected $table = 'clientes';

    protected $fillable = [
        'uuid', 'negocio_id', 'user_id', 'nombre', 'telefono', 'telefono_alterno',
        'notas', 'es_frecuente', 'creado_por',
    ];

    protected function casts(): array
    {
        return ['es_frecuente' => 'boolean'];
    }

    /**
     * Deja solo dígitos y agrega el código de Perú (51) a los celulares de 9 dígitos,
     * igual que el formato que ya usaba ProLech para WhatsApp (51XXXXXXXXX).
     */
    public static function normalizarTelefono(?string $telefono): ?string
    {
        if ($telefono === null) {
            return null;
        }
        $digitos = preg_replace('/\D+/', '', $telefono);
        if (strlen($digitos) === 9 && str_starts_with($digitos, '9')) {
            $digitos = '51' . $digitos;
        }

        return $digitos === '' ? null : $digitos;
    }

    public function negocio(): BelongsTo
    {
        return $this->belongsTo(Negocio::class);
    }

    public function usuario(): BelongsTo
    {
        return $this->belongsTo(User::class, 'user_id');
    }

    public function reservas(): HasMany
    {
        return $this->hasMany(Reserva::class);
    }

    public function aApi(): array
    {
        return [
            'id'               => $this->id,
            'uuid'             => $this->uuid,
            'nombre'           => $this->nombre,
            'telefono'         => $this->telefono,
            'telefono_alterno' => $this->telefono_alterno,
            'notas'            => $this->notas,
            'es_frecuente'     => $this->es_frecuente,
            'tiene_cuenta'     => $this->user_id !== null,
            'eliminado'        => $this->trashed(),
            'actualizado_en'   => self::fechaApi($this->updated_at),
        ];
    }
}
