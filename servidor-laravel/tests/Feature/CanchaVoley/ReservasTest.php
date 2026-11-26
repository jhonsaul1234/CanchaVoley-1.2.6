<?php

namespace Tests\Feature\CanchaVoley;

use App\Models\Bloqueo;
use App\Models\Notificacion;
use App\Models\Reserva;
use Carbon\CarbonImmutable;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Tests\TestCase;

class ReservasTest extends TestCase
{
    use EscenarioCancha, RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        $this->prepararEscenario();
    }

    public function test_recepcion_registra_reserva_confirmada_con_precio_de_la_tarifa(): void
    {
        $this->como($this->recepcion);
        $r = $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-02 10:00', '2026-12-02 11:30'))->assertCreated();

        $r->assertJsonPath('estado', 'confirmada')
          ->assertJsonPath('duracion_min', 90)
          ->assertJsonPath('precio_final', '45.00')
          ->assertJsonPath('precio_manual', false)
          ->assertJsonPath('origen', 'llamada')
          ->assertJsonPath('estado_pago', 'sin_pago');
        $this->assertNotNull($r->json('codigo_qr'));
        $this->assertSame('2026-12-02T10:00:00-05:00', $r->json('inicio'));
    }

    public function test_duraciones_distintas_conviven_si_no_se_superponen(): void
    {
        $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:30');   // 90 min
        $this->crearReserva('2026-12-02 11:30', '2026-12-02 13:30');   // 120 min, contigua
        $this->crearReserva('2026-12-02 14:00', '2026-12-02 15:00');   // 60 min
        $this->assertSame(3, Reserva::where('estado', 'confirmada')->count());
    }

    public function test_choque_devuelve_409_con_horarios_alternativos_libres(): void
    {
        $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $r = $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-02 10:30', '2026-12-02 11:30'))->assertStatus(409);

        $r->assertJsonPath('conflicto.tipo', 'reserva');
        $sugerencias = $r->json('sugerencias');
        $this->assertNotEmpty($sugerencias);
        foreach ($sugerencias as $s) {       // ninguna sugerencia choca con la reserva existente
            $this->assertTrue($s['fin'] <= '2026-12-02T10:00:00-05:00' || $s['inicio'] >= '2026-12-02T11:00:00-05:00', json_encode($s));
        }
        $this->assertSame(1, Reserva::count());
    }

    public function test_margen_entre_reservas_configurable(): void
    {
        $this->reglas(['margen_entre_reservas_min' => 15]);
        $this->crearReserva('2026-12-02 14:00', '2026-12-02 15:00');
        $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-02 15:00', '2026-12-02 16:00'))->assertStatus(409);
        $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-02 15:15', '2026-12-02 16:15'))->assertCreated();
    }

    public function test_fuera_de_horario_y_sin_horario_configurado(): void
    {
        $this->como($this->recepcion);
        $r = $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-02 21:00', '2026-12-02 23:00'))->assertStatus(422);
        $this->assertNotEmpty($r->json('sugerencias'));

        $this->cancha->horarios()->delete();
        $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-02 10:00', '2026-12-02 11:00'))
             ->assertStatus(422)->assertJsonPath('falta', 'horario');
    }

    public function test_turno_que_pasa_la_medianoche(): void
    {
        $this->cancha->horarios()->delete();
        $this->cancha->horarios()->create(['dia_semana' => 3, 'abre' => '18:00', 'cierra' => '02:00']);   // miércoles
        $this->como($this->recepcion);
        $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-02 23:00', '2026-12-03 01:00', ['precio_final' => 80]))->assertCreated();
        $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-03 01:00', '2026-12-03 03:00', ['precio_final' => 80]))->assertStatus(422);
    }

    public function test_bloqueo_cierra_la_cancha(): void
    {
        Bloqueo::create(['negocio_id' => $this->negocio->id, 'cancha_id' => null, 'inicio' => '2026-12-02 12:00', 'fin' => '2026-12-02 14:00', 'motivo' => 'Mantenimiento de prueba']);
        $this->como($this->recepcion);
        $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-02 13:00', '2026-12-02 14:00'))
             ->assertStatus(409)->assertJsonPath('conflicto.tipo', 'bloqueo');
    }

    public function test_mismo_uuid_no_duplica_la_reserva(): void
    {
        $this->como($this->recepcion);
        $datos = $this->datosReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $a = $this->postJson('/api/cv/reservas', $datos)->assertCreated();
        $b = $this->postJson('/api/cv/reservas', $datos)->assertOk();
        $this->assertSame($a->json('id'), $b->json('id'));
        $this->assertSame(1, Reserva::count());
    }

    public function test_reenvio_simultaneo_del_mismo_uuid_recibe_la_misma_reserva_y_no_ocupado(): void
    {
        // Dos envíos a la vez de la misma reserva (p. ej. el POST original y su reintento desde la
        // cola offline): el otro envío queda guardado justo después de la comprobación inicial.
        $this->como($this->recepcion);
        $datos = $this->datosReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $otroEnvioGuardado = false;
        DB::listen(function ($consulta) use (&$otroEnvioGuardado, $datos) {
            if ($otroEnvioGuardado || ! str_contains($consulta->sql, 'from "canchas"')) {
                return;
            }
            $otroEnvioGuardado = true;
            Reserva::create([
                'uuid' => $datos['uuid'], 'negocio_id' => $this->cancha->negocio_id, 'cancha_id' => $this->cancha->id,
                'cliente_id' => $this->clienteTel->id, 'inicio' => CarbonImmutable::parse($datos['inicio']),
                'fin' => CarbonImmutable::parse($datos['fin']), 'duracion_min' => 60, 'estado' => 'confirmada',
                'origen' => 'llamada', 'precio_final' => '30.00', 'creado_por' => $this->recepcion->id,
            ]);
        });

        $r = $this->postJson('/api/cv/reservas', $datos)->assertOk();
        $this->assertTrue($otroEnvioGuardado);
        $this->assertSame($datos['uuid'], $r->json('uuid'));
        $this->assertSame(1, Reserva::count());
    }

    public function test_reglas_de_duracion_configurables(): void
    {
        $this->reglas(['duracion_minima_min' => 60, 'duracion_maxima_min' => 180, 'paso_min' => 30, 'alinear_inicio_min' => 30]);
        $this->como($this->recepcion);
        $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-02 10:00', '2026-12-02 10:45'))->assertStatus(422);
        $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-02 10:15', '2026-12-02 11:15'))->assertStatus(422);
        $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-02 10:00', '2026-12-02 13:30'))->assertStatus(422);
        $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-02 10:30', '2026-12-02 12:00'))->assertCreated();
    }

    public function test_precio_cuando_cruza_franjas_segun_la_regla(): void
    {
        $this->como($this->recepcion);
        // Sin regla de cobro configurada: no se inventa el precio, se pide el acordado.
        $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-02 17:00', '2026-12-02 19:00'))
             ->assertStatus(422)->assertJsonPath('falta', 'precio_final');
        $manual = $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-02 17:00', '2026-12-02 19:00', ['precio_final' => 65]))->assertCreated();
        $manual->assertJsonPath('precio_final', '65.00')->assertJsonPath('precio_manual', true);

        $this->reglas(['regla_cruce_franjas' => 'prorrateo']);
        $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-03 17:00', '2026-12-03 19:00'))
             ->assertCreated()->assertJsonPath('precio_final', '70.00');      // 1 h × 30 + 1 h × 40

        $this->reglas(['regla_cruce_franjas' => 'franja_inicio']);
        $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-04 17:00', '2026-12-04 19:00'))
             ->assertCreated()->assertJsonPath('precio_final', '60.00');      // 2 h × 30
    }

    public function test_solicitud_del_cliente_queda_pendiente_hasta_que_la_administradora_aprueba(): void
    {
        $this->como($this->usuarioCliente);
        $r = $this->postJson('/api/cv/reservas', [
            'uuid' => (string) \Illuminate\Support\Str::uuid(), 'cancha_id' => $this->cancha->id,
            'inicio' => $this->t('2026-12-05 10:00'), 'fin' => $this->t('2026-12-05 12:00'),
        ])->assertCreated();
        $r->assertJsonPath('estado', 'pendiente')->assertJsonPath('pendiente_por', 'aprobacion')
          ->assertJsonPath('origen', 'app')->assertJsonPath('codigo_qr', null)
          ->assertJsonPath('cliente_id', $this->clienteApp->id);
        $uuid = $r->json('uuid');
        $this->assertSame(1, Notificacion::where('user_id', $this->admin->id)->where('tipo', 'reserva_solicitada')->count());

        // La solicitud pendiente ocupa el horario: recepción no puede darlo a otro.
        $this->como($this->recepcion);
        $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-05 11:00', '2026-12-05 12:00'))->assertStatus(409);
        // Recepción no aprueba: solo la administradora.
        $this->postJson("/api/cv/reservas/{$uuid}/aprobar")->assertForbidden();

        $this->como($this->admin);
        $ok = $this->postJson("/api/cv/reservas/{$uuid}/aprobar")->assertOk();
        $ok->assertJsonPath('estado', 'confirmada')->assertJsonPath('precio_final', '60.00');
        $this->assertNotNull($ok->json('codigo_qr'));
        $this->assertSame(1, Notificacion::where('user_id', $this->usuarioCliente->id)->where('tipo', 'reserva_aprobada')->count());
    }

    public function test_administradora_rechaza_y_el_horario_queda_libre(): void
    {
        $this->como($this->usuarioCliente);
        $uuid = $this->postJson('/api/cv/reservas', ['cancha_id' => $this->cancha->id,
            'inicio' => $this->t('2026-12-05 10:00'), 'fin' => $this->t('2026-12-05 11:00')])->assertCreated()->json('uuid');

        $this->como($this->admin);
        $this->postJson("/api/cv/reservas/{$uuid}/rechazar", ['motivo' => 'Horario reservado para un evento'])
             ->assertOk()->assertJsonPath('estado', 'rechazada');
        $this->crearReserva('2026-12-05 10:00', '2026-12-05 11:00');
    }

    public function test_cliente_solo_ve_sus_reservas(): void
    {
        $ajena = $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $this->como($this->usuarioCliente);
        $this->getJson('/api/cv/reservas')->assertOk()->assertJsonCount(0);
        $this->getJson("/api/cv/reservas/{$ajena->uuid}")->assertForbidden();
        $this->getJson('/api/cv/clientes')->assertForbidden();
    }

    public function test_cancelacion_por_el_cliente_respeta_la_regla_configurada(): void
    {
        $this->como($this->usuarioCliente);
        $pend = $this->postJson('/api/cv/reservas', ['cancha_id' => $this->cancha->id,
            'inicio' => $this->t('2026-12-05 10:00'), 'fin' => $this->t('2026-12-05 11:00')])->json('uuid');
        $this->postJson("/api/cv/reservas/{$pend}/cancelar", ['motivo' => 'Ya no puedo ir'])->assertOk()->assertJsonPath('estado', 'cancelada');

        $conf = $this->crearReserva('2026-12-03 10:00', '2026-12-03 11:00', ['cliente_id' => $this->clienteApp->id]);
        $this->como($this->usuarioCliente);
        $this->postJson("/api/cv/reservas/{$conf->uuid}/cancelar", ['motivo' => 'Ya no puedo ir'])->assertForbidden();   // sin regla: llamar al negocio

        $this->reglas(['cancelacion_limite_horas' => 24]);
        $this->postJson("/api/cv/reservas/{$conf->uuid}/cancelar", ['motivo' => 'Ya no puedo ir'])->assertOk();         // faltan 48 h
    }

    public function test_reprogramar_valida_choques_y_recalcula_el_precio(): void
    {
        $a = $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $this->crearReserva('2026-12-02 12:00', '2026-12-02 13:00');

        $this->postJson("/api/cv/reservas/{$a->uuid}/reprogramar", ['inicio' => $this->t('2026-12-02 12:30'), 'fin' => $this->t('2026-12-02 13:30')])->assertStatus(409);
        $r = $this->postJson("/api/cv/reservas/{$a->uuid}/reprogramar", ['inicio' => $this->t('2026-12-02 19:00'), 'fin' => $this->t('2026-12-02 21:00')])->assertOk();
        $r->assertJsonPath('precio_final', '80.00')->assertJsonPath('duracion_min', 120);
        $this->assertContains('reprogramada', collect($r->json('eventos'))->pluck('tipo')->all());
    }

    public function test_codigo_qr_y_asistencia(): void
    {
        $r = $this->crearReserva('2026-12-01 10:30', '2026-12-01 11:30');
        $this->getJson("/api/cv/qr/CANCHAVOLEY:{$r->codigo_qr}")->assertOk()->assertJsonPath('uuid', $r->uuid);
        $this->getJson('/api/cv/qr/NOEXISTE')->assertNotFound();
        $this->postJson("/api/cv/reservas/{$r->uuid}/asistencia", ['asistio' => true])->assertOk()->assertJsonPath('asistencia', 'asistio');
    }

    public function test_adelanto_obligatorio_deja_pendiente_hasta_pagarlo(): void
    {
        $this->reglas(['adelanto_tipo' => 'porcentaje', 'adelanto_valor' => 50, 'adelanto_obligatorio' => true, 'adelanto_plazo_min' => 30]);
        $r = $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:30');
        $this->assertSame('pendiente', $r->estado);
        $this->assertSame('adelanto', $r->pendiente_por);
        $this->assertSame('22.50', $r->adelanto_requerido);

        $this->postJson('/api/cv/pagos', ['reserva_uuid' => $r->uuid, 'tipo' => 'adelanto', 'metodo' => 'yape', 'monto' => 22.5, 'numero_operacion' => '000111'])
             ->assertCreated()->assertJsonPath('reserva.estado', 'confirmada')->assertJsonPath('reserva.saldo', '22.50');
    }

    public function test_pendiente_de_adelanto_vencida_libera_el_horario(): void
    {
        $this->reglas(['adelanto_tipo' => 'monto', 'adelanto_valor' => 10, 'adelanto_obligatorio' => true, 'adelanto_plazo_min' => 30]);
        $vieja = $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $this->travel(31)->minutes();
        $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00', ['cliente_id' => $this->clienteApp->id]);
        $this->assertSame('cancelada', $vieja->fresh()->estado);
    }

    public function test_no_se_registra_una_reserva_que_ya_termino(): void
    {
        $this->como($this->recepcion);
        $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-01 08:00', '2026-12-01 09:00'))->assertStatus(422);
        // Una reserva en curso (cliente que llegó sin reservar) sí se registra.
        $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-01 09:30', '2026-12-01 10:30'))->assertCreated();
    }

    public function test_detalle_incluye_historial(): void
    {
        $r = $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $d = $this->getJson("/api/cv/reservas/{$r->uuid}")->assertOk();
        $this->assertSame(['creada', 'confirmada'], collect($d->json('eventos'))->pluck('tipo')->all());
    }

    public function test_la_administradora_cambia_el_precio_y_queda_en_el_historial(): void
    {
        $r = $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');      // tarifa de prueba: 30.00
        $this->como($this->admin);
        $this->postJson("/api/cv/reservas/{$r->uuid}/editar", ['precio_final' => 25])
             ->assertOk()->assertJsonPath('precio_final', '25.00')->assertJsonPath('precio_manual', true);
        $eventos = collect($this->getJson("/api/cv/reservas/{$r->uuid}")->json('eventos'));
        $this->assertTrue($eventos->contains(fn ($e) => $e['tipo'] === 'precio_cambiado' && str_contains($e['descripcion'], '30.00') && str_contains($e['descripcion'], '25.00')));
    }

    public function test_recepcion_edita_notas_pero_no_el_precio(): void
    {
        $r = $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $this->como($this->recepcion);
        $this->postJson("/api/cv/reservas/{$r->uuid}/editar", ['notas' => 'Traen su propia pelota'])
             ->assertOk()->assertJsonPath('notas', 'Traen su propia pelota');
        $this->postJson("/api/cv/reservas/{$r->uuid}/editar", ['precio_final' => 1])->assertStatus(403);
        $this->assertSame('30.00', $r->fresh()->precio_final);
        $this->como($this->usuarioCliente);
        $this->postJson("/api/cv/reservas/{$r->uuid}/editar", ['notas' => 'x'])->assertStatus(403);
    }

    public function test_no_se_edita_una_reserva_cancelada(): void
    {
        $r = $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $this->postJson("/api/cv/reservas/{$r->uuid}/cancelar", ['motivo' => 'Llovió'])->assertOk();
        $this->como($this->admin);
        $this->postJson("/api/cv/reservas/{$r->uuid}/editar", ['precio_final' => 10])->assertStatus(409);
    }
}
