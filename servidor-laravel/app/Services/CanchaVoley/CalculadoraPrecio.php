<?php

namespace App\Services\CanchaVoley;

use App\Models\Cancha;
use App\Models\ReglaReserva;
use App\Models\Tarifa;
use Carbon\CarbonImmutable;

/**
 * CanchaVoley: precio de una reserva según las tarifas por franja de la cancha.
 * No inventa precios: si un tramo no tiene tarifa, o la reserva cruza franjas de distinto precio y la
 * regla de cobro no está configurada, devuelve precio NULL con el motivo.
 */
class CalculadoraPrecio
{
    /** @return array{precio: ?string, motivo: ?string} */
    public function calcular(Cancha $cancha, CarbonImmutable $inicio, CarbonImmutable $fin, ReglaReserva $regla): array
    {
        $minutos = (int) $inicio->diffInMinutes($fin);
        if ($minutos <= 0) {
            return ['precio' => null, 'motivo' => 'Duración inválida.'];
        }

        $tarifas = Tarifa::where('cancha_id', $cancha->id)->get();
        $porMinuto = [];          // céntimos por hora de cada minuto
        for ($t = $inicio; $t->lessThan($fin); $t = $t->addMinute()) {
            $tarifa = $this->tarifaEn($tarifas, $t);
            $porMinuto[] = $tarifa === null ? null : (int) round(((float) $tarifa->precio_hora) * 100);
        }

        $distintos = array_values(array_unique(array_filter($porMinuto, fn ($v) => $v !== null)));
        $regla_cruce = $regla->regla_cruce_franjas;

        if ($regla_cruce === 'franja_inicio') {
            if ($porMinuto[0] === null) {
                return ['precio' => null, 'motivo' => 'No hay tarifa configurada para la hora de inicio.'];
            }
            return ['precio' => $this->formatear($porMinuto[0] * $minutos / 60), 'motivo' => null];
        }

        if (in_array(null, $porMinuto, true)) {
            return ['precio' => null, 'motivo' => 'No hay tarifa configurada para todo el horario de la reserva.'];
        }
        if (count($distintos) > 1 && $regla_cruce !== 'prorrateo') {
            return ['precio' => null, 'motivo' => 'La reserva cruza franjas con distinto precio y la regla de cobro no está configurada.'];
        }

        return ['precio' => $this->formatear(array_sum($porMinuto) / 60), 'motivo' => null];
    }

    /** Tarifa vigente que cubre ese minuto (la de vigencia más reciente si hubiera varias). */
    private function tarifaEn($tarifas, CarbonImmutable $momento): ?Tarifa
    {
        $min   = $momento->hour * 60 + $momento->minute;
        $fecha = $momento->toDateString();

        return $tarifas
            ->filter(fn (Tarifa $t) => $t->dia_semana === $momento->dayOfWeekIso
                && $t->vigente_desde->toDateString() <= $fecha
                && ($t->vigente_hasta === null || $t->vigente_hasta->toDateString() >= $fecha)
                && $t->minutosDesde() <= $min && $min < $t->minutosHasta())
            ->sortByDesc(fn (Tarifa $t) => $t->vigente_desde->toDateString())
            ->first();
    }

    private function formatear(float $centimos): string
    {
        return number_format(round($centimos) / 100, 2, '.', '');
    }
}
