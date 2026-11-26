<?php

namespace Tests\Feature\CanchaVoley;

use App\Models\Bloqueo;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Str;
use Tests\TestCase;

/**
 * Pruebas agregadas el 20/11/2026 (rama mejoras-26-11):
 * 1) Matriz completa de permisos por rol en el servidor: administradora, recepción (el «empleado») y cliente.
 * 2) Configuración del negocio informada por el estudiante: UNA sola cancha con tarifa de S/ 20 por hora
 *    (1 h = S/ 20, 2 h = S/ 40, 3 h = S/ 60). El horario 08:00–22:00 es el del escenario de PRUEBA, no el real.
 */
class RolesYNegocioRealTest extends TestCase
{
    use EscenarioCancha, RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        $this->prepararEscenario();
    }

    /** [método, ruta, datos] que solo puede usar la administradora. */
    private function rutasSoloAdministradora(): array
    {
        $c = $this->cancha->id;
        $u = (string) Str::uuid();
        $bloqueo = Bloqueo::create(['negocio_id' => $this->negocio->id, 'cancha_id' => null, 'inicio' => '2026-12-09 12:00', 'fin' => '2026-12-09 14:00', 'motivo' => 'Cierre de prueba']);

        return [
            ['post', "/api/cv/reservas/{$u}/aprobar", []],
            ['post', "/api/cv/reservas/{$u}/rechazar", ['motivo' => 'prueba']],
            ['post', "/api/cv/pagos/{$u}/anular", ['motivo' => 'prueba']],
            ['put', '/api/cv/negocio', ['nombre' => 'Otro nombre']],
            ['post', '/api/cv/canchas', ['nombre' => 'Cancha nueva']],
            ['put', "/api/cv/canchas/{$c}", ['nombre' => 'Cambio']],
            ['put', "/api/cv/canchas/{$c}/horarios", ['horarios' => []]],
            ['put', "/api/cv/canchas/{$c}/tarifas", ['tarifas' => []]],
            ['post', "/api/cv/canchas/{$c}/copiar-configuracion", []],
            ['put', '/api/cv/reglas', ['margen_entre_reservas_min' => 10]],
            ['post', '/api/cv/bloqueos', ['inicio' => '2026-12-10 08:00', 'fin' => '2026-12-10 10:00', 'motivo' => 'x']],
            ['delete', "/api/cv/bloqueos/{$bloqueo->id}", []],
            ['get', '/api/cv/reportes/ocupacion?desde=2026-12-01&hasta=2026-12-07', []],
            ['get', '/api/cv/usuarios', []],
            ['post', '/api/cv/usuarios', ['nombre' => 'X', 'email' => 'x@prueba.test', 'password' => 'clave-segura-9', 'rol' => 'admin']],
            ['put', "/api/cv/usuarios/{$this->recepcion->id}", ['rol' => 'admin']],
        ];
    }

    /** [método, ruta, datos] del personal (administradora y recepción); el cliente no las usa. */
    private function rutasDelPersonal(): array
    {
        $u = (string) Str::uuid();

        return [
            ['get', '/api/cv/clientes', []],
            ['post', '/api/cv/clientes', ['nombre' => 'Cliente X', 'telefono' => '987000111']],
            ['get', '/api/cv/clientes/telefono/51900000002', []],
            ['get', "/api/cv/clientes/{$this->clienteTel->id}", []],
            ['put', "/api/cv/clientes/{$this->clienteTel->id}", ['nombre' => 'Cambio']],
            ['post', "/api/cv/reservas/{$u}/asistencia", ['asistencia' => 'asistio']],
            ['post', "/api/cv/reservas/{$u}/editar", ['notas' => 'x']],
            ['get', '/api/cv/qr/ABCDEF123456', []],
            ['post', '/api/cv/pagos', ['uuid' => (string) Str::uuid()]],
            ['get', '/api/cv/caja', []],
        ];
    }

    private function pedir(string $metodo, string $ruta, array $datos)
    {
        return $this->json(strtoupper($metodo), $ruta, $datos);
    }

    public function test_recepcion_y_cliente_no_usan_ninguna_ruta_de_la_administradora(): void
    {
        $rutas = $this->rutasSoloAdministradora();
        foreach ([$this->recepcion, $this->usuarioCliente] as $usuario) {
            foreach ($rutas as [$metodo, $ruta, $datos]) {
                $this->como($usuario);
                $this->pedir($metodo, $ruta, $datos)->assertStatus(403);
            }
        }
        // Nada cambió: la recepción sigue siendo recepción y la configuración no se tocó.
        $this->assertSame('recepcion', $this->recepcion->fresh()->rol);
        $this->assertSame('Cancha de prueba', $this->cancha->fresh()->nombre);
    }

    public function test_el_cliente_no_usa_ninguna_ruta_del_personal(): void
    {
        foreach ($this->rutasDelPersonal() as [$metodo, $ruta, $datos]) {
            $this->como($this->usuarioCliente);
            $this->pedir($metodo, $ruta, $datos)->assertStatus(403);
        }
    }

    public function test_cada_rol_si_usa_lo_que_le_corresponde(): void
    {
        $this->como($this->admin);
        $this->getJson('/api/cv/usuarios')->assertOk();
        $this->getJson('/api/cv/reportes/ocupacion?desde=2026-12-01&hasta=2026-12-07')->assertOk();
        $this->getJson('/api/cv/caja')->assertOk();

        $this->como($this->recepcion);
        $this->getJson('/api/cv/clientes')->assertOk();
        $this->getJson('/api/cv/caja')->assertOk();
        $this->getJson('/api/cv/disponibilidad?cancha_id=' . $this->cancha->id . '&fecha=2026-12-02')->assertOk();
        $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-02 10:00', '2026-12-02 11:00'))->assertCreated();

        $this->como($this->usuarioCliente);
        $this->getJson('/api/cv/catalogo')->assertOk();
        $this->getJson('/api/cv/reservas')->assertOk();
    }

    public function test_la_administradora_desactiva_la_cuenta_de_un_empleado(): void
    {
        $this->como($this->admin);
        $this->putJson("/api/cv/usuarios/{$this->recepcion->id}", ['activo' => false])->assertOk()->assertJsonPath('activo', false);
        $this->assertFalse((bool) $this->recepcion->fresh()->activo);
    }

    public function test_negocio_de_una_sola_cancha_a_20_soles_la_hora(): void
    {
        // La administradora deja una sola franja de S/ 20 por hora para todos los días (08:00–22:00 es horario de PRUEBA).
        $this->como($this->admin);
        $this->putJson("/api/cv/canchas/{$this->cancha->id}/tarifas", ['tarifas' => collect(range(1, 7))->map(fn ($d) => [
            'dia_semana' => $d, 'desde' => '08:00', 'hasta' => '22:00', 'precio_hora' => 20,
        ])->all()])->assertSuccessful();
        $this->assertCount(1, $this->negocio->canchas()->get());

        $casos = [
            ['2026-12-02 08:00', '2026-12-02 09:00', 60, '20.00'],    // 1 hora
            ['2026-12-02 10:00', '2026-12-02 12:00', 120, '40.00'],   // 2 horas
            ['2026-12-02 13:00', '2026-12-02 16:00', 180, '60.00'],   // 3 horas
            ['2026-12-02 19:00', '2026-12-02 21:00', 120, '40.00'],   // noche: la misma tarifa
            ['2026-12-03 10:00', '2026-12-03 11:30', 90, '30.00'],    // 1 h 30 min
        ];
        foreach ($casos as [$inicio, $fin, $minutos, $precio]) {
            $this->como($this->recepcion);
            $this->postJson('/api/cv/reservas', $this->datosReserva($inicio, $fin))->assertCreated()
                ->assertJsonPath('duracion_min', $minutos)
                ->assertJsonPath('precio_final', $precio)
                ->assertJsonPath('precio_manual', false);
        }
        // Con una sola cancha, el mismo horario no se puede reservar dos veces.
        $this->postJson('/api/cv/reservas', $this->datosReserva('2026-12-02 11:00', '2026-12-02 12:00'))->assertStatus(409);
    }
}
