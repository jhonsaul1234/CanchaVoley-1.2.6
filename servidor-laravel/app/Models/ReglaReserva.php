<?php

namespace App\Models;

use App\Models\Concerns\FormatoApi;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/** CanchaVoley: reglas de reserva del negocio. NULL = «sin configurar» (el sistema no supone valores). */
class ReglaReserva extends Model
{
    use FormatoApi;

    protected $table = 'reglas_reserva';

    public const CAMPOS = [
        'duracion_minima_min', 'duracion_maxima_min', 'paso_min', 'alinear_inicio_min',
        'margen_entre_reservas_min', 'anticipacion_minima_min', 'anticipacion_maxima_dias',
        'regla_cruce_franjas', 'adelanto_tipo', 'adelanto_valor', 'adelanto_plazo_min',
        'adelanto_obligatorio', 'cancelacion_limite_horas', 'cancelacion_devolucion',
        'cancelacion_porcentaje', 'reprogramacion_limite_horas', 'tolerancia_no_show_min',
    ];

    protected $fillable = [...self::CAMPOS, 'negocio_id', 'updated_by'];

    protected function casts(): array
    {
        return [
            'duracion_minima_min'         => 'integer',
            'duracion_maxima_min'         => 'integer',
            'paso_min'                    => 'integer',
            'alinear_inicio_min'          => 'integer',
            'margen_entre_reservas_min'   => 'integer',
            'anticipacion_minima_min'     => 'integer',
            'anticipacion_maxima_dias'    => 'integer',
            'adelanto_valor'              => 'decimal:2',
            'adelanto_plazo_min'          => 'integer',
            'adelanto_obligatorio'        => 'boolean',
            'cancelacion_limite_horas'    => 'integer',
            'cancelacion_porcentaje'      => 'decimal:2',
            'reprogramacion_limite_horas' => 'integer',
            'tolerancia_no_show_min'      => 'integer',
        ];
    }

    public function negocio(): BelongsTo
    {
        return $this->belongsTo(Negocio::class);
    }

    public function margen(): int
    {
        return $this->margen_entre_reservas_min ?? 0;
    }

    /** Adelanto requerido para un precio, según la regla (NULL si no hay regla o no hay precio). */
    public function adelantoPara(?string $precio): ?string
    {
        if ($precio === null || $this->adelanto_tipo === null || $this->adelanto_valor === null) {
            return null;
        }
        $centimos = (int) round(((float) $precio) * 100);
        $valor    = (float) $this->adelanto_valor;
        $adelanto = $this->adelanto_tipo === 'porcentaje'
            ? (int) round($centimos * $valor / 100)
            : min($centimos, (int) round($valor * 100));

        return number_format($adelanto / 100, 2, '.', '');
    }

    public function aApi(): array
    {
        $datos = [];
        foreach (self::CAMPOS as $campo) {
            $valor = $this->{$campo};
            $datos[$campo] = in_array($campo, ['adelanto_valor', 'cancelacion_porcentaje'], true)
                ? self::dineroApi($valor) : $valor;
        }
        $datos['actualizado_en'] = self::fechaApi($this->updated_at);

        return $datos;
    }
}
