<?php

namespace Tests\Feature\CanchaVoley;

use App\Models\Cliente;
use App\Models\OperacionSync;
use App\Models\Pago;
use App\Models\Reserva;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Str;
use Tests\TestCase;

/** Operaciones hechas sin conexión en escritorio/Android y enviadas al volver internet. */
class SincronizacionTest extends TestCase
{
    use EscenarioCancha, RefreshDatabase;

    private string $dispositivo;

    protected function setUp(): void
    {
        parent::setUp();
        $this->prepararEscenario();
        $this->dispositivo = (string) Str::uuid();
    }

    private function op(string $tipo, array $datos, ?string $creadaEn = null): array
    {
        return ['op_uuid' => (string) Str::uuid(), 'tipo' => $tipo, 'entidad_uuid' => $datos['uuid'] ?? null,
            'datos' => $datos, 'creada_en' => $creadaEn ?? $this->t('2026-12-01 09:55')];
    }

    private function enviar(array $ops)
    {
        return $this->postJson('/api/cv/sync/operaciones', ['dispositivo_uuid' => $this->dispositivo, 'operaciones' => $ops])->assertOk();
    }

    public function test_cliente_reserva_y_pago_creados_sin_conexion_se_aplican_en_orden(): void
    {
        $this->como($this->recepcion);
        $cli = (string) Str::uuid(); $res = (string) Str::uuid(); $pag = (string) Str::uuid();
        $ops = [
            $this->op('crear_cliente', ['uuid' => $cli, 'nombre' => 'Nuevo Offline', 'telefono' => '987 654 321']),
            $this->op('crear_reserva', ['uuid' => $res, 'cancha_id' => $this->cancha->id, 'cliente_uuid' => $cli,
                'inicio' => $this->t('2026-12-02 10:00'), 'fin' => $this->t('2026-12-02 11:00'), 'origen' => 'llamada']),
            $this->op('registrar_pago', ['uuid' => $pag, 'reserva_uuid' => $res, 'tipo' => 'adelanto', 'metodo' => 'efectivo', 'monto' => 10]),
        ];
        $r = $this->enviar($ops);
        $this->assertSame(['aplicada', 'aplicada', 'aplicada'], collect($r->json('resultados'))->pluck('resultado')->all());
        $this->assertSame('51987654321', Cliente::where('uuid', $cli)->value('telefono'));
        $reserva = Reserva::where('uuid', $res)->first();
        $this->assertSame('confirmada', $reserva->estado);
        $this->assertTrue($reserva->creada_offline);
        $this->assertSame($this->dispositivo, $reserva->dispositivo_uuid);

        // Reenvío del mismo lote (se perdió la respuesta): nada se duplica.
        $otra = $this->enviar($ops);
        $this->assertTrue(collect($otra->json('resultados'))->every(fn ($x) => ($x['repetida'] ?? false) === true));
        $this->assertSame(1, Reserva::count());
        $this->assertSame(1, Pago::count());
        $this->assertSame(3, OperacionSync::count());
    }

    public function test_reserva_offline_que_choca_queda_rechazada_y_su_pago_como_saldo_a_favor(): void
    {
        $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');      // otro equipo la tomó primero (en línea)
        $res = (string) Str::uuid();
        $r = $this->enviar([
            $this->op('crear_reserva', ['uuid' => $res, 'cancha_id' => $this->cancha->id, 'cliente_id' => $this->clienteApp->id,
                'inicio' => $this->t('2026-12-02 10:30'), 'fin' => $this->t('2026-12-02 11:30')]),
            $this->op('registrar_pago', ['uuid' => (string) Str::uuid(), 'reserva_uuid' => $res, 'tipo' => 'adelanto', 'metodo' => 'efectivo', 'monto' => 15]),
        ]);
        [$opReserva, $opPago] = $r->json('resultados');
        $this->assertSame('rechazada', $opReserva['resultado']);
        $this->assertSame(409, $opReserva['codigo']);
        $this->assertNotEmpty($opReserva['sugerencias']);
        $this->assertSame('rechazada', $opReserva['entidad']['estado']);     // nunca se confirma sola
        $this->assertSame('aplicada', $opPago['resultado']);
        $this->assertStringContainsString('saldo a favor', $opPago['advertencia']);
        $this->assertSame('saldo_a_favor', $opPago['reserva']['estado_pago']);
        $this->assertSame(1, Reserva::where('estado', 'confirmada')->count());
    }

    public function test_cliente_creado_offline_con_telefono_existente_se_une_y_sus_reservas_lo_encuentran(): void
    {
        $this->como($this->recepcion);
        $provisional = (string) Str::uuid();
        $r = $this->enviar([
            $this->op('crear_cliente', ['uuid' => $provisional, 'nombre' => 'Otro nombre', 'telefono' => '51900000002']),
            $this->op('crear_reserva', ['uuid' => (string) Str::uuid(), 'cancha_id' => $this->cancha->id, 'cliente_uuid' => $provisional,
                'inicio' => $this->t('2026-12-02 10:00'), 'fin' => $this->t('2026-12-02 11:00')]),
        ]);
        [$opCliente, $opReserva] = $r->json('resultados');
        $this->assertTrue($opCliente['unido_a_existente']);
        $this->assertSame($this->clienteTel->uuid, $opCliente['entidad']['uuid']);
        $this->assertSame('aplicada', $opReserva['resultado']);
        $this->assertSame($this->clienteTel->id, $opReserva['entidad']['cliente_id']);
        $this->assertSame(2, Cliente::count());
    }

    public function test_solicitud_offline_de_un_cliente_queda_pendiente_de_aprobacion(): void
    {
        $this->como($this->usuarioCliente);
        $r = $this->enviar([$this->op('crear_reserva', ['uuid' => (string) Str::uuid(), 'cancha_id' => $this->cancha->id,
            'inicio' => $this->t('2026-12-05 10:00'), 'fin' => $this->t('2026-12-05 11:00')])]);
        $this->assertSame('pendiente', $r->json('resultados.0.entidad.estado'));
        $this->assertSame('aprobacion', $r->json('resultados.0.entidad.pendiente_por'));
    }

    public function test_cambio_de_precio_y_notas_hecho_sin_conexion(): void
    {
        $r = $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $this->como($this->admin);
        $res = $this->enviar([$this->op('editar_reserva', ['reserva_uuid' => $r->uuid, 'precio_final' => '28.50', 'notas' => 'Pagan al llegar'])]);
        $this->assertSame('aplicada', $res->json('resultados.0.resultado'));
        $this->assertSame('28.50', $res->json('resultados.0.entidad.precio_final'));
        $this->assertSame('Pagan al llegar', $r->fresh()->notas);
    }

    public function test_un_cliente_no_puede_usar_operaciones_del_personal(): void
    {
        $this->como($this->usuarioCliente);
        $r = $this->enviar([$this->op('registrar_pago', ['uuid' => (string) Str::uuid(), 'reserva_uuid' => (string) Str::uuid(), 'tipo' => 'pago', 'metodo' => 'efectivo', 'monto' => 5])]);
        $this->assertSame('rechazada', $r->json('resultados.0.resultado'));
        $this->assertSame(403, $r->json('resultados.0.codigo'));
    }

    public function test_operacion_mal_formada_se_rechaza_sin_detener_el_lote(): void
    {
        $this->como($this->recepcion);
        $r = $this->enviar([
            ['op_uuid' => 'no-es-uuid', 'tipo' => 'crear_cliente', 'datos' => []],
            ['op_uuid' => (string) Str::uuid(), 'tipo' => 'borrar_todo', 'datos' => []],
            $this->op('crear_cliente', ['uuid' => (string) Str::uuid(), 'nombre' => 'Válido', 'telefono' => '911222333']),
        ]);
        $this->assertSame(['rechazada', 'rechazada', 'aplicada'], collect($r->json('resultados'))->pluck('resultado')->all());
    }

    public function test_offline_usa_la_hora_del_equipo_para_validar_que_no_haya_terminado(): void
    {
        // Registrada a las 09:30 (sin conexión) para 09:30–10:20; llega al servidor 10:30.
        $this->travelTo(\Carbon\CarbonImmutable::parse('2026-12-01 10:30', 'America/Lima'));
        $this->como($this->recepcion);
        $r = $this->enviar([$this->op('crear_reserva', ['uuid' => (string) Str::uuid(), 'cancha_id' => $this->cancha->id, 'cliente_id' => $this->clienteTel->id,
            'inicio' => $this->t('2026-12-01 09:30'), 'fin' => $this->t('2026-12-01 10:20')], $this->t('2026-12-01 09:30'))]);
        $this->assertSame('aplicada', $r->json('resultados.0.resultado'));
    }

    public function test_cambios_desde_el_cursor(): void
    {
        $a = $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $this->travel(5)->minutes();       // el cursor se devuelve con 2 min de solape a propósito
        $todo = $this->getJson('/api/cv/sync/cambios')->assertOk();
        $todo->assertJsonPath('carga_completa', true)->assertJsonPath('catalogo.negocio.nombre', 'Negocio de prueba');
        $this->assertCount(7, $todo->json('catalogo.horarios'));
        $this->assertCount(14, $todo->json('catalogo.tarifas'));
        $this->assertSame([$a->uuid], collect($todo->json('reservas'))->pluck('uuid')->all());
        $cursor = $todo->json('cursor');

        $this->travel(10)->minutes();
        $b = $this->crearReserva('2026-12-02 12:00', '2026-12-02 13:00');
        $nuevos = $this->getJson('/api/cv/sync/cambios?desde=' . urlencode($cursor))->assertOk();
        $this->assertSame([$b->uuid], collect($nuevos->json('reservas'))->pluck('uuid')->all());

        // Un cliente solo recibe lo suyo.
        $this->como($this->usuarioCliente);
        $suyo = $this->getJson('/api/cv/sync/cambios')->assertOk();
        $this->assertSame([], $suyo->json('reservas'));
        $this->assertSame([$this->clienteApp->uuid], collect($suyo->json('clientes'))->pluck('uuid')->all());
    }
}
