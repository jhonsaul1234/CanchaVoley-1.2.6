<?php

namespace App\Http\Controllers\Api\CanchaVoley;

use App\Http\Controllers\Controller;
use App\Models\Negocio;
use App\Models\Pago;
use App\Models\PagoInformado;
use App\Models\Reserva;
use App\Services\CanchaVoley\ServicioPagos;
use App\Services\CanchaVoley\ServicioReservas;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Validation\Rule;

/** CanchaVoley: registrar y anular pagos (personal; anular solo la administradora). */
class PagoController extends Controller
{
    /** POST /api/cv/pagos */
    public function store(Request $request, ServicioPagos $servicio): JsonResponse
    {
        $datos = $request->validate([
            'uuid'             => ['nullable', 'uuid'],
            'reserva_id'       => ['nullable', 'integer'],
            'reserva_uuid'     => ['nullable', 'uuid'],
            'tipo'             => ['required', Rule::in(Pago::TIPOS)],
            'metodo'           => ['required', Rule::in(Pago::METODOS)],
            'monto'            => ['required', 'numeric', 'gt:0', 'max:99999'],
            'numero_operacion' => ['nullable', 'string', 'max:40'],
            'pagado_en'        => ['nullable', 'date'],
            'observacion'      => ['nullable', 'string', 'max:255'],
        ]);
        [$pago, $creado, $advertencia] = $servicio->registrar($datos, $request->user());

        return response()->json([
            'pago'        => $pago->aApi(),
            'reserva'     => Reserva::find($pago->reserva_id)->aApi(),
            'advertencia' => $advertencia,
        ], $creado ? 201 : 200);
    }

    /** POST /api/cv/pagos-informados/{uuid}/descartar — el personal no encontró el ingreso que informó el cliente. */
    public function descartarInformado(Request $request, string $uuid, ServicioReservas $reservas): JsonResponse
    {
        $datos = $request->validate(['motivo' => ['required', 'string', 'min:3', 'max:255']]);
        $informado = PagoInformado::where('negocio_id', Negocio::principalOFallar()->id)->where('uuid', $uuid)->firstOrFail();
        $informado = $reservas->descartarPagoInformado($informado, $request->user(), $datos['motivo']);

        return response()->json(['pago_informado' => $informado->aApi(), 'reserva' => $informado->reserva->fresh()->aApi(true)]);
    }

    /** POST /api/cv/pagos/{uuid}/anular */
    public function anular(Request $request, string $uuid, ServicioPagos $servicio): JsonResponse
    {
        $datos = $request->validate(['motivo' => ['required', 'string', 'min:3', 'max:255']]);
        $pago  = Pago::where('negocio_id', Negocio::principalOFallar()->id)->where('uuid', $uuid)->firstOrFail();
        $pago  = $servicio->anular($pago, $request->user(), $datos['motivo']);

        return response()->json(['pago' => $pago->aApi(), 'reserva' => $pago->reserva->fresh()->aApi()]);
    }
}
