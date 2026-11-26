<?php

namespace App\Services\CanchaVoley;

use App\Models\Bloqueo;
use App\Models\Cancha;
use App\Models\ReglaReserva;
use App\Models\Reserva;
use Carbon\CarbonImmutable;

/**
 * CanchaVoley: horario de atención, tiempo ocupado y tiempo libre de una cancha.
 * Todos los intervalos son semiabiertos [inicio, fin): una reserva que termina a las 20:00
 * no choca con otra que empieza a las 20:00 (salvo que haya margen configurado).
 */
class Disponibilidad
{
    /** Paso de las sugerencias mientras el negocio no configure la alineación del inicio. */
    public const PASO_SUGERENCIAS_POR_DEFECTO = 30;

    /** @return list<array{0: CarbonImmutable, 1: CarbonImmutable}> turnos que empiezan en esa fecha */
    public function turnosDelDia(Cancha $cancha, CarbonImmutable $fecha): array
    {
        $base   = $fecha->startOfDay();
        $turnos = [];
        foreach ($cancha->horarios->where('dia_semana', $base->dayOfWeekIso) as $h) {
            $ini = $base->setTimeFromTimeString(substr($h->abre, 0, 5));
            $fin = $base->setTimeFromTimeString(substr($h->cierra, 0, 5));
            if ($fin->lessThanOrEqualTo($ini)) {
                $fin = $fin->addDay();
            }
            $turnos[] = [$ini, $fin];
        }

        return $this->fusionar($turnos);
    }

    public function tieneHorario(Cancha $cancha): bool
    {
        return $cancha->horarios->isNotEmpty();
    }

    /** ¿Todo [inicio, fin) cae dentro del horario (incluye turnos del día anterior que pasan la medianoche)? */
    public function dentroDeHorario(Cancha $cancha, CarbonImmutable $inicio, CarbonImmutable $fin): bool
    {
        $intervalos = [];
        for ($d = $inicio->subDay()->startOfDay(); $d->lessThanOrEqualTo($fin); $d = $d->addDay()) {
            array_push($intervalos, ...$this->turnosDelDia($cancha, $d));
        }
        foreach ($this->fusionar($intervalos) as [$a, $b]) {
            if ($a->lessThanOrEqualTo($inicio) && $fin->lessThanOrEqualTo($b)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Tiempo ocupado entre [desde, hasta): reservas que ocupan la cancha y bloqueos (de la cancha o del local).
     * @return list<array{inicio: CarbonImmutable, fin: CarbonImmutable, tipo: string, id: int}>
     */
    public function ocupados(Cancha $cancha, CarbonImmutable $desde, CarbonImmutable $hasta, ?int $excluirReservaId = null): array
    {
        $res = Reserva::where('cancha_id', $cancha->id)
            ->whereIn('estado', Reserva::ESTADOS_QUE_OCUPAN)
            ->where('inicio', '<', $hasta)
            ->where('fin', '>', $desde)
            ->when($excluirReservaId, fn ($q) => $q->where('id', '!=', $excluirReservaId))
            // Pendiente del adelanto con el plazo vencido: ya no aparta el horario (al reservarlo se libera).
            ->where(fn ($q) => $q->whereNull('pendiente_por')->orWhere('pendiente_por', '!=', 'adelanto')
                ->orWhereNull('adelanto_vence_en')->orWhere('adelanto_vence_en', '>=', CarbonImmutable::now()))
            ->get(['id', 'inicio', 'fin']);

        $blo = Bloqueo::where('negocio_id', $cancha->negocio_id)
            ->where(fn ($q) => $q->whereNull('cancha_id')->orWhere('cancha_id', $cancha->id))
            ->where('inicio', '<', $hasta)
            ->where('fin', '>', $desde)
            ->get(['id', 'inicio', 'fin']);

        $todo = [];
        foreach ($res as $r) {
            $todo[] = ['inicio' => $r->inicio->toImmutable(), 'fin' => $r->fin->toImmutable(), 'tipo' => 'reserva', 'id' => $r->id];
        }
        foreach ($blo as $b) {
            $todo[] = ['inicio' => $b->inicio->toImmutable(), 'fin' => $b->fin->toImmutable(), 'tipo' => 'bloqueo', 'id' => $b->id];
        }
        usort($todo, fn ($x, $y) => $x['inicio'] <=> $y['inicio']);

        return $todo;
    }

    /**
     * Ocupación (sin datos de clientes) de todas las canchas activas entre [desde, hasta): la app del cliente
     * la guarda para mostrar horarios sin conexión sin ofrecer como libres los que ya están tomados.
     * @return list<array{cancha_id: int, inicio: string, fin: string, tipo: string}>
     */
    public function ocupacionDelNegocio(\App\Models\Negocio $negocio, CarbonImmutable $desde, CarbonImmutable $hasta): array
    {
        $lista = [];
        foreach ($negocio->canchas()->where('activa', true)->get() as $cancha) {
            foreach ($this->ocupados($cancha, $desde, $hasta) as $o) {
                $lista[] = ['cancha_id' => $cancha->id, 'inicio' => $o['inicio']->toIso8601String(), 'fin' => $o['fin']->toIso8601String(), 'tipo' => $o['tipo']];
            }
        }

        return $lista;
    }

    /**
     * Intervalos donde cabe una reserva completa ese día (turnos que empiezan en la fecha, menos lo ocupado
     * ampliado por el margen entre reservas). No incluye tiempo ya pasado.
     * @return list<array{0: CarbonImmutable, 1: CarbonImmutable}>
     */
    public function libres(Cancha $cancha, CarbonImmutable $fecha, ReglaReserva $regla, ?int $excluirReservaId = null): array
    {
        $turnos = $this->turnosDelDia($cancha, $fecha);
        if (! $turnos) {
            return [];
        }
        $m        = $regla->margen();
        $ocupados = $this->ocupados($cancha, $turnos[0][0]->subMinutes($m), end($turnos)[1]->addMinutes($m), $excluirReservaId);
        $ahora    = CarbonImmutable::now()->startOfMinute();

        $libres = [];
        foreach ($turnos as [$a, $b]) {
            $cursor = $a->max($ahora);
            foreach ($ocupados as $o) {
                $s = $o['inicio']->subMinutes($m);
                $e = $o['fin']->addMinutes($m);
                if ($e->lessThanOrEqualTo($cursor) || $s->greaterThanOrEqualTo($b)) {
                    continue;
                }
                if ($s->greaterThan($cursor)) {
                    $libres[] = [$cursor, $s->min($b)];
                }
                $cursor = $cursor->max($e);
                if ($cursor->greaterThanOrEqualTo($b)) {
                    break;
                }
            }
            if ($cursor->lessThan($b)) {
                $libres[] = [$cursor, $b];
            }
        }

        return $libres;
    }

    /**
     * Recomendación de horarios: inicios libres para una duración, ordenados por cercanía a la hora preferida
     * (o los más próximos si no hay preferencia), buscando desde la fecha indicada hasta `dias` días después.
     * @return list<array{inicio: string, fin: string}>
     */
    public function sugerencias(
        Cancha $cancha,
        ReglaReserva $regla,
        CarbonImmutable $fecha,
        int $duracionMin,
        ?CarbonImmutable $preferida = null,
        int $dias = 7,
        int $limite = 6,
        ?int $excluirReservaId = null,
    ): array {
        if ($duracionMin <= 0 || ! $this->tieneHorario($cancha)) {
            return [];
        }
        $paso     = max(5, $regla->alinear_inicio_min ?? $regla->paso_min ?? self::PASO_SUGERENCIAS_POR_DEFECTO);
        $minimo   = $regla->anticipacion_minima_min ? CarbonImmutable::now()->addMinutes($regla->anticipacion_minima_min) : null;
        $candidatos = [];

        for ($i = 0; $i < $dias; $i++) {
            $dia = $fecha->startOfDay()->addDays($i);
            foreach ($this->libres($cancha, $dia, $regla, $excluirReservaId) as [$a, $b]) {
                $minDelDia = $a->hour * 60 + $a->minute;
                $resto     = $minDelDia % $paso;
                $inicio    = $resto === 0 && $a->second === 0 ? $a : $a->startOfMinute()->addMinutes($paso - $resto);
                for (; $inicio->addMinutes($duracionMin)->lessThanOrEqualTo($b); $inicio = $inicio->addMinutes($paso)) {
                    if ($minimo && $inicio->lessThan($minimo)) {
                        continue;
                    }
                    $distancia = $preferida
                        ? abs($inicio->diffInMinutes($preferida, false))
                        : $inicio->diffInMinutes($fecha->startOfDay(), true);
                    $candidatos[] = [$distancia, $inicio];
                }
            }
            if ($preferida === null && count($candidatos) >= $limite) {
                break;
            }
        }
        usort($candidatos, fn ($x, $y) => [$x[0], $x[1]] <=> [$y[0], $y[1]]);

        return array_map(fn ($c) => [
            'inicio' => $c[1]->toIso8601String(),
            'fin'    => $c[1]->addMinutes($duracionMin)->toIso8601String(),
        ], array_slice($candidatos, 0, $limite));
    }

    /** @param list<array{0: CarbonImmutable, 1: CarbonImmutable}> $intervalos */
    private function fusionar(array $intervalos): array
    {
        usort($intervalos, fn ($x, $y) => $x[0] <=> $y[0]);
        $res = [];
        foreach ($intervalos as [$a, $b]) {
            $n = count($res);
            if ($n && $a->lessThanOrEqualTo($res[$n - 1][1])) {
                $res[$n - 1][1] = $res[$n - 1][1]->max($b);
            } else {
                $res[] = [$a, $b];
            }
        }

        return $res;
    }
}
