<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

/** CanchaVoley: operación recibida de un equipo; guarda la respuesta para contestar igual a un reenvío. */
class OperacionSync extends Model
{
    protected $table = 'operaciones_sync';

    protected $fillable = [
        'op_uuid', 'negocio_id', 'user_id', 'dispositivo_uuid', 'tipo', 'entidad_uuid', 'datos',
        'resultado', 'respuesta', 'creada_en_dispositivo', 'recibida_en',
    ];

    protected function casts(): array
    {
        return [
            'datos'                 => 'array',
            'respuesta'             => 'array',
            'creada_en_dispositivo' => 'datetime',
            'recibida_en'           => 'datetime',
        ];
    }
}
