<?php

namespace Tests\Feature\CanchaVoley;

use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

/**
 * Complementa RolesYNegocioRealTest: negocio de UNA cancha a S/ 20 por hora configurado como una sola franja
 * de TODO el día (00:00 a 00:00), para que el precio no dependa del horario; el catálogo que recibe la app y el
 * precio que ve el cliente en su solicitud. El horario de atención del negocio todavía no se conoce: el de este escenario es
 * FICTICIO (08:00–22:00) y solo sirve para la prueba. No toca ninguna base real (SQLite en memoria).
 */
class NegocioUnaCanchaTest extends TestCase
{
    use RefreshDatabase, EscenarioCancha;

    protected function setUp(): void
    {
        parent::setUp();
        $this->prepararEscenario(conHorario: true, conTarifas: false);   // una sola cancha, sin tarifas
    }

    /** La administradora guarda S/ 20 por hora para todos los días y todo el día (00:00 a 00:00). */
    private function configurarVeintePorHora(): void
    {
        $this->como($this->admin);
        $this->putJson("/api/cv/canchas/{$this->cancha->id}/tarifas", ['tarifas' => array_map(
            fn (int $dia) => ['dia_semana' => $dia, 'desde' => '00:00', 'hasta' => '00:00', 'precio_hora' => 20],
            range(1, 7),
        )])->assertOk()->assertJsonCount(7);
    }

    public function test_tarifa_de_20_soles_por_hora_cobra_20_40_y_60(): void
    {
        $this->configurarVeintePorHora();

        $this->assertSame('20.00', $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00')->precio_final);   // 1 h
        $this->assertSame('40.00', $this->crearReserva('2026-12-02 12:00', '2026-12-02 14:00')->precio_final);   // 2 h
        $this->assertSame('60.00', $this->crearReserva('2026-12-02 17:00', '2026-12-02 20:00')->precio_final);   // 3 h (de tarde a noche)
        $this->assertSame('30.00', $this->crearReserva('2026-12-03 09:00', '2026-12-03 10:30')->precio_final);   // 1 h 30 min
        $this->assertSame('20.00', $this->crearReserva('2026-12-05 19:00', '2026-12-05 20:00')->precio_final);   // sábado
        $this->assertSame('20.00', $this->crearReserva('2026-12-06 08:00', '2026-12-06 09:00')->precio_final);   // domingo
    }

    public function test_el_precio_se_ve_antes_de_reservar_y_la_app_recibe_la_tarifa(): void
    {
        $this->configurarVeintePorHora();
        $this->como($this->recepcion);

        $catalogo = $this->getJson('/api/cv/catalogo')->assertOk();
        $this->assertCount(1, $catalogo->json('canchas'));
        $tarifas = collect($catalogo->json('tarifas'))->where('cancha_id', $this->cancha->id);
        $this->assertCount(7, $tarifas);
        $this->assertTrue($tarifas->every(fn ($t) => (float) $t['precio_hora'] === 20.0));
    }

    public function test_el_cliente_de_la_app_ve_el_mismo_precio(): void
    {
        $this->configurarVeintePorHora();
        $this->como($this->usuarioCliente);
        $r = $this->postJson('/api/cv/reservas', [...$this->datosReserva('2026-12-04 16:00', '2026-12-04 18:00'), 'cliente_id' => null])
                  ->assertCreated();
        $this->assertSame('40.00', $r->json('precio_final') ?? $r->json('precio_calculado'));
    }
}
