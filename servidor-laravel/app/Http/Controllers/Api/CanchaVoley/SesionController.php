<?php

namespace App\Http\Controllers\Api\CanchaVoley;

use App\Exceptions\CanchaVoley\ReservaException;
use App\Http\Controllers\Controller;
use App\Models\Cliente;
use App\Models\Negocio;
use App\Models\User;
use App\Services\CanchaVoley\Avisos;
use App\Services\CanchaVoley\ServicioClientes;
use Carbon\CarbonImmutable;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Str;
use Illuminate\Validation\Rules\Password;
use App\Support\CuentasCanchaVoley;

/** CanchaVoley: registro de clientes desde la app y datos de la sesión actual. */
class SesionController extends Controller
{
    /**
     * POST /api/cv/registro — un cliente crea su cuenta desde Android.
     * Si recepción ya lo había registrado por teléfono, la cuenta se une a ese cliente (conserva su
     * historial) y se avisa a la administradora, que puede revisarlo.
     */
    public function registro(Request $request, ServicioClientes $clientes, Avisos $avisos): JsonResponse
    {
        $datos = $request->validate([
            'nombre'      => ['required', 'string', 'max:120'],
            'email'       => ['required', 'email', 'max:190', 'unique:users,email', CuentasCanchaVoley::reglaCorreo()],
            'password'    => ['required', 'string', Password::min(8), CuentasCanchaVoley::reglaClave()],
            'telefono'    => ['required', 'string', 'max:20'],
            'dispositivo' => ['nullable', 'string', 'max:40'],
        ]);
        $negocio  = Negocio::principalOFallar();
        $telefono = $clientes->telefonoValido($datos['telefono']);

        [$user, $cliente, $unido] = DB::transaction(function () use ($datos, $negocio, $telefono) {
            $existente = Cliente::where('negocio_id', $negocio->id)->where('telefono', $telefono)->lockForUpdate()->first();
            if ($existente && $existente->user_id !== null) {
                throw new ReservaException('Ese teléfono ya tiene una cuenta. Inicia sesión o comunícate con el negocio.', 409);
            }
            $user = User::create([
                'name'     => $datos['nombre'],
                'email'    => $datos['email'],
                'password' => Hash::make($datos['password']),
                'rol'      => 'cliente',
                'activo'   => true,
                'telefono' => $telefono,
            ]);
            if ($existente) {
                $existente->forceFill(['user_id' => $user->id])->save();
                return [$user, $existente, true];
            }
            $cliente = Cliente::create([
                'uuid' => (string) Str::uuid(), 'negocio_id' => $negocio->id, 'user_id' => $user->id,
                'nombre' => $datos['nombre'], 'telefono' => $telefono,
            ]);
            return [$user, $cliente, false];
        });

        if ($unido) {
            $avisos->aAdministradoras('Cuenta de cliente vinculada',
                "La cuenta {$user->email} se vinculó al cliente {$cliente->nombre} ({$cliente->telefono}) registrado antes por teléfono. Revísalo si no lo reconoces.",
                'cliente_vinculado', $cliente->id);
        }
        $token = $user->createToken($this->nombreToken($datos['dispositivo'] ?? null), ['*'])->plainTextToken;

        return response()->json(['token' => $token, ...$this->datosSesion($user->fresh())], 201);
    }

    /** GET /api/cv/sesion */
    public function sesion(Request $request): JsonResponse
    {
        return response()->json($this->datosSesion($request->user()));
    }

    public static function nombreToken(?string $dispositivo): string
    {
        $limpio = Str::slug((string) $dispositivo);
        return $limpio !== '' ? 'cv-' . mb_substr($limpio, 0, 40) : 'mobile';
    }

    private function datosSesion(User $user): array
    {
        $rol = $user->tieneRol('admin') ? 'admin' : ($user->tieneRol('recepcion') ? 'recepcion' : ($user->tieneRol('cliente') ? 'cliente' : $user->rol));

        return [
            'usuario' => [
                'id' => $user->id, 'nombre' => $user->name, 'email' => $user->email,
                'rol' => $rol, 'roles' => $user->roles(),
            ],
            'es_personal'    => $user->esPersonal(),
            'es_admin'       => $user->tieneRol('admin'),
            'cliente'        => $user->cliente?->aApi(),
            'negocio'        => Negocio::principal()?->aApi(),
            'servidor_ahora' => CarbonImmutable::now()->toIso8601String(),
        ];
    }
}
