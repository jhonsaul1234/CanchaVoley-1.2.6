<?php

namespace App\Http\Controllers\Api\CanchaVoley;

use App\Exceptions\CanchaVoley\ReservaException;
use App\Http\Controllers\Controller;
use App\Models\Negocio;
use App\Models\Reserva;
use App\Services\CanchaVoley\ServicioReservas;
use Carbon\CarbonImmutable;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Validation\Rule;

/**
 * CanchaVoley: reservas. El personal ve todas; un cliente solo las suyas.
 * Las solicitudes de clientes quedan pendientes hasta que la administradora las aprueba.
 */
class ReservaController extends Controller
{
    public function __construct(private readonly ServicioReservas $servicio) {}

    /** GET /api/cv/reservas?desde=&hasta=&cancha_id=&estado= */
    public function index(Request $request): JsonResponse
    {
        $datos = $request->validate([
            'desde'     => ['nullable', 'date'],
            'hasta'     => ['nullable', 'date'],
            'cancha_id' => ['nullable', 'integer'],
            'estado'    => ['nullable', Rule::in(['pendiente', 'confirmada', 'cancelada', 'rechazada'])],
        ]);
        $user    = $request->user();
        $negocio = Negocio::principalOFallar();
        $desde   = isset($datos['desde']) ? CarbonImmutable::parse($datos['desde'])->startOfDay() : null;
        $hasta   = isset($datos['hasta']) ? CarbonImmutable::parse($datos['hasta'])->endOfDay() : null;

        $reservas = Reserva::where('negocio_id', $negocio->id)
            ->when(! $user->esPersonal(), fn ($q) => $q->where('cliente_id', $user->cliente?->id ?? 0))
            ->when($desde, fn ($q) => $q->where('fin', '>=', $desde))
            ->when($hasta, fn ($q) => $q->where('inicio', '<=', $hasta))
            ->when($datos['cancha_id'] ?? null, fn ($q, $c) => $q->where('cancha_id', $c))
            ->when($datos['estado'] ?? null, fn ($q, $e) => $q->where('estado', $e))
            ->with(['cliente', 'pagos', 'pagosInformados'])
            ->orderBy('inicio')->limit(500)->get();

        return response()->json($reservas->map->aApi()->values());
    }

    /** GET /api/cv/reservas/{uuid} — detalle con pagos e historial. */
    public function show(Request $request, string $uuid): JsonResponse
    {
        $reserva = $this->buscar($uuid);
        $this->servicio->exigirVisible($reserva, $request->user());

        return response()->json($reserva->aApi(true));
    }

    /** POST /api/cv/reservas */
    public function store(Request $request): JsonResponse
    {
        $datos = $request->validate([
            'uuid'         => ['nullable', 'uuid'],
            'cancha_id'    => ['required', 'integer'],
            'cliente_id'   => ['nullable', 'integer'],
            'cliente_uuid' => ['nullable', 'uuid'],
            'inicio'       => ['required', 'date'],
            'fin'          => ['required', 'date'],
            'origen'       => ['nullable', Rule::in(Reserva::ORIGENES)],
            'precio_final' => ['nullable', 'numeric', 'min:0', 'max:99999'],
            'notas'        => ['nullable', 'string', 'max:2000'],
        ]);
        [$reserva, $creada] = $this->servicio->crear($datos, $request->user());

        return response()->json($reserva->aApi(true), $creada ? 201 : 200);
    }

    /** POST /api/cv/reservas/{uuid}/aprobar (administradora) */
    public function aprobar(Request $request, string $uuid): JsonResponse
    {
        $datos = $request->validate(['precio_final' => ['nullable', 'numeric', 'min:0', 'max:99999']]);

        return response()->json($this->servicio->aprobar($this->buscar($uuid), $request->user(), $datos['precio_final'] ?? null)->aApi(true));
    }

    /** POST /api/cv/reservas/{uuid}/rechazar (administradora) */
    public function rechazar(Request $request, string $uuid): JsonResponse
    {
        $datos = $request->validate(['motivo' => ['required', 'string', 'min:3', 'max:255']]);

        return response()->json($this->servicio->rechazar($this->buscar($uuid), $request->user(), $datos['motivo'])->aApi(true));
    }

    /** POST /api/cv/reservas/{uuid}/cancelar */
    public function cancelar(Request $request, string $uuid): JsonResponse
    {
        $datos = $request->validate(['motivo' => ['required', 'string', 'min:3', 'max:255']]);

        return response()->json($this->servicio->cancelar($this->buscar($uuid), $request->user(), $datos['motivo'])->aApi(true));
    }

    /** POST /api/cv/reservas/{uuid}/reprogramar */
    public function reprogramar(Request $request, string $uuid): JsonResponse
    {
        $datos = $request->validate(['inicio' => ['required', 'date'], 'fin' => ['required', 'date']]);

        return response()->json($this->servicio->reprogramar($this->buscar($uuid), $request->user(), $datos['inicio'], $datos['fin'])->aApi(true));
    }

    /** POST /api/cv/reservas/{uuid}/editar — precio acordado (administradora, con motivo si ya hay pagos o asistencia) y notas (personal). */
    public function editar(Request $request, string $uuid): JsonResponse
    {
        $datos = $request->validate([
            'precio_final' => ['nullable', 'numeric', 'min:0', 'max:99999'],
            'notas'        => ['nullable', 'string', 'max:2000'],
            'motivo'       => ['nullable', 'string', 'max:255'],
        ]);
        $datos = array_intersect_key($datos, array_flip(array_keys($request->only(['precio_final', 'notas', 'motivo']))));

        return response()->json($this->servicio->editar($this->buscar($uuid), $request->user(), $datos)->aApi(true));
    }

    /** POST /api/cv/reservas/{uuid}/asistencia — {asistio, corregir?, motivo?} (corregir: solo la administradora). */
    public function asistencia(Request $request, string $uuid): JsonResponse
    {
        $datos = $request->validate([
            'asistio'  => ['required', 'boolean'],
            'corregir' => ['nullable', 'boolean'],
            'motivo'   => ['nullable', 'string', 'max:255'],
        ]);

        return response()->json($this->servicio->marcarAsistencia($this->buscar($uuid), $request->user(), (bool) $datos['asistio'], [],
            (bool) ($datos['corregir'] ?? false), $datos['motivo'] ?? null)->aApi(true));
    }

    /** POST /api/cv/reservas/{uuid}/informar-pago — el cliente informa su pago (referencia; no cambia el saldo). */
    public function informarPago(Request $request, string $uuid): JsonResponse
    {
        $datos = $request->validate([
            'metodo'           => ['required', Rule::in(\App\Models\PagoInformado::METODOS)],
            'numero_operacion' => ['required', 'string', 'min:4', 'max:40'],
            'monto'            => ['required', 'numeric', 'gt:0', 'max:99999'],
            'nota'             => ['nullable', 'string', 'max:255'],
        ], [
            'metodo.required' => 'Indica si pagaste por Yape o por Plin.',
            'metodo.in' => 'Indica si pagaste por Yape o por Plin.',
            'numero_operacion.*' => 'Escribe el número de operación que aparece en la constancia de tu pago (de 4 a 40 caracteres).',
            'monto.*' => 'Indica el monto que pagaste.',
        ]);
        [$informado, $creado] = $this->servicio->informarPago($this->buscar($uuid), $request->user(), $datos);

        return response()->json(['pago_informado' => $informado->aApi(), 'reserva' => $informado->reserva->fresh()->aApi(true)], $creado ? 201 : 200);
    }

    /** GET /api/cv/qr/{codigo} — el personal identifica una reserva leyendo su código QR. */
    public function porQr(string $codigo): JsonResponse
    {
        $codigo  = strtoupper(preg_replace('/^CANCHAVOLEY:/i', '', trim($codigo)));
        $reserva = Reserva::where('codigo_qr', $codigo)->first()
            ?? throw new ReservaException('El código QR no corresponde a ninguna reserva.', 404);

        return response()->json($reserva->aApi(true));
    }

    /** GET /api/cv/reservas/{uuid}/comprobante — datos del comprobante interno de pagos. */
    public function comprobante(Request $request, string $uuid): JsonResponse
    {
        $reserva = $this->buscar($uuid);
        $this->servicio->exigirVisible($reserva, $request->user());
        $negocio = $reserva->negocio;

        return response()->json([
            'negocio'   => $negocio->aApi(),
            'reserva'   => $reserva->aApi(),
            'cancha'    => $reserva->cancha->nombre,
            'pagos'     => $reserva->pagos->map->aApi()->values(),
            'nota'      => 'Comprobante interno del negocio; no reemplaza un comprobante electrónico de SUNAT.',
            'emitido_en' => now()->toIso8601String(),
        ]);
    }

    private function buscar(string $uuid): Reserva
    {
        return Reserva::where('negocio_id', Negocio::principalOFallar()->id)->where('uuid', $uuid)->first()
            ?? throw new ReservaException('La reserva no existe.', 404);
    }
}
