<?php

namespace App\Http\Controllers\Api\CanchaVoley;

use App\Exceptions\CanchaVoley\ReservaException;
use App\Http\Controllers\Controller;
use App\Models\User;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Hash;
use Illuminate\Validation\Rule;
use Illuminate\Validation\Rules\Password;
use App\Support\CuentasCanchaVoley;

/** CanchaVoley: cuentas del personal (administradora y recepción). Solo la administradora. */
class UsuarioController extends Controller
{
    /** GET /api/cv/usuarios */
    public function index(): JsonResponse
    {
        // No se listan las cuentas que no usan CanchaVoley con esta base (heredadas de ProLech o de demostración en la base real).
        return response()->json(User::conRol('admin', 'recepcion')->orderBy('name')->get()
            ->filter(fn (User $u) => CuentasCanchaVoley::motivoDeRechazo((string) $u->email) === null)
            ->map(fn (User $u) => $this->aApi($u))->values());
    }

    /** POST /api/cv/usuarios */
    public function store(Request $request): JsonResponse
    {
        $datos = $request->validate([
            'nombre'   => ['required', 'string', 'max:120'],
            'email'    => ['required', 'email', 'max:190', 'unique:users,email', CuentasCanchaVoley::reglaCorreo()],
            'password' => ['required', 'string', Password::min(8), CuentasCanchaVoley::reglaClave()],
            'rol'      => ['required', Rule::in(['admin', 'recepcion'])],
            'telefono' => ['nullable', 'string', 'max:20'],
        ]);
        $user = User::create([
            'name' => $datos['nombre'], 'email' => $datos['email'], 'password' => Hash::make($datos['password']),
            'rol' => $datos['rol'], 'activo' => true, 'telefono' => $datos['telefono'] ?? null,
        ]);

        return response()->json($this->aApi($user), 201);
    }

    /** PUT /api/cv/usuarios/{user} */
    public function update(Request $request, User $user): JsonResponse
    {
        if (! $user->tieneRol('admin', 'recepcion')) {
            throw new ReservaException('Solo se administran cuentas del personal.', 404);
        }
        $datos = $request->validate([
            'nombre'   => ['sometimes', 'string', 'max:120'],
            'rol'      => ['sometimes', Rule::in(['admin', 'recepcion'])],
            'activo'   => ['sometimes', 'boolean'],
            'password' => ['sometimes', 'string', Password::min(8), CuentasCanchaVoley::reglaClave()],
        ]);
        if ($user->id === $request->user()->id && (($datos['activo'] ?? true) === false || ($datos['rol'] ?? 'admin') !== 'admin')) {
            throw new ReservaException('No puedes desactivar ni quitar el rol de administradora a tu propia cuenta.', 422);
        }
        if (isset($datos['nombre'])) {
            $user->name = $datos['nombre'];
        }
        if (isset($datos['rol'])) {
            $user->rol = $datos['rol'];
        }
        if (array_key_exists('activo', $datos)) {
            $user->activo = $datos['activo'];
            if (! $datos['activo']) {
                $user->tokens()->delete();           // se cierran sus sesiones
            }
        }
        if (isset($datos['password'])) {
            $user->password = Hash::make($datos['password']);
            $user->tokens()->delete();
        }
        $user->save();

        return response()->json($this->aApi($user));
    }

    private function aApi(User $u): array
    {
        return ['id' => $u->id, 'nombre' => $u->name, 'email' => $u->email, 'rol' => $u->rol, 'activo' => (bool) $u->activo];
    }
}
