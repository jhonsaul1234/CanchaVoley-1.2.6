<?php

namespace App\Support;

use Closure;
use Illuminate\Support\Facades\DB;

/**
 * CanchaVoley: qué cuentas pueden usar el sistema en cada base (seguridad de la demostración).
 *
 * - Las cuentas de la demostración (@demo.canchavoley) tienen una clave pública, escrita en la guía de
 *   instalación: solo funcionan con una base de demostración (nombre terminado en _demo), nunca con la
 *   base real del negocio, aunque alguien las creara ahí.
 * - Las cuentas de ejemplo heredadas de ProLech (@prolech.com) no entran a CanchaVoley.
 * - La clave de la demostración no se puede usar al crear una cuenta ni al cambiar una clave.
 */
final class CuentasCanchaVoley
{
    public const DOMINIO_DEMO = 'demo.canchavoley';

    /** Dominios de las cuentas de ejemplo de ProLech que quedaron en la base. */
    public const DOMINIOS_HEREDADOS = ['prolech.com'];

    /** Clave de las cuentas de demostración (pública: está en docs/INSTALACION.md). */
    public const CLAVE_DEMO = 'Demo2026';

    public static function dominio(string $email): string
    {
        $pos = strrpos($email, '@');

        return $pos === false ? '' : mb_strtolower(trim(substr($email, $pos + 1)));
    }

    public static function esDeDemostracion(string $email): bool
    {
        return self::dominio($email) === self::DOMINIO_DEMO;
    }

    public static function esHeredadaDeProlech(string $email): bool
    {
        return in_array(self::dominio($email), self::DOMINIOS_HEREDADOS, true);
    }

    /** ¿La base conectada es de demostración? Por el nombre (…_demo); en las pruebas se puede fijar por configuración. */
    public static function baseDeDemostracion(): bool
    {
        $fijado = config('canchavoley.base_de_demostracion');
        if ($fijado !== null) {
            return (bool) $fijado;
        }

        return str_ends_with((string) DB::connection()->getDatabaseName(), '_demo');
    }

    /** Por qué la cuenta no puede usar CanchaVoley con esta base, o null si puede. */
    public static function motivoDeRechazo(string $email): ?string
    {
        if (self::esHeredadaDeProlech($email)) {
            return 'Esta cuenta es de ProLech y no se usa en CanchaVoley. Pide a la administradora una cuenta de CanchaVoley.';
        }
        if (self::esDeDemostracion($email) && ! self::baseDeDemostracion()) {
            return 'Las cuentas de demostración solo funcionan con el servidor de demostración (datos ficticios), no con la base real del negocio.';
        }

        return null;
    }

    public static function esClaveDeDemostracion(string $clave): bool
    {
        return mb_strtolower(trim($clave)) === mb_strtolower(self::CLAVE_DEMO);
    }

    /** Regla de validación para el correo de una cuenta nueva. */
    public static function reglaCorreo(): Closure
    {
        return function (string $atributo, mixed $valor, Closure $falla): void {
            $email = (string) $valor;
            if (self::esHeredadaDeProlech($email)) {
                $falla('Ese dominio de correo es de ProLech: usa otro correo.');
            } elseif (self::esDeDemostracion($email) && ! self::baseDeDemostracion()) {
                $falla('El dominio demo.canchavoley está reservado para la demostración: usa otro correo.');
            }
        };
    }

    /** Regla de validación: la clave pública de la demostración no se puede usar. */
    public static function reglaClave(): Closure
    {
        return function (string $atributo, mixed $valor, Closure $falla): void {
            if (self::esClaveDeDemostracion((string) $valor)) {
                $falla('Esa es la clave pública de la demostración: elige otra.');
            }
        };
    }
}
