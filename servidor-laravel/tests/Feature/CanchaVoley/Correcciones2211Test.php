<?php

namespace Tests\Feature\CanchaVoley;

use App\Models\Notificacion;
use App\Models\Pago;
use App\Models\PagoInformado;
use App\Models\Reserva;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Str;
use Tests\TestCase;

/**
 * Correcciones del 22/11/2026, a partir de las pruebas con un celular real:
 *  1. Disponibilidad sin conexión en Android (ocupación que guarda la app del cliente).
 *  2. Acciones contradictorias después de registrar la asistencia.
 *  3. Avisos (notificaciones) con la reserva a la que se refieren.
 *  4. El cliente informa el pago de su adelanto; el personal lo verifica.
 *  5. Pagos en reservas canceladas y devoluciones.
 * Todos los datos son ficticios (ver EscenarioCancha): martes 1/12/2026 10:00 en Lima.
 */
class Correcciones2211Test extends TestCase
{
    use EscenarioCancha, RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        $this->prepararEscenario();
    }

    private function pago(string $reservaUuid, array $extra = []): array
    {
        return ['uuid' => (string) Str::uuid(), 'reserva_uuid' => $reservaUuid, 'tipo' => 'pago', 'metodo' => 'efectivo', 'monto' => 10, ...$extra];
    }

    private function sync(array $ops)
    {
        return $this->postJson('/api/cv/sync/operaciones', ['dispositivo_uuid' => (string) Str::uuid(), 'operaciones' => $ops])->assertOk();
    }

    private function op(string $tipo, array $datos, string $creadaEn): array
    {
        return ['op_uuid' => (string) Str::uuid(), 'tipo' => $tipo, 'entidad_uuid' => $datos['uuid'] ?? null, 'datos' => $datos, 'creada_en' => $this->t($creadaEn)];
    }

    // ── 5. Pagos en reservas canceladas ───────────────────────────────────────

    public function test_cancelada_sin_pagos_no_admite_pagos_ni_devoluciones(): void
    {
        $r = $this->crearReserva('2026-12-02 20:00', '2026-12-02 21:00');
        $this->postJson("/api/cv/reservas/{$r->uuid}/cancelar", ['motivo' => 'El cliente canceló'])->assertOk();

        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['tipo' => 'adelanto']))->assertStatus(409);
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid))->assertStatus(409);
        $this->como($this->admin);
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['tipo' => 'devolucion', 'observacion' => 'Sin motivo real']))
             ->assertStatus(422)->assertJsonPath('message', 'Esta reserva no tiene pagos que devolver.');
        $this->assertSame(0, Pago::count());
        $this->getJson("/api/cv/reservas/{$r->uuid}")->assertJsonPath('saldo', '0.00')->assertJsonPath('estado_pago', 'sin_pago');
    }

    public function test_cancelada_con_adelanto_solo_admite_la_devolucion_de_lo_cobrado(): void
    {
        $r = $this->crearReserva('2026-12-02 20:00', '2026-12-02 21:00');           // S/ 40.00
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['tipo' => 'adelanto', 'monto' => 20]))->assertCreated();
        $this->postJson("/api/cv/reservas/{$r->uuid}/cancelar", ['motivo' => 'Lluvia'])->assertOk()
             ->assertJsonPath('estado_pago', 'saldo_a_favor')->assertJsonPath('saldo', '-20.00');

        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['tipo' => 'devolucion', 'monto' => 20, 'observacion' => 'Lluvia']))->assertForbidden();
        $this->como($this->admin);
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['tipo' => 'devolucion', 'monto' => 25, 'observacion' => 'Lluvia']))->assertStatus(422);
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['tipo' => 'devolucion', 'monto' => 20, 'observacion' => 'Lluvia: se devolvió el adelanto']))
             ->assertCreated()->assertJsonPath('reserva.total_pagado', '0.00')->assertJsonPath('reserva.estado_pago', 'sin_pago');
        // Ya no queda nada que devolver.
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['tipo' => 'devolucion', 'monto' => 1, 'observacion' => 'Otra vez']))->assertStatus(422);
        $this->assertSame(2, Pago::where('reserva_id', $r->id)->count());
    }

    public function test_no_se_anula_un_cobro_que_ya_se_devolvio(): void
    {
        $r = $this->crearReserva('2026-12-02 20:00', '2026-12-02 21:00');
        $adelanto = $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['tipo' => 'adelanto', 'monto' => 20]))->assertCreated()->json('pago.uuid');
        $this->postJson("/api/cv/reservas/{$r->uuid}/cancelar", ['motivo' => 'Lluvia'])->assertOk();
        $this->como($this->admin);
        $devolucion = $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['tipo' => 'devolucion', 'monto' => 20, 'observacion' => 'Se devolvió el adelanto']))
            ->assertCreated()->json('pago.uuid');

        // Anular el adelanto ya devuelto dejaría «pagado» en −20: el servidor lo impide.
        $this->postJson("/api/cv/pagos/{$adelanto}/anular", ['motivo' => 'Error'])->assertStatus(409)->assertJsonPath('conflicto', 'devolucion');
        $this->assertSame('valido', Pago::where('uuid', $adelanto)->value('estado'));
        $this->getJson("/api/cv/reservas/{$r->uuid}")->assertJsonPath('total_pagado', '0.00');

        // Orden correcto si todo fue un error: primero la devolución, luego el cobro.
        $this->postJson("/api/cv/pagos/{$devolucion}/anular", ['motivo' => 'La devolución no se hizo'])->assertOk()
             ->assertJsonPath('reserva.total_pagado', '20.00')->assertJsonPath('reserva.estado_pago', 'saldo_a_favor');
        $this->postJson("/api/cv/pagos/{$adelanto}/anular", ['motivo' => 'El adelanto tampoco se cobró'])->assertOk()
             ->assertJsonPath('reserva.total_pagado', '0.00');
        // Los comprobantes no se borran: quedan anulados y en el historial.
        $this->assertSame(2, Pago::where('reserva_id', $r->id)->where('estado', 'anulado')->count());
        $this->assertSame(2, $r->eventos()->where('tipo', 'pago_anulado')->count());
    }

    public function test_cobro_hecho_sin_conexion_antes_de_cancelar_se_conserva_como_saldo_a_favor(): void
    {
        $r = $this->crearReserva('2026-12-02 20:00', '2026-12-02 21:00');
        // 09:50: la recepcionista cobró sin internet; 10:00 el cliente canceló desde la app (en línea).
        $this->postJson("/api/cv/reservas/{$r->uuid}/cancelar", ['motivo' => 'Cancelada'])->assertOk();
        $this->como($this->recepcion);
        $res = $this->sync([$this->op('registrar_pago', ['uuid' => (string) Str::uuid(), 'reserva_uuid' => $r->uuid, 'tipo' => 'adelanto',
            'metodo' => 'yape', 'monto' => 20, 'numero_operacion' => 'OFF-1'], '2026-12-01 09:50')]);
        $this->assertSame('aplicada', $res->json('resultados.0.resultado'));
        $this->assertStringContainsString('saldo a favor', $res->json('resultados.0.advertencia'));

        // En cambio, un cobro hecho (sin conexión) DESPUÉS de la cancelación se rechaza.
        $this->travel(5)->minutes();
        $res = $this->sync([$this->op('registrar_pago', ['uuid' => (string) Str::uuid(), 'reserva_uuid' => $r->uuid, 'tipo' => 'pago',
            'metodo' => 'efectivo', 'monto' => 20], '2026-12-01 10:03')]);
        $this->assertSame('rechazada', $res->json('resultados.0.resultado'));
        $this->assertSame(409, $res->json('resultados.0.codigo'));
        $this->assertSame(1, Pago::where('reserva_id', $r->id)->count());
    }

    public function test_en_linea_no_se_cobra_mas_que_el_saldo_pendiente(): void
    {
        $r = $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');           // S/ 30.00
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['monto' => 35]))->assertStatus(422)->assertJsonPath('falta', 'monto');
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['monto' => 30]))->assertCreated()->assertJsonPath('reserva.estado_pago', 'pagado');
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['monto' => 1]))->assertStatus(422)
             ->assertJsonPath('message', 'Esta reserva ya está pagada por completo.');
        // Sin conexión no se pierde un cobro real: se acepta y se avisa del saldo a favor.
        $res = $this->sync([$this->op('registrar_pago', ['uuid' => (string) Str::uuid(), 'reserva_uuid' => $r->uuid, 'tipo' => 'pago',
            'metodo' => 'efectivo', 'monto' => 5], '2026-12-01 09:59')]);
        $this->assertSame('aplicada', $res->json('resultados.0.resultado'));
        $this->assertStringContainsString('a favor del cliente', $res->json('resultados.0.advertencia'));
    }

    public function test_cada_pago_actualiza_la_reserva_para_que_se_sincronice(): void
    {
        $r = $this->crearReserva('2026-12-02 10:00', '2026-12-02 12:00');           // S/ 60.00 sin adelanto obligatorio
        $antes = $r->fresh()->updated_at;
        $this->travel(2)->minutes();
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['monto' => 20]))->assertCreated();
        $this->assertTrue($r->fresh()->updated_at->greaterThan($antes));

        // La app del cliente recibe la reserva con el pagado y el saldo nuevos en la descarga incremental.
        $this->como($this->usuarioCliente);
        $propia = $this->crearReserva('2026-12-03 10:00', '2026-12-03 11:00', ['cliente_id' => $this->clienteApp->id]);
        $this->como($this->usuarioCliente);
        $cursor = $this->getJson('/api/cv/sync/cambios')->json('cursor');
        $this->travel(5)->minutes();
        $this->como($this->recepcion);
        $this->postJson('/api/cv/pagos', $this->pago($propia->uuid, ['monto' => 10]))->assertCreated();
        $this->como($this->usuarioCliente);
        $c = $this->getJson('/api/cv/sync/cambios?desde=' . urlencode($cursor))->assertOk();
        $this->assertSame('10.00', collect($c->json('reservas'))->firstWhere('uuid', $propia->uuid)['total_pagado']);
    }

    // ── 2. Ciclo de vida después de la asistencia ─────────────────────────────

    public function test_despues_de_asistio_no_se_marca_no_asistio_ni_se_cancela_ni_se_cambia_el_horario(): void
    {
        $r = $this->crearReserva('2026-12-01 10:30', '2026-12-01 11:30');
        $this->postJson("/api/cv/reservas/{$r->uuid}/asistencia", ['asistio' => true])->assertOk()->assertJsonPath('asistencia', 'asistio');
        // Marcar lo mismo otra vez no agrega nada.
        $this->postJson("/api/cv/reservas/{$r->uuid}/asistencia", ['asistio' => true])->assertOk();
        $this->assertSame(1, $r->eventos()->where('tipo', 'asistencia')->count());

        $this->travel(40)->minutes();
        $this->postJson("/api/cv/reservas/{$r->uuid}/asistencia", ['asistio' => false])->assertStatus(409)->assertJsonPath('asistencia', 'asistio');
        $this->postJson("/api/cv/reservas/{$r->uuid}/cancelar", ['motivo' => 'Error'])->assertStatus(409);
        $this->postJson("/api/cv/reservas/{$r->uuid}/reprogramar", ['inicio' => $this->t('2026-12-01 15:00'), 'fin' => $this->t('2026-12-01 16:00')])->assertStatus(409);
        // Las notas sí se pueden editar y el saldo pendiente se sigue cobrando.
        $this->postJson("/api/cv/reservas/{$r->uuid}/editar", ['notas' => 'Jugaron completo'])->assertOk()->assertJsonPath('notas', 'Jugaron completo');
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['monto' => 30]))->assertCreated()->assertJsonPath('reserva.estado_pago', 'pagado');
        $this->assertSame('asistio', $r->fresh()->asistencia);
        $this->assertSame('confirmada', $r->fresh()->estado);
    }

    public function test_solo_la_administradora_corrige_la_asistencia_y_con_motivo(): void
    {
        $r = $this->crearReserva('2026-12-01 10:30', '2026-12-01 11:30');
        $this->postJson("/api/cv/reservas/{$r->uuid}/asistencia", ['asistio' => true])->assertOk();
        $this->travel(40)->minutes();
        $this->postJson("/api/cv/reservas/{$r->uuid}/asistencia", ['asistio' => false, 'corregir' => true, 'motivo' => 'Me equivoqué'])->assertStatus(409);

        $this->como($this->admin);
        $this->postJson("/api/cv/reservas/{$r->uuid}/asistencia", ['asistio' => false, 'corregir' => true])->assertStatus(422)->assertJsonPath('falta', 'motivo');
        $this->postJson("/api/cv/reservas/{$r->uuid}/asistencia", ['asistio' => false, 'corregir' => true, 'motivo' => 'Se marcó en la reserva equivocada'])
             ->assertOk()->assertJsonPath('asistencia', 'no_asistio');
        $evento = $r->eventos()->where('tipo', 'asistencia_corregida')->first();
        $this->assertNotNull($evento);
        $this->assertStringContainsString('Se marcó en la reserva equivocada', $evento->descripcion);
    }

    public function test_no_asistio_recien_despues_de_la_hora_y_la_tolerancia(): void
    {
        $this->reglas(['tolerancia_no_show_min' => 15]);
        $r = $this->crearReserva('2026-12-01 10:30', '2026-12-01 11:30');
        $this->postJson("/api/cv/reservas/{$r->uuid}/asistencia", ['asistio' => false])->assertStatus(409);      // 10:00
        $this->travel(40)->minutes();                                                                         // 10:40
        $this->postJson("/api/cv/reservas/{$r->uuid}/asistencia", ['asistio' => false])->assertStatus(409);
        $this->travel(10)->minutes();                                                                         // 10:50
        $this->postJson("/api/cv/reservas/{$r->uuid}/asistencia", ['asistio' => false])->assertOk()->assertJsonPath('asistencia', 'no_asistio');
        // Llega tarde, todavía dentro de su horario: la recepción registra la llegada.
        $this->travel(5)->minutes();                                                                          // 10:55
        $this->postJson("/api/cv/reservas/{$r->uuid}/asistencia", ['asistio' => true])->assertOk()->assertJsonPath('asistencia', 'asistio');
        $this->assertNotNull($r->fresh()->check_in_en);
    }

    public function test_despues_de_terminar_no_asistio_no_se_cambia_sin_correccion(): void
    {
        $r = $this->crearReserva('2026-12-01 10:30', '2026-12-01 11:30');
        $this->travel(65)->minutes();                                                                         // 11:05
        $this->postJson("/api/cv/reservas/{$r->uuid}/asistencia", ['asistio' => false])->assertOk();
        $this->travel(30)->minutes();                                                                         // 11:35, ya terminó
        $this->postJson("/api/cv/reservas/{$r->uuid}/asistencia", ['asistio' => true])->assertStatus(409);
    }

    public function test_precio_despues_de_pagos_o_asistencia_requiere_motivo_y_no_baja_de_lo_pagado(): void
    {
        $r = $this->crearReserva('2026-12-01 10:30', '2026-12-01 11:30');                                      // S/ 30.00
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['monto' => 20]))->assertCreated();
        $this->como($this->recepcion);
        $this->postJson("/api/cv/reservas/{$r->uuid}/editar", ['precio_final' => 25, 'motivo' => 'Descuento'])->assertForbidden();
        $this->como($this->admin);
        $this->postJson("/api/cv/reservas/{$r->uuid}/editar", ['precio_final' => 25])->assertStatus(422)->assertJsonPath('falta', 'motivo');
        $this->postJson("/api/cv/reservas/{$r->uuid}/editar", ['precio_final' => 15, 'motivo' => 'Descuento'])->assertStatus(422)->assertJsonPath('falta', 'precio_final');
        $this->postJson("/api/cv/reservas/{$r->uuid}/editar", ['precio_final' => 25, 'motivo' => 'Cliente frecuente'])->assertOk()
             ->assertJsonPath('precio_final', '25.00')->assertJsonPath('saldo', '5.00');
        $this->assertStringContainsString('Cliente frecuente', $r->eventos()->where('tipo', 'precio_cambiado')->value('descripcion'));
    }

    public function test_las_notas_se_editan_tambien_en_una_reserva_cancelada_pero_no_el_precio(): void
    {
        $r = $this->crearReserva('2026-12-02 20:00', '2026-12-02 21:00');
        $this->postJson("/api/cv/reservas/{$r->uuid}/cancelar", ['motivo' => 'Lluvia'])->assertOk();
        $this->postJson("/api/cv/reservas/{$r->uuid}/editar", ['notas' => 'Se le avisó por WhatsApp'])->assertOk();
        $this->como($this->admin);
        $this->postJson("/api/cv/reservas/{$r->uuid}/editar", ['precio_final' => 10])->assertStatus(409);
    }

    public function test_reglas_de_asistencia_tambien_sin_conexion(): void
    {
        $r = $this->crearReserva('2026-12-01 10:30', '2026-12-01 11:30');
        $this->postJson("/api/cv/reservas/{$r->uuid}/asistencia", ['asistio' => true])->assertOk();
        $this->travel(40)->minutes();
        $res = $this->sync([$this->op('marcar_asistencia', ['reserva_uuid' => $r->uuid, 'asistio' => false], '2026-12-01 10:39')]);
        $this->assertSame('rechazada', $res->json('resultados.0.resultado'));
        $this->assertSame('asistio', $r->fresh()->asistencia);
    }

    // ── Plazo del adelanto ────────────────────────────────────────────────────

    public function test_pendiente_con_plazo_vencido_no_ocupa_y_se_informa(): void
    {
        $this->reglas(['adelanto_tipo' => 'porcentaje', 'adelanto_valor' => 50, 'adelanto_obligatorio' => true, 'adelanto_plazo_min' => 30]);
        $r = $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $this->getJson("/api/cv/reservas/{$r->uuid}")->assertJsonPath('adelanto_vencido', false);
        $d = $this->getJson('/api/cv/disponibilidad?cancha_id=' . $this->cancha->id . '&fecha=2026-12-02')->json('ocupados');
        $this->assertCount(1, $d);

        $this->travel(31)->minutes();
        $this->getJson("/api/cv/reservas/{$r->uuid}")->assertJsonPath('adelanto_vencido', true)->assertJsonPath('estado', 'pendiente');
        $this->assertCount(0, $this->getJson('/api/cv/disponibilidad?cancha_id=' . $this->cancha->id . '&fecha=2026-12-02')->json('ocupados'));
        // Un pago tardío todavía confirma la reserva si nadie tomó el horario.
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['tipo' => 'adelanto', 'monto' => 15]))->assertCreated()
             ->assertJsonPath('reserva.estado', 'confirmada')->assertJsonPath('reserva.adelanto_vencido', false);
    }

    // ── 4. El cliente informa el pago del adelanto ────────────────────────────

    public function test_la_administradora_publica_los_datos_de_pago_y_el_cliente_los_recibe(): void
    {
        $this->como($this->admin);
        $this->putJson('/api/cv/negocio', ['nombre' => 'Negocio de prueba', 'pago_yape' => 'abc'])->assertStatus(422);
        $this->putJson('/api/cv/negocio', ['nombre' => 'Negocio de prueba', 'pago_yape' => '911 111 111', 'pago_titular' => 'Titular Ficticio',
            'pago_instrucciones' => 'Envía la constancia por WhatsApp.'])->assertOk()->assertJsonPath('pago_yape', '911 111 111');
        // Una app anterior que solo envía nombre/teléfono/dirección no borra los datos de pago.
        $this->putJson('/api/cv/negocio', ['nombre' => 'Negocio de prueba', 'telefono' => '900000000'])->assertOk()->assertJsonPath('pago_titular', 'Titular Ficticio');

        $this->como($this->usuarioCliente);
        $this->getJson('/api/cv/catalogo')->assertJsonPath('negocio.pago_yape', '911 111 111')->assertJsonPath('negocio.pago_plin', null);
    }

    public function test_pago_informado_por_el_cliente_no_cambia_el_saldo_hasta_que_el_personal_lo_registra(): void
    {
        $this->reglas(['adelanto_tipo' => 'porcentaje', 'adelanto_valor' => 50, 'adelanto_obligatorio' => true, 'adelanto_plazo_min' => 180]);
        $this->como($this->usuarioCliente);
        $uuid = $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-03 10:00', '2026-12-03 11:00', ['cliente_id' => null]))->assertCreated()->json('uuid');
        $this->como($this->admin);
        $this->postJson("/api/cv/reservas/{$uuid}/aprobar")->assertOk()->assertJsonPath('pendiente_por', 'adelanto');

        $this->como($this->usuarioCliente);
        $this->postJson("/api/cv/reservas/{$uuid}/informar-pago", ['metodo' => 'efectivo', 'numero_operacion' => '12345', 'monto' => 15])->assertStatus(422);
        $this->postJson("/api/cv/reservas/{$uuid}/informar-pago", ['metodo' => 'yape', 'numero_operacion' => '12345', 'monto' => 40])->assertStatus(422);
        $r = $this->postJson("/api/cv/reservas/{$uuid}/informar-pago", ['metodo' => 'yape', 'numero_operacion' => '12345', 'monto' => 15])->assertCreated();
        $r->assertJsonPath('reserva.total_pagado', '0.00')->assertJsonPath('reserva.estado', 'pendiente')
          ->assertJsonPath('reserva.pago_informado.numero_operacion', '12345')->assertJsonPath('reserva.pago_informado.estado', 'pendiente');
        // El mismo aviso enviado otra vez no se duplica.
        $this->postJson("/api/cv/reservas/{$uuid}/informar-pago", ['metodo' => 'yape', 'numero_operacion' => '12345', 'monto' => 15])->assertOk();
        $this->assertSame(1, PagoInformado::count());
        // El personal recibe el aviso para verificarlo.
        $this->assertTrue(Notificacion::where('user_id', $this->recepcion->id)->where('tipo', 'pago_informado')->exists());
        $this->assertTrue(Notificacion::where('user_id', $this->admin->id)->where('tipo', 'pago_informado')->exists());

        // El personal no puede «informar»; registra el pago verificado con el mismo número.
        $this->como($this->recepcion);
        $this->postJson("/api/cv/reservas/{$uuid}/informar-pago", ['metodo' => 'yape', 'numero_operacion' => '99999', 'monto' => 15])->assertForbidden();
        $this->postJson('/api/cv/pagos', $this->pago($uuid, ['tipo' => 'adelanto', 'metodo' => 'yape', 'numero_operacion' => '12345', 'monto' => 15]))
             ->assertCreated()->assertJsonPath('reserva.estado', 'confirmada');
        $this->assertSame('verificado', PagoInformado::first()->estado);
        $this->assertNotNull(PagoInformado::first()->pago_id);
        // Ya registrada, esa operación no se puede volver a informar.
        $this->como($this->usuarioCliente);
        $this->postJson("/api/cv/reservas/{$uuid}/informar-pago", ['metodo' => 'yape', 'numero_operacion' => '12345', 'monto' => 15])->assertStatus(409);
    }

    public function test_pago_informado_descartado_avisa_al_cliente_y_otro_cliente_no_puede_informar(): void
    {
        $r = $this->crearReserva('2026-12-03 10:00', '2026-12-03 11:00', ['cliente_id' => $this->clienteApp->id]);
        $this->como($this->usuarioCliente);
        $informado = $this->postJson("/api/cv/reservas/{$r->uuid}/informar-pago", ['metodo' => 'plin', 'numero_operacion' => 'PL-777', 'monto' => 10])->assertCreated()
            ->json('pago_informado.uuid');

        $this->como($this->recepcion);
        $this->postJson("/api/cv/pagos-informados/{$informado}/descartar", ['motivo' => 'x'])->assertStatus(422);
        $this->postJson("/api/cv/pagos-informados/{$informado}/descartar", ['motivo' => 'No figura en la cuenta del negocio'])->assertOk()
             ->assertJsonPath('pago_informado.estado', 'descartado')->assertJsonPath('reserva.pago_informado', null);
        $this->postJson("/api/cv/pagos-informados/{$informado}/descartar", ['motivo' => 'Otra vez'])->assertStatus(409);
        $this->assertTrue(Notificacion::where('user_id', $this->usuarioCliente->id)->where('tipo', 'pago_informado_descartado')->exists());

        // Otra cuenta de cliente no informa pagos de reservas ajenas.
        $otro = \App\Models\User::factory()->create(['rol' => 'cliente', 'activo' => true]);
        \App\Models\Cliente::create(['uuid' => (string) Str::uuid(), 'negocio_id' => $this->negocio->id, 'user_id' => $otro->id, 'nombre' => 'Otro', 'telefono' => '51900000009']);
        $this->como($otro);
        $this->postJson("/api/cv/reservas/{$r->uuid}/informar-pago", ['metodo' => 'yape', 'numero_operacion' => '55555', 'monto' => 10])->assertForbidden();
    }

    // ── 3. Notificaciones ─────────────────────────────────────────────────────

    public function test_avisos_al_cliente_con_su_reserva_y_sin_duplicados(): void
    {
        $this->reglas(['adelanto_tipo' => 'porcentaje', 'adelanto_valor' => 50, 'adelanto_obligatorio' => true, 'adelanto_plazo_min' => 180]);
        $this->como($this->usuarioCliente);
        $uuid = $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-03 10:00', '2026-12-03 11:00', ['cliente_id' => null]))->json('uuid');
        $this->como($this->admin);
        $this->postJson("/api/cv/reservas/{$uuid}/aprobar")->assertOk();
        $this->como($this->recepcion);
        $this->postJson('/api/cv/pagos', $this->pago($uuid, ['tipo' => 'adelanto', 'monto' => 5]))->assertCreated();     // parcial
        $this->postJson('/api/cv/pagos', $this->pago($uuid, ['tipo' => 'adelanto', 'monto' => 10]))->assertCreated();    // completa el adelanto

        $this->como($this->usuarioCliente);
        $n = $this->getJson('/api/cv/notificaciones')->assertOk();
        $tipos = collect($n->json('notificaciones'))->pluck('tipo')->all();
        $this->assertSame(['reserva_confirmada', 'pago_registrado', 'reserva_aprobada'], $tipos);   // sin «pago registrado» por el que confirmó
        $this->assertSame($uuid, $n->json('notificaciones.0.reserva_uuid'));
        $this->assertSame(3, $n->json('no_leidas'));

        // Marcar una como leída (al abrirla) y no las de otro usuario.
        $id = $n->json('notificaciones.1.id');
        $this->postJson("/api/cv/notificaciones/{$id}/leer")->assertOk()->assertJsonPath('ok', true);
        $this->assertSame(2, $this->getJson('/api/cv/notificaciones')->json('no_leidas'));
        $ajena = Notificacion::where('user_id', $this->admin->id)->value('id');
        $this->postJson("/api/cv/notificaciones/{$ajena}/leer")->assertOk()->assertJsonPath('ok', false);
    }

    // ── 1. Ocupación para consultar horarios sin conexión ─────────────────────

    public function test_solicitud_sin_conexion_en_un_horario_ya_tomado_se_rechaza_una_vez_y_avisa_al_cliente(): void
    {
        $this->crearReserva('2026-12-06 10:00', '2026-12-06 12:00');       // otra persona lo tomó mientras el celular estaba sin conexión
        $this->como($this->usuarioCliente);
        $datos = $this->datosReserva('2026-12-06 11:00', '2026-12-06 12:00', ['cliente_id' => null]);
        $op = $this->op('crear_reserva', $datos, '2026-12-01 09:00');
        $this->sync([$op])->assertJsonPath('resultados.0.resultado', 'rechazada');
        $this->sync([$op]);                                                   // el celular reintenta: no se duplica
        $this->assertSame(1, Reserva::where('uuid', $datos['uuid'])->count());
        $this->assertSame('rechazada', Reserva::where('uuid', $datos['uuid'])->value('estado'));
        $aviso = Notificacion::where('user_id', $this->usuarioCliente->id)->where('tipo', 'reserva_rechazada_sync')->sole();
        $this->assertStringStartsWith('Tu solicitud para el', $aviso->mensaje);
        $this->assertStringNotContainsString('Llama al cliente', $aviso->mensaje);
    }

    public function test_la_app_del_cliente_recibe_la_ocupacion_sin_datos_de_otros_clientes(): void
    {
        $ajena = $this->crearReserva('2026-12-06 10:00', '2026-12-06 12:00');          // de otro cliente (Pedro, por llamada)
        $cancelada = $this->crearReserva('2026-12-06 15:00', '2026-12-06 16:00');
        $this->postJson("/api/cv/reservas/{$cancelada->uuid}/cancelar", ['motivo' => 'Prueba'])->assertOk();
        $this->como($this->admin);
        $this->postJson('/api/cv/bloqueos', ['inicio' => $this->t('2026-12-07 08:00'), 'fin' => $this->t('2026-12-07 10:00'), 'motivo' => 'Mantenimiento'])->assertCreated();

        $this->como($this->usuarioCliente);
        $c = $this->getJson('/api/cv/sync/cambios')->assertOk();
        $this->assertSame([], $c->json('reservas'));                                    // sus reservas: ninguna
        $o = $c->json('ocupacion');
        $this->assertNotNull($o['generado_en']);
        $intervalos = collect($o['intervalos']);
        $this->assertTrue($intervalos->contains(fn ($i) => $i['inicio'] === $this->t('2026-12-06 10:00') && $i['fin'] === $this->t('2026-12-06 12:00') && $i['tipo'] === 'reserva'));
        $this->assertFalse($intervalos->contains(fn ($i) => $i['inicio'] === $this->t('2026-12-06 15:00')));     // la cancelada no ocupa
        $this->assertTrue($intervalos->contains(fn ($i) => $i['tipo'] === 'bloqueo'));
        $this->assertStringNotContainsString('Teléfono', json_encode($o));
        $this->assertStringNotContainsString((string) $ajena->uuid, json_encode($o));
        // Al personal no se le envía (ya tiene todas las reservas en su copia).
        $this->como($this->recepcion);
        $this->assertNull($this->getJson('/api/cv/sync/cambios')->json('ocupacion'));
    }
}
