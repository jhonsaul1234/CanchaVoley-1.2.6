<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\User;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Hash;
use Illuminate\Validation\ValidationException;

class AuthController extends Controller
{
    /**
     * POST /api/auth/login
     * Usada por la App Android (choferes y admin).
     */
    public function login(Request $request): JsonResponse
    {
        $request->validate([
            'email'    => ['required', 'email'],
            'password' => ['required', 'string'],
        ]);

        $user = User::where('email', $request->email)
                    ->where('activo', true)
                    ->first();

        if (! $user || ! Hash::check($request->password, $user->password)) {
            throw ValidationException::withMessages([
                'email' => ['Las credenciales no son correctas.'],
            ]);
        }

        // CanchaVoley: las cuentas de demostración (clave pública) solo entran con la base de demostración y
        // las cuentas de ejemplo heredadas de ProLech no entran desde las apps de CanchaVoley (envían `dispositivo`).
        $motivo = \App\Support\CuentasCanchaVoley::motivoDeRechazo($user->email);
        if ($motivo !== null && (\App\Support\CuentasCanchaVoley::esDeDemostracion($user->email) || $request->filled('dispositivo'))) {
            return response()->json(['message' => $motivo], 403);
        }

        // Pueden usar la API: choferes y admin (ProLech) y, en CanchaVoley, recepción y clientes
        // (en modo desarrollo debug se permiten todos para pruebas).
        if (! $user->tieneRol('chofer', 'admin', 'recepcion', 'cliente') && ! config('app.debug')) {
            return response()->json(['message' => 'No autorizado para acceso API.'], 403);
        }

        // Revocar tokens anteriores del mismo dispositivo. CanchaVoley envía `dispositivo`
        // (escritorio, android…) para que iniciar sesión en un equipo no cierre la del otro.
        $nombreToken = \App\Http\Controllers\Api\CanchaVoley\SesionController::nombreToken($request->input('dispositivo'));
        $user->tokens()->where('name', $nombreToken)->delete();

        $token = $user->createToken($nombreToken, ['*'])->plainTextToken;

        return response()->json([
            'token' => $token,
            'user'  => [
                'id'    => $user->id,
                'name'  => $user->name,
                'email' => $user->email,
                // La app de campo es de choferes: si el chofer es un acceso adicional, se informa como chofer
                'rol'   => $user->rol !== 'admin' && $user->tieneRol('chofer') ? 'chofer' : $user->rol,
                'roles' => $user->roles(),
            ],
        ]);
    }

    /**
     * POST /api/auth/logout
     */
    public function logout(Request $request): JsonResponse
    {
        $request->user()->currentAccessToken()->delete();

        return response()->json(['message' => 'Sesión cerrada correctamente.']);
    }

    /**
     * GET /api/auth/me
     */
    public function me(Request $request): JsonResponse
    {
        return response()->json(
            $request->user()->only('id', 'name', 'email', 'rol')
        );
    }
}
