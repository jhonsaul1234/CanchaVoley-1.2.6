<?php

namespace Tests\Feature\CanchaVoley;

use App\Models\Cancha;
use App\Models\Cliente;
use App\Models\Negocio;
use App\Models\Reserva;
use App\Models\User;
use Carbon\CarbonImmutable;
use Illuminate\Support\Str;
use Laravel\Sanctum\Sanctum;

/**
 * Escenario de prueba de CanchaVoley. TODOS los valores (horario, precios, nombres) son FICTICIOS
 * y existen solo para las pruebas automáticas: no representan al negocio real.
 */
trait EscenarioCancha
{
    protected Negocio $negocio;
    protected Cancha $cancha;
    protected User $admin;
    protected User $recepcion;
    protected User $usuarioCliente;
    protected Cliente $clienteApp;
    protected Cliente $clienteTel;

    protected function prepararEscenario(bool $conHorario = true, bool $conTarifas = true): void
    {
        // Martes 1 de diciembre de 2026, 10:00 en Lima (fecha ficticia fija para pruebas repetibles).
        $this->travelTo(CarbonImmutable::parse('2026-12-01 10:00:00', 'America/Lima'));

        $this->negocio = Negocio::create(['nombre' => 'Negocio de prueba']);
        $this->cancha  = Cancha::create(['negocio_id' => $this->negocio->id, 'nombre' => 'Cancha de prueba']);
        if ($conHorario) {
            foreach (range(1, 7) as $dia) {
                $this->cancha->horarios()->create(['dia_semana' => $dia, 'abre' => '08:00', 'cierra' => '22:00']);
            }
        }
        if ($conTarifas) {
            foreach (range(1, 7) as $dia) {
                $this->cancha->tarifas()->create(['dia_semana' => $dia, 'desde' => '08:00', 'hasta' => '18:00', 'precio_hora' => '30.00', 'vigente_desde' => '2026-01-01']);
                $this->cancha->tarifas()->create(['dia_semana' => $dia, 'desde' => '18:00', 'hasta' => '22:00', 'precio_hora' => '40.00', 'vigente_desde' => '2026-01-01']);
            }
        }

        $this->admin     = User::factory()->create(['rol' => 'admin', 'activo' => true]);
        $this->recepcion = User::factory()->create(['rol' => 'recepcion', 'activo' => true]);
        $this->usuarioCliente = User::factory()->create(['rol' => 'cliente', 'activo' => true]);
        $this->clienteApp = Cliente::create([
            'uuid' => (string) Str::uuid(), 'negocio_id' => $this->negocio->id, 'user_id' => $this->usuarioCliente->id,
            'nombre' => 'Cliente App Prueba', 'telefono' => '51900000001',
        ]);
        $this->clienteTel = Cliente::create([
            'uuid' => (string) Str::uuid(), 'negocio_id' => $this->negocio->id,
            'nombre' => 'Cliente Teléfono Prueba', 'telefono' => '51900000002',
        ]);
    }

    protected function como(User $user): static
    {
        Sanctum::actingAs($user);

        return $this;
    }

    protected function reglas(array $valores): void
    {
        $this->negocio->reglasOVacias()->fill($valores)->save();
    }

    /** Fecha-hora ficticia en Lima con desfase (formato que envían las apps). */
    protected function t(string $fechaHora): string
    {
        return CarbonImmutable::parse($fechaHora, 'America/Lima')->toIso8601String();
    }

    protected function datosReserva(string $inicio, string $fin, array $extra = []): array
    {
        return [
            'uuid'       => (string) Str::uuid(),
            'cancha_id'  => $this->cancha->id,
            'cliente_id' => $this->clienteTel->id,
            'inicio'     => $this->t($inicio),
            'fin'        => $this->t($fin),
            'origen'     => 'llamada',
            ...$extra,
        ];
    }

    protected function crearReserva(string $inicio, string $fin, array $extra = []): Reserva
    {
        $this->como($this->recepcion);
        $r = $this->postJson('/api/cv/reservas', $this->datosReserva($inicio, $fin, $extra))->assertCreated();

        return Reserva::where('uuid', $r->json('uuid'))->firstOrFail();
    }
}
