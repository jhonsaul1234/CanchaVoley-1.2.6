<?php

namespace Tests\Feature\CanchaVoley;

use App\Models\Cliente;
use App\Models\Notificacion;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;
use Tests\TestCase;

class SesionYPermisosTest extends TestCase
{
    use EscenarioCancha, RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        $this->prepararEscenario();
    }

    public function test_registro_de_cliente_desde_la_app(): void
    {
        $r = $this->postJson('/api/cv/registro', [
            'nombre' => 'Persona Prueba', 'email' => 'persona@prueba.test', 'password' => 'clave-segura-1',
            'telefono' => '+51 955 111 222', 'dispositivo' => 'android',
        ])->assertCreated();
        $r->assertJsonPath('usuario.rol', 'cliente')->assertJsonPath('es_personal', false)
          ->assertJsonPath('cliente.telefono', '51955111222')->assertJsonPath('cliente.tiene_cuenta', true);
        $this->assertNotEmpty($r->json('token'));
        $this->withToken($r->json('token'))->getJson('/api/cv/sesion')->assertOk()->assertJsonPath('usuario.email', 'persona@prueba.test');
    }

    public function test_la_sesion_incluye_la_marca_de_la_base_que_no_cambia_al_editar_el_negocio(): void
    {
        $this->actingAs($this->admin, 'sanctum');
        $marca = $this->getJson('/api/cv/sesion')->assertOk()->json('negocio.creado_en');
        $this->assertNotEmpty($marca);
        $this->travel(5)->minutes();
        $this->putJson('/api/cv/negocio', ['nombre' => 'Otro nombre'])->assertOk();
        $this->assertSame($marca, $this->getJson('/api/cv/sesion')->json('negocio.creado_en'));
    }

    public function test_registro_se_une_al_cliente_ya_registrado_por_telefono_y_avisa(): void
    {
        $this->postJson('/api/cv/registro', [
            'nombre' => 'Mismo Cliente', 'email' => 'mismo@prueba.test', 'password' => 'clave-segura-1', 'telefono' => '900000002',
        ])->assertCreated()->assertJsonPath('cliente.uuid', $this->clienteTel->uuid);
        $this->assertNotNull($this->clienteTel->fresh()->user_id);
        $this->assertSame(1, Notificacion::where('user_id', $this->admin->id)->where('tipo', 'cliente_vinculado')->count());

        // Ese teléfono ya tiene cuenta: no se puede registrar otra.
        $this->postJson('/api/cv/registro', [
            'nombre' => 'Otro', 'email' => 'otro@prueba.test', 'password' => 'clave-segura-1', 'telefono' => '900000002',
        ])->assertStatus(409);
    }

    public function test_login_permite_recepcion_y_cliente_y_no_cierra_la_sesion_del_otro_equipo(): void
    {
        config(['app.debug' => false]);
        $u = User::factory()->create(['rol' => 'recepcion', 'activo' => true]);
        $escritorio = $this->postJson('/api/login', ['email' => $u->email, 'password' => 'password', 'dispositivo' => 'escritorio'])->assertOk()->json('token');
        $android    = $this->postJson('/api/login', ['email' => $u->email, 'password' => 'password', 'dispositivo' => 'android'])->assertOk()->json('token');
        $this->assertSame(2, $u->tokens()->count());
        $this->assertNotSame($escritorio, $android);

        $c = User::factory()->create(['rol' => 'cliente', 'activo' => true]);
        $this->postJson('/api/login', ['email' => $c->email, 'password' => 'password'])->assertOk()->assertJsonPath('user.rol', 'cliente');

        $g = User::factory()->create(['rol' => 'ganadero', 'activo' => true]);
        $this->postJson('/api/login', ['email' => $g->email, 'password' => 'password'])->assertForbidden();
    }

    public function test_roles_de_prolech_no_entran_a_canchavoley(): void
    {
        $this->como(User::factory()->create(['rol' => 'chofer', 'activo' => true]));
        $this->getJson('/api/cv/sesion')->assertForbidden();
    }

    public function test_recepcion_no_configura_ni_ve_reportes_ni_usuarios(): void
    {
        $this->como($this->recepcion);
        $this->putJson('/api/cv/reglas', ['margen_entre_reservas_min' => 10])->assertForbidden();
        $this->putJson("/api/cv/canchas/{$this->cancha->id}/tarifas", ['tarifas' => []])->assertForbidden();
        $this->getJson('/api/cv/reportes/ocupacion?desde=2026-12-01&hasta=2026-12-07')->assertForbidden();
        $this->getJson('/api/cv/usuarios')->assertForbidden();
        $this->getJson('/api/cv/caja')->assertOk();
    }

    public function test_administradora_gestiona_cuentas_del_personal(): void
    {
        $this->como($this->admin);
        $nuevo = $this->postJson('/api/cv/usuarios', ['nombre' => 'Recepción Prueba', 'email' => 'rec@prueba.test', 'password' => 'clave-segura-2', 'rol' => 'recepcion'])
            ->assertCreated()->json('id');
        $this->getJson('/api/cv/usuarios')->assertOk()->assertJsonFragment(['email' => 'rec@prueba.test']);
        $this->putJson("/api/cv/usuarios/{$nuevo}", ['activo' => false])->assertOk()->assertJsonPath('activo', false);
        $this->putJson("/api/cv/usuarios/{$this->admin->id}", ['activo' => false])->assertStatus(422);
    }

    public function test_modo_llamada_busca_por_telefono_con_cualquier_formato(): void
    {
        $this->crearReserva('2026-12-02 10:00', '2026-12-02 11:00');
        $this->getJson('/api/cv/clientes/telefono/900-000-002')->assertOk()
             ->assertJsonPath('encontrado', true)->assertJsonPath('cliente.nombre', 'Cliente Teléfono Prueba')
             ->assertJsonPath('resumen.reservas', 1);
        $this->getJson('/api/cv/clientes/telefono/999888777')->assertOk()->assertJsonPath('encontrado', false)
             ->assertJsonPath('telefono', '51999888777');
    }

    public function test_un_telefono_guardado_sin_el_51_tambien_se_encuentra(): void
    {
        // Así quedó la cuenta de cliente de la demostración hasta el 22/11: su ficha salía vacía en Windows.
        $this->como($this->recepcion);
        Cliente::create(['uuid' => (string) Str::uuid(), 'negocio_id' => $this->negocio->id, 'nombre' => 'Sin normalizar', 'telefono' => '900000099']);
        $this->getJson('/api/cv/clientes/telefono/51900000099')->assertOk()
             ->assertJsonPath('encontrado', true)->assertJsonPath('cliente.nombre', 'Sin normalizar');
        $this->getJson('/api/cv/clientes/telefono/900-000-099')->assertOk()->assertJsonPath('cliente.nombre', 'Sin normalizar');
        // Si existen las dos formas, gana la normalizada.
        $this->getJson('/api/cv/clientes/telefono/900000002')->assertOk()->assertJsonPath('cliente.nombre', 'Cliente Teléfono Prueba');
        Cliente::create(['uuid' => (string) Str::uuid(), 'negocio_id' => $this->negocio->id, 'nombre' => 'Copia sin 51', 'telefono' => '900000002']);
        $this->getJson('/api/cv/clientes/telefono/900000002')->assertOk()->assertJsonPath('cliente.nombre', 'Cliente Teléfono Prueba');
    }

    public function test_alta_de_cliente_con_telefono_repetido_devuelve_el_existente(): void
    {
        $this->como($this->recepcion);
        $this->postJson('/api/cv/clientes', ['nombre' => 'Duplicado', 'telefono' => '51900000002'])
             ->assertOk()->assertJsonPath('unido_a_existente', true)->assertJsonPath('uuid', $this->clienteTel->uuid);
        $this->assertSame(2, Cliente::count());
        $this->putJson("/api/cv/clientes/{$this->clienteTel->id}", ['telefono' => '900000001'])->assertStatus(409);
    }

    public function test_mismo_cliente_registrado_a_la_vez_desde_dos_equipos_se_une(): void
    {
        // Otro equipo guarda el mismo celular justo antes de que se inserte este cliente.
        $this->como($this->recepcion);
        $otroUuid = (string) Str::uuid();
        $guardado = false;
        DB::listen(function ($consulta) use (&$guardado, $otroUuid) {
            if ($guardado || ! str_contains($consulta->sql, '"deleted_at" is not null')) {
                return;
            }
            $guardado = true;
            Cliente::create(['uuid' => $otroUuid, 'negocio_id' => $this->clienteTel->negocio_id,
                'nombre' => 'Desde el otro equipo', 'telefono' => '51987000111']);
        });

        $this->postJson('/api/cv/clientes', ['uuid' => (string) Str::uuid(), 'nombre' => 'Desde este equipo', 'telefono' => '987 000 111'])
             ->assertOk()->assertJsonPath('unido_a_existente', true)->assertJsonPath('uuid', $otroUuid);
        $this->assertTrue($guardado);
        $this->assertSame(1, Cliente::where('telefono', '51987000111')->count());
    }
}
