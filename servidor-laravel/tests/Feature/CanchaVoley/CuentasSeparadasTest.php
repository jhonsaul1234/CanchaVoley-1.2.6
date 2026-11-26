<?php

namespace Tests\Feature\CanchaVoley;

use App\Models\User;
use App\Support\CuentasCanchaVoley;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

/**
 * Las cuentas de demostración (clave pública) y las heredadas de ProLech no se mezclan con las del negocio real.
 */
class CuentasSeparadasTest extends TestCase
{
    use EscenarioCancha, RefreshDatabase;

    protected function setUp(): void
    {
        parent::setUp();
        $this->prepararEscenario();
        config(['app.debug' => false]);
    }

    private function cuenta(string $email, string $rol = 'admin'): User
    {
        return User::factory()->create(['email' => $email, 'rol' => $rol, 'activo' => true, 'password' => CuentasCanchaVoley::CLAVE_DEMO]);
    }

    public function test_en_la_base_real_una_cuenta_de_demostracion_no_entra_ni_usa_la_api(): void
    {
        config(['canchavoley.base_de_demostracion' => false]);
        $demo = $this->cuenta('admin@demo.canchavoley');

        $this->postJson('/api/login', ['email' => 'admin@demo.canchavoley', 'password' => CuentasCanchaVoley::CLAVE_DEMO, 'dispositivo' => 'escritorio'])
            ->assertForbidden()->assertJsonPath('message', fn ($m) => str_contains($m, 'solo funcionan con el servidor de demostración'));
        $this->postJson('/api/login', ['email' => 'admin@demo.canchavoley', 'password' => CuentasCanchaVoley::CLAVE_DEMO])->assertForbidden();
        $this->assertSame(0, $demo->tokens()->count());

        // Aunque tuviera un token, la API de CanchaVoley no la atiende.
        $this->actingAs($demo, 'sanctum')->getJson('/api/cv/sesion')->assertForbidden();
    }

    public function test_en_la_base_de_demostracion_las_cuentas_demo_funcionan(): void
    {
        config(['canchavoley.base_de_demostracion' => true]);
        $this->cuenta('admin@demo.canchavoley');

        $token = $this->postJson('/api/login', ['email' => 'admin@demo.canchavoley', 'password' => CuentasCanchaVoley::CLAVE_DEMO, 'dispositivo' => 'escritorio'])
            ->assertOk()->json('token');
        $this->withToken($token)->getJson('/api/cv/sesion')->assertOk()->assertJsonPath('usuario.email', 'admin@demo.canchavoley');
    }

    public function test_la_administradora_heredada_de_prolech_no_entra_a_canchavoley(): void
    {
        $prolech = $this->cuenta('admin@prolech.com');

        $this->postJson('/api/login', ['email' => 'admin@prolech.com', 'password' => CuentasCanchaVoley::CLAVE_DEMO, 'dispositivo' => 'android'])
            ->assertForbidden()->assertJsonPath('message', fn ($m) => str_contains($m, 'es de ProLech'));
        $this->actingAs($prolech, 'sanctum')->getJson('/api/cv/sesion')->assertForbidden();
        $this->actingAs($prolech, 'sanctum')->getJson('/api/cv/usuarios')->assertForbidden();

        // Tampoco aparece en la lista de cuentas del personal del negocio.
        $this->actingAs($this->admin, 'sanctum')->getJson('/api/cv/usuarios')->assertOk()
            ->assertJsonMissing(['email' => 'admin@prolech.com']);
    }

    public function test_no_se_crean_cuentas_con_correo_reservado_ni_con_la_clave_de_la_demostracion(): void
    {
        config(['canchavoley.base_de_demostracion' => false]);
        $this->actingAs($this->admin, 'sanctum');

        $this->postJson('/api/cv/usuarios', ['nombre' => 'X', 'email' => 'x@demo.canchavoley', 'password' => 'clave-segura-9', 'rol' => 'recepcion'])
            ->assertStatus(422)->assertJsonValidationErrors('email');
        $this->postJson('/api/cv/usuarios', ['nombre' => 'X', 'email' => 'x@prolech.com', 'password' => 'clave-segura-9', 'rol' => 'recepcion'])
            ->assertStatus(422)->assertJsonValidationErrors('email');
        $this->postJson('/api/cv/usuarios', ['nombre' => 'X', 'email' => 'x@negocio.test', 'password' => 'demo2026', 'rol' => 'recepcion'])
            ->assertStatus(422)->assertJsonValidationErrors('password');
        $id = $this->postJson('/api/cv/usuarios', ['nombre' => 'X', 'email' => 'x@negocio.test', 'password' => 'clave-segura-9', 'rol' => 'recepcion'])
            ->assertCreated()->json('id');
        $this->putJson("/api/cv/usuarios/{$id}", ['password' => CuentasCanchaVoley::CLAVE_DEMO])->assertStatus(422)->assertJsonValidationErrors('password');

        // Registro de clientes desde Android.
        $this->postJson('/api/cv/registro', ['nombre' => 'C', 'email' => 'c@demo.canchavoley', 'password' => 'clave-segura-9', 'telefono' => '955000111'])
            ->assertStatus(422)->assertJsonValidationErrors('email');
        $this->postJson('/api/cv/registro', ['nombre' => 'C', 'email' => 'c@cliente.test', 'password' => 'Demo2026', 'telefono' => '955000111'])
            ->assertStatus(422)->assertJsonValidationErrors('password');
    }

    public function test_instalar_ignora_la_administradora_de_prolech_y_rechaza_correo_o_clave_de_demo(): void
    {
        $this->admin->forceFill(['activo' => false])->save();         // solo queda la cuenta heredada
        $this->cuenta('admin@prolech.com');

        $this->artisan('canchavoley:instalar', ['--admin-email' => 'jefa@demo.canchavoley', '--admin-nombre' => 'Jefa', '--admin-password' => 'clave-segura-9'])
            ->assertFailed();
        $this->artisan('canchavoley:instalar', ['--admin-email' => 'jefa@negocio.test', '--admin-nombre' => 'Jefa', '--admin-password' => 'Demo2026'])
            ->assertFailed();
        $this->artisan('canchavoley:instalar', ['--admin-email' => 'jefa@negocio.test', '--admin-nombre' => 'Jefa', '--admin-password' => 'clave-segura-9'])
            ->assertSuccessful();
        $this->assertTrue(User::where('email', 'jefa@negocio.test')->where('rol', 'admin')->exists());
    }

    public function test_las_cuentas_heredadas_solo_se_desactivan_si_se_confirma(): void
    {
        $prolech = $this->cuenta('admin@prolech.com');
        $chofer = $this->cuenta('chofer@prolech.com', 'chofer');

        $this->artisan('canchavoley:cuentas-heredadas')->expectsOutputToContain('Cuentas heredadas de ProLech: 2 (activas: 2)')->assertSuccessful();
        $this->artisan('canchavoley:cuentas-heredadas', ['--desactivar' => true])
            ->expectsConfirmation('¿Desactivar 2 cuentas heredadas de ProLech? No se borran y se pueden volver a activar.', 'no')
            ->assertSuccessful();
        $this->assertTrue($prolech->fresh()->activo);

        $this->artisan('canchavoley:cuentas-heredadas', ['--desactivar' => true])
            ->expectsConfirmation('¿Desactivar 2 cuentas heredadas de ProLech? No se borran y se pueden volver a activar.', 'yes')
            ->assertSuccessful();
        $this->assertFalse($prolech->fresh()->activo);
        $this->assertFalse($chofer->fresh()->activo);
        $this->assertTrue($this->admin->fresh()->activo);           // las cuentas del negocio no se tocan
    }
}
