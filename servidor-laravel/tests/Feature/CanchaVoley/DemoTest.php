<?php

namespace Tests\Feature\CanchaVoley;

use App\Models\Cancha;
use App\Models\Cliente;
use App\Models\Negocio;
use App\Models\Pago;
use App\Models\Reserva;
use App\Models\User;
use Carbon\CarbonImmutable;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

/** El comando de datos ficticios: carga una demostración coherente y nunca toca una base que no sea *_demo. */
class DemoTest extends TestCase
{
    use RefreshDatabase;

    public function test_carga_datos_ficticios_con_los_servicios_reales_y_no_duplica(): void
    {
        $this->travelTo(CarbonImmutable::parse('2026-12-01 10:00:00', 'America/Lima'));

        $this->artisan('canchavoley:demo')->assertSuccessful();

        $this->assertSame(1, Negocio::count());
        $this->assertSame(1, Cancha::count());                 // como el negocio real: una sola cancha
        $this->assertSame(['20.00'], \App\Models\Tarifa::pluck('precio_hora')->unique()->values()->all());   // S/ 20 por hora
        $this->assertSame(9, Cliente::count());                // 8 por teléfono + 1 con cuenta de la app
        $this->assertSame(25, Reserva::count());                // 11 próximas + 14 del historial
        $this->assertSame(11, Reserva::where('inicio', '>=', now())->count());
        // Historial: jugadas, confirmadas, con asistencia y pagadas completas antes de jugar.
        $pasadas = Reserva::where('inicio', '<', now())->get();
        $this->assertSame(14, $pasadas->count());
        foreach ($pasadas as $r) {
            $this->assertSame('confirmada', $r->estado);
            $this->assertSame('asistio', $r->asistencia);
            $this->assertGreaterThan(0, (float) $r->precio_final);
            $this->assertSame((float) $r->precio_final, (float) Pago::where('reserva_id', $r->id)->where('estado', 'valido')->sum('monto'));
        }
        $this->assertSame(14, Pago::where('pagado_en', '<', now()->startOfDay())->count());
        $this->assertGreaterThan(14, Pago::count());
        $this->assertSame(3, User::whereIn('email', ['admin@demo.canchavoley', 'recepcion@demo.canchavoley', 'cliente@demo.canchavoley'])->count());
        // Con adelanto obligatorio: las que no pagaron quedan pendientes; las pagadas, confirmadas con QR.
        $this->assertSame(2, Reserva::where('estado', 'pendiente')->where('pendiente_por', 'adelanto')->count());
        $this->assertSame(9 + 14, Reserva::where('estado', 'confirmada')->whereNotNull('codigo_qr')->count());
        // Los reportes de la última semana ya tienen ocupación e ingresos.
        $reporte = app(\App\Services\CanchaVoley\Reportes::class)->ocupacion(now()->toImmutable()->subDays(7), now()->toImmutable()->subDay());
        $this->assertGreaterThan(0, $reporte['ocupacion_pct'] ?? 0);

        $this->artisan('canchavoley:demo')->assertSuccessful();   // segunda vez: no duplica
        $this->assertSame(25, Reserva::count());
    }

    public function test_se_niega_fuera_de_una_base_de_demostracion(): void
    {
        $this->app['env'] = 'local';                            // la base en memoria no termina en _demo
        $this->artisan('canchavoley:demo')->assertFailed();
        $this->assertSame(0, Negocio::count());
    }

    public function test_reiniciar_nunca_borra_una_base_que_no_sea_de_demostracion(): void
    {
        $this->artisan('canchavoley:demo')->assertSuccessful();
        $this->artisan('canchavoley:demo', ['--reiniciar' => true])->assertFailed();   // base en memoria, no *_demo
        $this->assertSame(25, Reserva::count());
    }

    public function test_el_seeder_heredado_de_prolech_no_crea_nada_fuera_de_pruebas(): void
    {
        $this->app['env'] = 'local';
        $this->seed(\Database\Seeders\DatabaseSeeder::class);
        $this->assertSame(0, User::count());
    }
}
