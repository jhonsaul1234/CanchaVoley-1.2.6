<?php

namespace Tests\Feature\CanchaVoley;

use App\Models\Pago;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;
use Tests\TestCase;

class PagosTest extends TestCase
{
    use EscenarioCancha, RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        $this->prepararEscenario();
    }

    private function pago(string $reservaUuid, array $extra = []): array
    {
        return ['uuid' => (string) Str::uuid(), 'reserva_uuid' => $reservaUuid, 'tipo' => 'pago', 'metodo' => 'efectivo', 'monto' => 20, ...$extra];
    }

    public function test_pagos_en_efectivo_yape_y_plin_actualizan_el_saldo(): void
    {
        $r = $this->crearReserva('2026-12-02 10:00', '2026-12-02 12:00');     // 60.00
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['tipo' => 'adelanto', 'monto' => 20]))
             ->assertCreated()->assertJsonPath('reserva.estado_pago', 'con_adelanto')->assertJsonPath('reserva.saldo', '40.00');
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['metodo' => 'yape', 'monto' => 25, 'numero_operacion' => 'Y-123']))->assertCreated();
        $p = $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['metodo' => 'plin', 'monto' => 15, 'numero_operacion' => 'P-9']))->assertCreated();
        $p->assertJsonPath('reserva.estado_pago', 'pagado')->assertJsonPath('reserva.saldo', '0.00');
        $this->assertMatchesRegularExpression('/^CV-\d{6}$/', $p->json('pago.numero_comprobante'));
    }

    public function test_reintento_con_el_mismo_uuid_no_duplica_el_pago(): void
    {
        $r = $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $datos = $this->pago($r->uuid);
        $this->postJson('/api/cv/pagos', $datos)->assertCreated();
        $this->postJson('/api/cv/pagos', $datos)->assertOk();
        $this->assertSame(1, Pago::count());
    }

    public function test_el_mismo_pago_enviado_dos_veces_a_la_vez_recibe_el_mismo_pago(): void
    {
        // El envío original y su reintento llegan a la vez: el otro queda guardado justo después
        // de la comprobación inicial. Debe responder el mismo pago (200), no "ya registrado" (409).
        $r = $this->crearReserva('2026-12-02 10:00', '2026-12-02 12:00');
        $this->reenvioSimultaneo($r->uuid, $r->id, $r->negocio_id, 'yape', 'Y-555');
        $this->reenvioSimultaneo($r->uuid, $r->id, $r->negocio_id, 'efectivo', null);
        $this->assertSame(2, Pago::count());
    }

    private function reenvioSimultaneo(string $reservaUuid, int $reservaId, int $negocioId, string $metodo, ?string $numero): void
    {
        $datos = $this->pago($reservaUuid, ['metodo' => $metodo, 'numero_operacion' => $numero]);
        $otroEnvioGuardado = false;
        DB::listen(function ($consulta) use (&$otroEnvioGuardado, $datos, $reservaId, $negocioId, $metodo, $numero) {
            if ($otroEnvioGuardado || ! str_contains($consulta->sql, 'from "reservas"')) {
                return;
            }
            $otroEnvioGuardado = true;
            Pago::create([
                'uuid' => $datos['uuid'], 'negocio_id' => $negocioId, 'reserva_id' => $reservaId, 'tipo' => 'pago',
                'metodo' => $metodo, 'monto' => '20.00', 'numero_operacion' => $numero, 'pagado_en' => now(),
                'estado' => 'valido', 'registrado_por' => $this->recepcion->id,
            ]);
        });

        $this->postJson('/api/cv/pagos', $datos)->assertOk()->assertJsonPath('pago.uuid', $datos['uuid']);
        $this->assertTrue($otroEnvioGuardado);
    }

    public function test_el_mismo_numero_de_operacion_de_yape_no_entra_dos_veces(): void
    {
        $a = $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $b = $this->crearReserva('2026-12-02 12:00', '2026-12-02 13:00');
        $this->postJson('/api/cv/pagos', $this->pago($a->uuid, ['metodo' => 'yape', 'numero_operacion' => '777']))->assertCreated();
        $this->postJson('/api/cv/pagos', $this->pago($b->uuid, ['metodo' => 'yape', 'numero_operacion' => '777']))
             ->assertStatus(409)->assertJsonPath('duplicado', 'numero_operacion');
        // El mismo número en Plin es otra operación distinta.
        $this->postJson('/api/cv/pagos', $this->pago($b->uuid, ['metodo' => 'plin', 'numero_operacion' => '777']))->assertCreated();
    }

    public function test_solo_la_administradora_anula_y_el_numero_vuelve_a_quedar_libre(): void
    {
        $r = $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $uuid = $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['metodo' => 'yape', 'numero_operacion' => '555']))->json('pago.uuid');

        $this->postJson("/api/cv/pagos/{$uuid}/anular", ['motivo' => 'Se registró en otra reserva'])->assertForbidden();
        $this->como($this->admin);
        $this->postJson("/api/cv/pagos/{$uuid}/anular", ['motivo' => 'Se registró en otra reserva'])
             ->assertOk()->assertJsonPath('pago.estado', 'anulado')->assertJsonPath('reserva.estado_pago', 'sin_pago');
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['metodo' => 'yape', 'numero_operacion' => '555']))->assertCreated();
    }

    public function test_devolucion_no_supera_lo_cobrado(): void
    {
        $r = $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['monto' => 10]))->assertCreated();
        // 22/11/2026: la devolución la registra la administradora y con motivo.
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['tipo' => 'devolucion', 'monto' => 10, 'observacion' => 'Cambio de planes']))->assertForbidden();
        $this->como($this->admin);
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['tipo' => 'devolucion', 'monto' => 10]))->assertStatus(422)->assertJsonPath('falta', 'observacion');
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['tipo' => 'devolucion', 'monto' => 15, 'observacion' => 'Cambio de planes']))->assertStatus(422);
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['tipo' => 'devolucion', 'monto' => 10, 'observacion' => 'Cambio de planes']))->assertCreated()->assertJsonPath('reserva.total_pagado', '0.00');
    }

    public function test_en_una_reserva_cancelada_no_se_registran_adelantos_ni_pagos(): void
    {
        // 22/11/2026 (antes se aceptaba como saldo a favor): en línea se rechaza; ver Correcciones2211Test
        // para el cobro hecho sin conexión cuando la reserva aún estaba activa.
        $r = $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $this->postJson("/api/cv/reservas/{$r->uuid}/cancelar", ['motivo' => 'Lluvia'])->assertOk();
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid))->assertStatus(409)->assertJsonPath('estado', 'cancelada');
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['tipo' => 'adelanto']))->assertStatus(409);
        $this->assertSame(0, Pago::count());
    }

    public function test_aviso_si_se_repite_un_pago_igual_en_efectivo(): void
    {
        $r = $this->crearReserva('2026-12-02 10:00', '2026-12-02 12:00');
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid))->assertJsonPath('advertencia', null);
        $this->assertNotNull($this->postJson('/api/cv/pagos', $this->pago($r->uuid))->json('advertencia'));
    }

    public function test_un_cliente_no_registra_pagos(): void
    {
        $r = $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00', ['cliente_id' => $this->clienteApp->id]);
        $this->como($this->usuarioCliente);
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid))->assertForbidden();
        $this->getJson("/api/cv/reservas/{$r->uuid}/comprobante")->assertOk()->assertJsonPath('reserva.uuid', $r->uuid);
    }

    public function test_caja_del_dia_por_metodo_y_por_usuario(): void
    {
        $r = $this->crearReserva('2026-12-02 10:00', '2026-12-02 12:00');
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['monto' => 20]));
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['metodo' => 'yape', 'monto' => 30, 'numero_operacion' => '1']));
        $this->como($this->admin);
        $this->postJson('/api/cv/pagos', $this->pago($r->uuid, ['tipo' => 'devolucion', 'monto' => 5, 'observacion' => 'Vuelto mal dado']))->assertCreated();
        $c = $this->getJson('/api/cv/caja?fecha=2026-12-01')->assertOk();
        $c->assertJsonPath('totales.efectivo', '15.00')->assertJsonPath('totales.yape', '30.00')->assertJsonPath('total', '45.00');
        $this->assertSame('50.00', $c->json('por_usuario')[$this->recepcion->name]);
        $this->assertSame('-5.00', $c->json('por_usuario')[$this->admin->name]);
    }
}
