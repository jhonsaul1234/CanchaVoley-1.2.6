<?php

namespace App\Exceptions\CanchaVoley;

use Illuminate\Contracts\Debug\ShouldntReport;
use Illuminate\Http\JsonResponse;
use RuntimeException;

/**
 * CanchaVoley: regla de negocio incumplida (horario, choque, permiso, dato faltante…).
 * Se responde como JSON con el código HTTP indicado y datos extra (choque, sugerencias).
 * No se escribe en el registro de errores: es una respuesta normal del negocio (p. ej. horario ocupado).
 */
class ReservaException extends RuntimeException implements ShouldntReport
{
    public function __construct(string $mensaje, public readonly int $codigo = 422, public readonly array $datos = [])
    {
        parent::__construct($mensaje, $codigo);
    }

    public function render(): JsonResponse
    {
        return response()->json(['message' => $this->getMessage(), ...$this->datos], $this->codigo);
    }
}
