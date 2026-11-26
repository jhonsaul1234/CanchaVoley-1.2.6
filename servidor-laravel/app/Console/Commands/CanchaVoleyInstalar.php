<?php

namespace App\Console\Commands;

use App\Models\Cancha;
use App\Models\Negocio;
use App\Models\User;
use App\Support\CuentasCanchaVoley;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;

/**
 * CanchaVoley: deja el sistema listo para usarse sin inventar datos del negocio.
 * Crea (si no existen) el negocio, su primera cancha y la cuenta de la administradora con los datos
 * que se indiquen. Horarios, tarifas y reglas se cargan después desde la app (Configuración).
 */
class CanchaVoleyInstalar extends Command
{
    protected $signature = 'canchavoley:instalar
        {--negocio= : Nombre del negocio}
        {--cancha= : Nombre de la primera cancha}
        {--admin-nombre= : Nombre de la administradora}
        {--admin-email= : Correo de la administradora}
        {--admin-password= : Contraseña (mínimo 8 caracteres)}';

    protected $description = 'Instala CanchaVoley: negocio, primera cancha y cuenta de la administradora';

    public function handle(): int
    {
        $this->info('Base de datos: ' . DB::connection()->getDatabaseName() . ' (' . DB::getDriverName() . ')');

        $negocio = Negocio::principal();
        if ($negocio) {
            $this->line("Negocio ya registrado: {$negocio->nombre}");
        } else {
            $nombre = $this->option('negocio') ?: $this->ask('Nombre del negocio');
            if (! $nombre) {
                $this->error('Falta el nombre del negocio.');
                return self::FAILURE;
            }
            $negocio = Negocio::create(['nombre' => $nombre]);
            $negocio->reglasOVacias();
            $this->info("Negocio creado: {$negocio->nombre}");
        }

        if ($negocio->canchas()->exists()) {
            $this->line('Canchas registradas: ' . $negocio->canchas()->pluck('nombre')->join(', '));
        } else {
            $cancha = $this->option('cancha') ?: $this->ask('Nombre de la primera cancha', 'Cancha de vóley');
            Cancha::create(['negocio_id' => $negocio->id, 'nombre' => $cancha, 'deporte' => 'voley', 'orden' => 1]);
            $this->info("Cancha creada: {$cancha}");
        }

        // Las cuentas heredadas de ProLech y las de demostración no cuentan como administradora de CanchaVoley.
        $adminsCanchaVoley = User::conRol('admin')->where('activo', true)->get()
            ->filter(fn (User $u) => CuentasCanchaVoley::motivoDeRechazo((string) $u->email) === null);
        if ($adminsCanchaVoley->isNotEmpty() && ! $this->option('admin-email')) {
            $this->line('Ya existe al menos una cuenta de administradora.');
        } else {
            $email = $this->option('admin-email') ?: $this->ask('Correo de la administradora');
            if (User::where('email', $email)->exists()) {
                $this->warn("Ya existe una cuenta con el correo {$email}: no se modifica.");
            } else {
                $nombre   = $this->option('admin-nombre') ?: $this->ask('Nombre de la administradora');
                $password = $this->option('admin-password') ?: $this->secret('Contraseña (mínimo 8 caracteres)');
                if (! $email || ! $nombre || strlen((string) $password) < 8) {
                    $this->error('Correo, nombre y una contraseña de al menos 8 caracteres son obligatorios.');
                    return self::FAILURE;
                }
                if (($motivo = CuentasCanchaVoley::motivoDeRechazo((string) $email)) !== null || CuentasCanchaVoley::esDeDemostracion((string) $email)) {
                    $this->error('Usa un correo propio del negocio: ' . ($motivo ?? 'el dominio demo.canchavoley es solo para la demostración.'));
                    return self::FAILURE;
                }
                if (CuentasCanchaVoley::esClaveDeDemostracion((string) $password)) {
                    $this->error('Esa es la clave pública de la demostración: elige otra contraseña.');
                    return self::FAILURE;
                }
                User::create([
                    'name' => $nombre, 'email' => $email, 'password' => Hash::make($password),
                    'rol' => 'admin', 'activo' => true,
                ]);
                $this->info("Cuenta de administradora creada: {$email}");
            }
        }

        $this->newLine();
        $this->line('Siguiente paso: inicia sesión en la app de escritorio y completa en Configuración el horario de atención, las tarifas y las reglas de reserva.');

        return self::SUCCESS;
    }
}
