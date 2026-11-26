<?php

namespace App\Models\Concerns;

use Carbon\CarbonInterface;

/** CanchaVoley: formato común de fechas y dinero en la API (ISO 8601 con desfase y dinero como texto). */
trait FormatoApi
{
    protected static function fechaApi(?CarbonInterface $fecha): ?string
    {
        return $fecha?->copy()->setTimezone(config('app.timezone'))->toIso8601String();
    }

    protected static function dineroApi(mixed $valor): ?string
    {
        return $valor === null ? null : number_format((float) $valor, 2, '.', '');
    }
}
