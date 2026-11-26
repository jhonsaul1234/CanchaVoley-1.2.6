<?php

namespace App\Providers;

use Illuminate\Cache\RateLimiting\Limit;
use Illuminate\Console\Events\CommandStarting;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Event;
use Illuminate\Support\Facades\RateLimiter;
use Illuminate\Support\ServiceProvider;

class AppServiceProvider extends ServiceProvider
{
    /** CanchaVoley: bases del proyecto ProLech original que nunca deben modificarse. */
    public const BASES_PROTEGIDAS = ['prolech', 'prolech_db', 'prolech_pass'];

    /** Comandos que escriben en la base de datos y se bloquean si la conexión apunta a ProLech. */
    public const COMANDOS_QUE_ESCRIBEN = ['migrate', 'migrate:fresh', 'migrate:refresh', 'migrate:reset', 'migrate:rollback', 'db:seed', 'db:wipe', 'canchavoley:instalar'];

    public static function verificarBaseProtegida(string $comando): void
    {
        if (! in_array($comando, self::COMANDOS_QUE_ESCRIBEN, true)) {
            return;
        }
        $conexion = config('database.default');
        $base     = (string) config("database.connections.{$conexion}.database");
        if (in_array(strtolower($base), self::BASES_PROTEGIDAS, true)) {
            throw new \RuntimeException("CanchaVoley: «{$base}» es una base de ProLech y está protegida. Usa DB_DATABASE=cancha_voley en el .env.");
        }
    }

    /**
     * Register any application services.
     */
    public function register(): void
    {
        //
    }

    /**
     * Bootstrap any application services.
     */
    public function boot(): void
    {
        // CanchaVoley: las bases de ProLech no se tocan nunca. Si el .env apuntara a una de ellas,
        // las migraciones, seeders y el instalador se detienen antes de hacer cualquier cambio.
        Event::listen(CommandStarting::class, fn (CommandStarting $evento) => self::verificarBaseProtegida((string) $evento->command));

        // Login: máximo 5 intentos por minuto por correo + IP (frena ataques de fuerza bruta)
        // y 30 por IP para que no se puedan probar muchos correos distintos.
        $porCorreo = fn (Request $r) => strtolower((string) $r->input('email')) . '|' . $r->ip();

        // CanchaVoley: registro de clientes desde la app (evita altas masivas).
        RateLimiter::for('registro-cv', fn (Request $r) => Limit::perMinute(5)->by('ip:' . $r->ip())->response(fn () => response()->json(
            ['message' => 'Demasiados intentos de registro. Espera un minuto.'], 429
        )));

        RateLimiter::for('login-api', fn (Request $r) => [
            Limit::perMinute(5)->by($porCorreo($r))->response(fn () => response()->json(
                ['message' => 'Demasiados intentos. Espera un minuto e inténtalo de nuevo.'], 429
            )),
            Limit::perMinute(30)->by('ip:' . $r->ip()),
        ]);

        RateLimiter::for('login-web', fn (Request $r) => [
            Limit::perMinute(5)->by($porCorreo($r))->response(fn () => back()
                ->withInput($r->only('email'))
                ->withErrors(['email' => 'Demasiados intentos. Espera un minuto e inténtalo de nuevo.'])),
            Limit::perMinute(30)->by('ip:' . $r->ip()),
        ]);
    }
}
