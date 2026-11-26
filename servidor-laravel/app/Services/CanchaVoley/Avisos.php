<?php

namespace App\Services\CanchaVoley;

use App\Models\Notificacion;
use App\Models\Reserva;
use App\Models\User;

/** CanchaVoley: notificaciones dentro del sistema (tabla `notificaciones` de ProLech, reutilizada). */
class Avisos
{
    public function aUsuario(?int $userId, string $titulo, string $mensaje, string $tipo, ?int $referencia = null): void
    {
        if ($userId === null) {
            return;
        }
        Notificacion::create([
            'user_id'       => $userId,
            'titulo'        => mb_substr($titulo, 0, 200),
            'mensaje'       => $mensaje,
            'tipo'          => $tipo,
            'referencia_id' => $referencia,
            'leida'         => false,
        ]);
    }

    public function aAdministradoras(string $titulo, string $mensaje, string $tipo, ?int $referencia = null, ?int $excepto = null): void
    {
        User::conRol('admin')->where('activo', true)
            ->when($excepto, fn ($q) => $q->where('id', '!=', $excepto))
            ->pluck('id')
            ->each(fn ($id) => $this->aUsuario($id, $titulo, $mensaje, $tipo, $referencia));
    }

    /** Al personal (administradoras y recepción): p. ej. un pago que el cliente informó y hay que verificar. */
    public function aPersonal(string $titulo, string $mensaje, string $tipo, ?int $referencia = null): void
    {
        User::conRol('admin', 'recepcion')->where('activo', true)
            ->pluck('id')->unique()
            ->each(fn ($id) => $this->aUsuario($id, $titulo, $mensaje, $tipo, $referencia));
    }

    public function aClienteDe(Reserva $reserva, string $titulo, string $mensaje, string $tipo): void
    {
        $this->aUsuario($reserva->cliente?->user_id, $titulo, $mensaje, $tipo, $reserva->id);
    }

    public static function texto(Reserva $r): string
    {
        return $r->inicio->locale('es')->isoFormat('dddd D [de] MMMM, HH:mm') . '–' . $r->fin->format('H:i');
    }
}
