<?php

namespace App\Console\Commands;

use App\Models\User;
use App\Support\CuentasCanchaVoley;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\DB;

/**
 * CanchaVoley: muestra las cuentas de ejemplo heredadas de ProLech que quedaron en la base y, solo si se pide
 * con --desactivar y se confirma, las desactiva (no borra nada: se pueden volver a activar).
 * Aunque sigan activas, CanchaVoley ya no las deja entrar.
 */
class CanchaVoleyCuentasHeredadas extends Command
{
    protected $signature = 'canchavoley:cuentas-heredadas {--desactivar : Desactiva las cuentas heredadas después de confirmar}';

    protected $description = 'Revisa (y opcionalmente desactiva) las cuentas de ejemplo heredadas de ProLech';

    public function handle(): int
    {
        $this->info('Base de datos: ' . DB::connection()->getDatabaseName());
        $heredadas = User::query()->get()->filter(fn (User $u) => CuentasCanchaVoley::esHeredadaDeProlech((string) $u->email));
        $activas = $heredadas->where('activo', true);
        $this->line("Cuentas heredadas de ProLech: {$heredadas->count()} (activas: {$activas->count()}).");
        foreach ($heredadas->groupBy('rol') as $rol => $cuentas) {
            $this->line("  - {$rol}: {$cuentas->count()}");
        }
        if (! $this->option('desactivar') || $activas->isEmpty()) {
            return self::SUCCESS;
        }
        if (! $this->confirm("¿Desactivar {$activas->count()} cuentas heredadas de ProLech? No se borran y se pueden volver a activar.")) {
            $this->warn('No se cambió nada.');
            return self::SUCCESS;
        }
        DB::transaction(function () use ($activas) {
            foreach ($activas as $u) {
                $u->tokens()->delete();
                $u->forceFill(['activo' => false])->save();
            }
        });
        $this->info("Listo: {$activas->count()} cuentas desactivadas.");

        return self::SUCCESS;
    }
}
