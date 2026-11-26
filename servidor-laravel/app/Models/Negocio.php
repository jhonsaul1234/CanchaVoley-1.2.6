<?php

namespace App\Models;

use App\Models\Concerns\FormatoApi;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Database\Eloquent\Relations\HasOne;

/** CanchaVoley: negocio dueño de las canchas (hoy uno solo). */
class Negocio extends Model
{
    use FormatoApi;

    protected $table = 'negocios';

    protected $fillable = [
        'nombre', 'telefono', 'direccion', 'zona_horaria', 'moneda', 'activo',
        // Datos de pago que el negocio publica para el adelanto (los llena la administradora).
        'pago_yape', 'pago_plin', 'pago_titular', 'pago_instrucciones',
    ];

    protected function casts(): array
    {
        return ['activo' => 'boolean'];
    }

    /** El negocio con el que trabaja esta instalación (el primero activo). */
    public static function principal(): ?self
    {
        return static::where('activo', true)->orderBy('id')->first();
    }

    public static function principalOFallar(): self
    {
        return static::principal() ?? throw new \App\Exceptions\CanchaVoley\ReservaException(
            'El sistema todavía no está instalado: falta registrar el negocio (php artisan canchavoley:instalar).', 503
        );
    }

    public function canchas(): HasMany
    {
        return $this->hasMany(Cancha::class)->orderBy('orden')->orderBy('id');
    }

    public function reglas(): HasOne
    {
        return $this->hasOne(ReglaReserva::class);
    }

    /** Reglas del negocio; si aún no existen se crean vacías (todo «sin configurar»). */
    public function reglasOVacias(): ReglaReserva
    {
        return $this->reglas()->firstOrCreate([]);
    }

    public function aApi(): array
    {
        return [
            'id'           => $this->id,
            'nombre'       => $this->nombre,
            'telefono'     => $this->telefono,
            'direccion'    => $this->direccion,
            'zona_horaria' => $this->zona_horaria,
            'moneda'       => $this->moneda,
            // Datos de pago para el adelanto (null = el negocio aún no los publicó).
            'pago_yape'          => $this->pago_yape,
            'pago_plin'          => $this->pago_plin,
            'pago_titular'       => $this->pago_titular,
            'pago_instrucciones' => $this->pago_instrucciones,
            'actualizado_en' => self::fechaApi($this->updated_at),
            // Marca de la base: cambia solo si el negocio se vuelve a crear (por ejemplo, al reiniciar la
            // base de demostración). La app la usa para no mezclar su copia local con datos que ya no existen.
            'creado_en'    => self::fechaApi($this->created_at),
        ];
    }
}
