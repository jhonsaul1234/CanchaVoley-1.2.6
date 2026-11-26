<?php

namespace App\Http\Controllers\Api\CanchaVoley;

use App\Http\Controllers\Controller;
use App\Models\Cliente;
use App\Models\Negocio;
use App\Models\Reserva;
use App\Services\CanchaVoley\ServicioClientes;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

/** CanchaVoley: clientes (solo personal). Incluye la búsqueda por teléfono del modo llamada. */
class ClienteController extends Controller
{
    /** GET /api/cv/clientes?q= */
    public function index(Request $request): JsonResponse
    {
        $negocio = Negocio::principalOFallar();
        $q = trim((string) $request->query('q', ''));
        $digitos = preg_replace('/\D+/', '', $q);
        $clientes = Cliente::where('negocio_id', $negocio->id)
            ->when($q !== '', fn ($w) => $w->where(fn ($x) => $x->where('nombre', 'like', "%{$q}%")
                ->when(strlen($digitos) >= 3, fn ($y) => $y->orWhere('telefono', 'like', "%{$digitos}%"))))
            ->orderBy('nombre')->limit(100)->get();

        return response()->json($clientes->map->aApi()->values());
    }

    /** GET /api/cv/clientes/telefono/{telefono} — modo llamada: quién llama y su historial. */
    public function porTelefono(string $telefono, ServicioClientes $servicio): JsonResponse
    {
        $cliente = $servicio->porTelefono($telefono);
        if (! $cliente) {
            return response()->json(['encontrado' => false, 'telefono' => Cliente::normalizarTelefono($telefono)]);
        }

        return response()->json(['encontrado' => true, ...$this->ficha($cliente)]);
    }

    /** GET /api/cv/clientes/{cliente} */
    public function show(Cliente $cliente): JsonResponse
    {
        return response()->json($this->ficha($cliente));
    }

    /** POST /api/cv/clientes */
    public function store(Request $request, ServicioClientes $servicio): JsonResponse
    {
        $datos = $request->validate([
            'uuid'             => ['nullable', 'uuid'],
            'nombre'           => ['required', 'string', 'max:120'],
            'telefono'         => ['required', 'string', 'max:20'],
            'telefono_alterno' => ['nullable', 'string', 'max:20'],
            'notas'            => ['nullable', 'string', 'max:2000'],
            'es_frecuente'     => ['nullable', 'boolean'],
        ]);
        [$cliente, $creado, $unido] = $servicio->crear($datos, $request->user());

        return response()->json([...$cliente->aApi(), 'unido_a_existente' => $unido], $creado ? 201 : 200);
    }

    /** PUT /api/cv/clientes/{cliente} */
    public function update(Request $request, Cliente $cliente, ServicioClientes $servicio): JsonResponse
    {
        $datos = $request->validate([
            'nombre'           => ['sometimes', 'string', 'max:120'],
            'telefono'         => ['sometimes', 'string', 'max:20'],
            'telefono_alterno' => ['sometimes', 'nullable', 'string', 'max:20'],
            'notas'            => ['sometimes', 'nullable', 'string', 'max:2000'],
            'es_frecuente'     => ['sometimes', 'boolean'],
        ]);

        return response()->json($servicio->actualizar($cliente, $datos, $request->user())->aApi());
    }

    private function ficha(Cliente $cliente): array
    {
        $reservas = Reserva::where('cliente_id', $cliente->id)->with(['cliente', 'pagos'])->orderByDesc('inicio')->limit(20)->get();
        $todas    = Reserva::where('cliente_id', $cliente->id)->get(['estado', 'asistencia', 'duracion_min']);

        return [
            'cliente'   => $cliente->aApi(),
            'resumen'   => [
                'reservas'      => $todas->count(),
                'confirmadas'   => $todas->where('estado', 'confirmada')->count(),
                'canceladas'    => $todas->where('estado', 'cancelada')->count(),
                'no_asistio'    => $todas->where('asistencia', 'no_asistio')->count(),
                'horas_jugadas' => round($todas->where('estado', 'confirmada')->sum('duracion_min') / 60, 2),
            ],
            'historial' => $reservas->map->aApi()->values(),
        ];
    }
}
