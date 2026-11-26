<?php

namespace App\Services\CanchaVoley;

use App\Models\Cancha;
use App\Models\Negocio;
use App\Models\Pago;
use App\Models\Reserva;
use Carbon\CarbonImmutable;

/** CanchaVoley: estadísticas de ocupación, ingresos y caja del día (sin tablas extra). */
class Reportes
{
    public function __construct(private readonly Disponibilidad $disponibilidad) {}

    public function ocupacion(CarbonImmutable $desde, CarbonImmutable $hasta, ?int $canchaId = null): array
    {
        $negocio = Negocio::principalOFallar();
        $canchas = $negocio->canchas()->with('horarios')->when($canchaId, fn ($q) => $q->whereKey($canchaId))->get();
        $desde   = $desde->startOfDay();
        $hasta   = $hasta->endOfDay();

        $reservas = Reserva::where('negocio_id', $negocio->id)
            ->whereIn('cancha_id', $canchas->pluck('id'))
            ->where('inicio', '>=', $desde)->where('inicio', '<=', $hasta)
            ->with('cliente')->get();
        $confirmadas = $reservas->where('estado', 'confirmada');

        $porDia = [];
        $minAbiertosTotal = 0;
        $minReservadosTotal = 0;
        for ($d = $desde; $d->lessThanOrEqualTo($hasta); $d = $d->addDay()) {
            $abiertos = 0;
            foreach ($canchas as $c) {
                foreach ($this->disponibilidad->turnosDelDia($c, $d) as [$a, $b]) {
                    $abiertos += (int) $a->diffInMinutes($b);
                }
            }
            $reservados = (int) $confirmadas->filter(fn ($r) => $r->inicio->toDateString() === $d->toDateString())->sum('duracion_min');
            $minAbiertosTotal   += $abiertos;
            $minReservadosTotal += $reservados;
            $porDia[] = [
                'fecha'            => $d->toDateString(),
                'horas_abiertas'   => round($abiertos / 60, 2),
                'horas_reservadas' => round($reservados / 60, 2),
                'ocupacion_pct'    => $abiertos > 0 ? round($reservados * 100 / $abiertos, 1) : null,
            ];
        }

        $porHora = array_fill(0, 24, 0);
        $porDiaSemana = array_fill(1, 7, 0);
        foreach ($confirmadas as $r) {
            for ($t = $r->inicio->toImmutable(); $t->lessThan($r->fin); $t = $t->addMinutes(15)) {
                $porHora[$t->hour] += 15;
            }
            $porDiaSemana[$r->inicio->dayOfWeekIso] += $r->duracion_min;
        }
        arsort($porHora);
        $horasPico = array_slice(array_keys(array_filter($porHora)), 0, 3);
        ksort($porHora);

        $pagos = Pago::where('negocio_id', $negocio->id)->where('estado', 'valido')
            ->whereBetween('pagado_en', [$desde, $hasta])->get();
        $ingresos = ['efectivo' => 0, 'yape' => 0, 'plin' => 0];
        foreach ($pagos as $p) {
            $c = (int) round(((float) $p->monto) * 100);
            $ingresos[$p->metodo] += $p->tipo === 'devolucion' ? -$c : $c;
        }

        $topClientes = $confirmadas->groupBy('cliente_id')->map(fn ($g) => [
            'cliente'  => $g->first()->cliente?->nombre,
            'reservas' => $g->count(),
            'horas'    => round($g->sum('duracion_min') / 60, 2),
        ])->sortByDesc('reservas')->take(5)->values();

        return [
            'desde'                => $desde->toDateString(),
            'hasta'                => $hasta->toDateString(),
            'horas_abiertas'       => round($minAbiertosTotal / 60, 2),
            'horas_reservadas'     => round($minReservadosTotal / 60, 2),
            'ocupacion_pct'        => $minAbiertosTotal > 0 ? round($minReservadosTotal * 100 / $minAbiertosTotal, 1) : null,
            'por_dia'              => $porDia,
            'minutos_por_hora'     => $porHora,
            'minutos_por_dia_semana' => $porDiaSemana,
            'horas_pico'           => $horasPico,
            'reservas_por_estado'  => $reservas->countBy('estado'),
            'reservas_por_origen'  => $reservas->countBy('origen'),
            'demanda_no_atendida'  => $reservas->whereIn('estado', ['rechazada', 'cancelada'])->count(),
            'ingresos'             => array_map(fn ($c) => number_format($c / 100, 2, '.', ''), $ingresos),
            'ingresos_total'       => number_format(array_sum($ingresos) / 100, 2, '.', ''),
            'clientes_frecuentes'  => $topClientes,
            'sin_horario'          => $canchas->filter(fn (Cancha $c) => ! $this->disponibilidad->tieneHorario($c))->pluck('nombre')->values(),
        ];
    }

    public function caja(CarbonImmutable $fecha): array
    {
        $negocio = Negocio::principalOFallar();
        $pagos = Pago::where('negocio_id', $negocio->id)
            ->whereBetween('pagado_en', [$fecha->startOfDay(), $fecha->endOfDay()])
            ->with(['reserva.cliente', 'registrador'])->orderBy('pagado_en')->get();

        $totales = ['efectivo' => 0, 'yape' => 0, 'plin' => 0];
        $porUsuario = [];
        foreach ($pagos->where('estado', 'valido') as $p) {
            $c = (int) round(((float) $p->monto) * 100) * ($p->tipo === 'devolucion' ? -1 : 1);
            $totales[$p->metodo] += $c;
            $nombre = $p->registrador?->name ?? '—';
            $porUsuario[$nombre] = ($porUsuario[$nombre] ?? 0) + $c;
        }

        return [
            'fecha'       => $fecha->toDateString(),
            'totales'     => array_map(fn ($c) => number_format($c / 100, 2, '.', ''), $totales),
            'total'       => number_format(array_sum($totales) / 100, 2, '.', ''),
            'por_usuario' => array_map(fn ($c) => number_format($c / 100, 2, '.', ''), $porUsuario),
            'pagos'       => $pagos->map(fn (Pago $p) => [...$p->aApi(), 'cliente' => $p->reserva?->cliente?->nombre])->values(),
        ];
    }
}
