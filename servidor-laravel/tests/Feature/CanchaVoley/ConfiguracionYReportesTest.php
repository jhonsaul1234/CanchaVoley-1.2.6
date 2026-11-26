<?php

namespace Tests\Feature\CanchaVoley;

use App\Models\Auditoria;
use App\Models\Reserva;
use App\Models\Tarifa;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\Artisan;
use Tests\TestCase;

class ConfiguracionYReportesTest extends TestCase
{
    use EscenarioCancha, RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        $this->prepararEscenario();
    }

    public function test_horarios_se_reemplazan_y_no_se_superponen(): void
    {
        $this->como($this->admin);
        $this->putJson("/api/cv/canchas/{$this->cancha->id}/horarios", ['horarios' => [
            ['dia_semana' => 1, 'abre' => '08:00', 'cierra' => '12:00'],
            ['dia_semana' => 1, 'abre' => '11:00', 'cierra' => '15:00'],
        ]])->assertStatus(422);
        $this->putJson("/api/cv/canchas/{$this->cancha->id}/horarios", ['horarios' => [
            ['dia_semana' => 1, 'abre' => '08:00', 'cierra' => '12:00'],
            ['dia_semana' => 1, 'abre' => '14:00', 'cierra' => '22:00'],
            ['dia_semana' => 6, 'abre' => '18:00', 'cierra' => '01:00'],
        ]])->assertOk()->assertJsonCount(3);
        $this->assertSame(1, Auditoria::where('campo_modificado', 'horarios')->count());
    }

    public function test_nuevas_tarifas_no_cambian_el_precio_de_reservas_hechas(): void
    {
        $antes = $this->crearReserva('2026-12-10 10:00', '2026-12-10 11:00');       // 30.00
        $this->como($this->admin);
        $this->putJson("/api/cv/canchas/{$this->cancha->id}/tarifas", ['vigente_desde' => '2026-12-05', 'tarifas' => [
            ['dia_semana' => 4, 'desde' => '08:00', 'hasta' => '00:00', 'precio_hora' => 50],
        ]])->assertOk()->assertJsonCount(1);
        $this->assertSame('30.00', $antes->fresh()->precio_final);
        $this->assertSame(1, Tarifa::whereNull('vigente_hasta')->count());
        $this->assertSame(14, Tarifa::where('vigente_hasta', '2026-12-04')->count());

        $nueva = $this->crearReserva('2026-12-10 12:00', '2026-12-10 13:00');       // jueves con la tarifa nueva
        $this->assertSame('50.00', $nueva->precio_final);
        $this->como($this->admin);
        $this->putJson("/api/cv/canchas/{$this->cancha->id}/tarifas", ['tarifas' => [['dia_semana' => 1, 'desde' => '10:00', 'hasta' => '09:00', 'precio_hora' => 5]]])
             ->assertStatus(422);
    }

    public function test_reglas_quedan_sin_configurar_hasta_que_la_administradora_las_guarda(): void
    {
        $this->como($this->admin);
        $vacias = $this->getJson('/api/cv/catalogo')->assertOk()->json('reglas');
        $this->assertNull($vacias['duracion_minima_min']);
        $this->assertNull($vacias['adelanto_tipo']);

        $this->putJson('/api/cv/reglas', ['adelanto_tipo' => 'porcentaje', 'adelanto_valor' => 150])->assertStatus(422);
        $this->putJson('/api/cv/reglas', ['duracion_minima_min' => 90, 'duracion_maxima_min' => 60])->assertStatus(422);
        $this->putJson('/api/cv/reglas', ['duracion_minima_min' => 60, 'margen_entre_reservas_min' => 10, 'regla_cruce_franjas' => 'prorrateo'])
             ->assertOk()->assertJsonPath('duracion_minima_min', 60)->assertJsonPath('adelanto_tipo', null);
        $this->assertSame(1, Auditoria::where('campo_modificado', 'reglas')->count());
    }

    public function test_bloqueo_lista_las_reservas_afectadas_sin_cancelarlas(): void
    {
        $r = $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $this->como($this->admin);
        $b = $this->postJson('/api/cv/bloqueos', ['inicio' => $this->t('2026-12-02 09:00'), 'fin' => $this->t('2026-12-02 12:00'), 'motivo' => 'Corte de luz (prueba)'])
            ->assertCreated();
        $this->assertSame([$r->uuid], collect($b->json('reservas_afectadas'))->pluck('uuid')->all());
        $this->assertSame('confirmada', $r->fresh()->estado);
        $this->deleteJson('/api/cv/bloqueos/' . $b->json('bloqueo.id'))->assertOk()->assertJsonPath('eliminado', true);
    }

    public function test_segunda_cancha_con_configuracion_copiada_recibe_reservas_en_paralelo(): void
    {
        $this->como($this->admin);
        $id = $this->postJson('/api/cv/canchas', ['nombre' => 'Cancha 2 (prueba)'])->assertCreated()->json('id');
        $this->postJson("/api/cv/canchas/{$id}/copiar-configuracion", ['desde_cancha_id' => $this->cancha->id])->assertOk();
        $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00', ['cancha_id' => $id]);        // misma hora, otra cancha
        $this->assertSame(2, Reserva::where('estado', 'confirmada')->count());
    }

    public function test_disponibilidad_y_recomendaciones(): void
    {
        $this->crearReserva('2026-12-02 10:00', '2026-12-02 12:00');
        $d = $this->getJson("/api/cv/disponibilidad?cancha_id={$this->cancha->id}&fecha=2026-12-02")->assertOk();
        $this->assertSame([
            ['inicio' => '2026-12-02T08:00:00-05:00', 'fin' => '2026-12-02T10:00:00-05:00'],
            ['inicio' => '2026-12-02T12:00:00-05:00', 'fin' => '2026-12-02T22:00:00-05:00'],
        ], $d->json('libres'));

        $s = $this->getJson("/api/cv/recomendaciones?cancha_id={$this->cancha->id}&fecha=2026-12-02&duracion_min=120&hora=11:00")->assertOk();
        $inicios = collect($s->json('sugerencias'))->pluck('inicio');
        $this->assertSame('2026-12-02T12:00:00-05:00', $inicios->first());       // la más cercana a las 11:00 que cabe
        $this->assertContains('2026-12-02T08:00:00-05:00', $inicios->all());
        $this->assertNotContains('2026-12-02T10:00:00-05:00', $inicios->all());  // ocupado
    }

    public function test_reporte_de_ocupacion_e_ingresos(): void
    {
        $a = $this->crearReserva('2026-12-02 18:00', '2026-12-02 20:00');                // 80.00
        $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');                     // 30.00
        $this->postJson('/api/cv/pagos', ['reserva_uuid' => $a->uuid, 'tipo' => 'pago', 'metodo' => 'yape', 'monto' => 80, 'numero_operacion' => 'R1']);
        $this->como($this->usuarioCliente);
        $p = $this->postJson('/api/cv/reservas', ['cancha_id' => $this->cancha->id, 'inicio' => $this->t('2026-12-02 14:00'), 'fin' => $this->t('2026-12-02 15:00')])->json('uuid');
        $this->como($this->admin);
        $this->postJson("/api/cv/reservas/{$p}/rechazar", ['motivo' => 'Prueba']);

        // Los ingresos se cuentan por fecha de pago (hoy, 1/12) y la ocupación por fecha de la reserva (2/12).
        $r = $this->getJson('/api/cv/reportes/ocupacion?desde=2026-12-01&hasta=2026-12-02')->assertOk();
        $r->assertJsonPath('horas_abiertas', 28)->assertJsonPath('horas_reservadas', 3)
          ->assertJsonPath('ocupacion_pct', 10.7)->assertJsonPath('ingresos.yape', '80.00')
          ->assertJsonPath('ingresos_total', '80.00')->assertJsonPath('demanda_no_atendida', 1)
          ->assertJsonPath('reservas_por_origen.llamada', 2)->assertJsonPath('reservas_por_origen.app', 1);
        $this->assertEqualsCanonicalizing([10, 18, 19], $r->json('horas_pico'));
    }

    public function test_instalador_crea_negocio_cancha_y_administradora_sin_duplicar(): void
    {
        \App\Models\Reserva::query()->delete();
        $this->artisan('canchavoley:instalar', ['--admin-email' => 'duena@prueba.test', '--admin-nombre' => 'Dueña Prueba', '--admin-password' => 'clave-segura-3'])
             ->assertSuccessful();
        $this->assertDatabaseHas('users', ['email' => 'duena@prueba.test', 'rol' => 'admin']);
        $this->artisan('canchavoley:instalar', ['--admin-email' => 'duena@prueba.test'])->assertSuccessful();
        $this->assertSame(1, \App\Models\Negocio::count());
    }

    public function test_las_bases_de_prolech_estan_protegidas(): void
    {
        $this->assertTrue(\Illuminate\Support\Facades\Event::hasListeners(\Illuminate\Console\Events\CommandStarting::class));
        \App\Providers\AppServiceProvider::verificarBaseProtegida('migrate');          // base de pruebas: permitido
        config(['database.connections.' . config('database.default') . '.database' => 'prolech_db']);
        \App\Providers\AppServiceProvider::verificarBaseProtegida('route:list');       // no escribe: permitido
        $this->expectException(\RuntimeException::class);
        $this->expectExceptionMessage('protegida');
        \App\Providers\AppServiceProvider::verificarBaseProtegida('migrate:fresh');
    }
}
