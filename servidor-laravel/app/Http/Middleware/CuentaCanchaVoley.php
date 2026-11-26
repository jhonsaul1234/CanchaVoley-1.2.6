<?php

namespace App\Http\Middleware;

use App\Support\CuentasCanchaVoley;
use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

/**
 * CanchaVoley: las cuentas de demostración solo trabajan con la base de demostración y las cuentas
 * heredadas de ProLech no usan la API de CanchaVoley, aunque tengan un token.
 */
class CuentaCanchaVoley
{
    public function handle(Request $request, Closure $next): Response
    {
        $user = $request->user();
        if ($user && ($motivo = CuentasCanchaVoley::motivoDeRechazo((string) $user->email)) !== null) {
            return response()->json(['message' => $motivo], 403);
        }

        return $next($request);
    }
}
