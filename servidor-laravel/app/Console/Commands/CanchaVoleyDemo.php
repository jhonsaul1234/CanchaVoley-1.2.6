<?php

namespace App\Console\Commands;

use App\Models\Cancha;
use App\Models\HorarioAtencion;
use App\Models\Negocio;
use App\Models\Pago;
use App\Models\Reserva;
use App\Models\Tarifa;
use App\Models\User;
use App\Services\CanchaVoley\CalculadoraPrecio;
use App\Services\CanchaVoley\ServicioClientes;
use App\Services\CanchaVoley\ServicioPagos;
use App\Services\CanchaVoley\ServicioReservas;
use Carbon\CarbonImmutable;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Str;

/**
 * CanchaVoley: carga DATOS FICTICIOS para demostraciones y pruebas manuales.
 *
 * Solo se ejecuta en una base cuyo nombre termine en "_demo" (por ejemplo cancha_voley_demo), nunca en
 * la base real del negocio. Los nombres, teléfonos, horarios, tarifas y reglas son inventados para la
 * demostración y NO son los del negocio real: el negocio real configura los suyos desde la app.
 * Las reservas y los pagos se crean con los mismos servicios que usa la API (mismas validaciones).
 */
class CanchaVoleyDemo extends Command
{
    public const CLAVE_DEMO = \App\Support\CuentasCanchaVoley::CLAVE_DEMO;

    protected $signature = 'canchavoley:demo
        {--reiniciar : Borra las tablas de la base de demostración y la vuelve a crear antes de cargar los datos}';

    protected $description = 'Carga datos ficticios de demostración (solo en una base cuyo nombre termine en _demo)';

    public function handle(ServicioClientes $clientes, ServicioReservas $reservas, ServicioPagos $pagos): int
    {
        $base = (string) DB::connection()->getDatabaseName();
        $this->info("Base de datos: {$base} (" . DB::getDriverName() . ')');
        $esDemo = str_ends_with($base, '_demo');
        if (! $esDemo && ! app()->environment('testing')) {
            $this->error('Este comando solo carga datos en una base de demostración (nombre terminado en _demo).');
            $this->line('La base actual es "' . $base . '": no se cambió nada.');
            return self::FAILURE;
        }
        if ($this->option('reiniciar')) {
            // Borrar tablas solo en una base *_demo de verdad (ni siquiera en la base en memoria de las pruebas).
            if (! $esDemo) {
                $this->error('--reiniciar solo se permite en una base cuyo nombre termine en _demo; no se cambió nada.');
                return self::FAILURE;
            }
            $this->warn("Reiniciando la base de demostración {$base}: se borran sus tablas y se vuelven a crear.");
            $this->call('migrate:fresh', ['--force' => true]);
        }
        if (Negocio::query()->exists()) {
            $this->warn('La base ya tiene un negocio registrado: no se cargan los datos de demostración otra vez.');
            return self::SUCCESS;
        }

        $hoy = CarbonImmutable::now()->startOfDay();

        // ── Cuentas de demostración ───────────────────────────────────────────
        $admin = $this->usuario('Administradora (demo)', 'admin@demo.canchavoley', 'admin');
        $recepcion = $this->usuario('Recepción (demo)', 'recepcion@demo.canchavoley', 'recepcion');

        // ── Negocio, cancha, horario, tarifa y reglas ─────────────────────────
        // Como el negocio real: UNA cancha a S/ 20 por hora (dato informado por el estudiante el 21/11/2026).
        // El horario 08:00–23:00, las reglas, los clientes y las reservas son INVENTADOS.
        $negocio = Negocio::create([
            'nombre'    => 'Cancha de Vóley DEMO',
            'telefono'  => '900000000',
            'direccion' => 'Dirección ficticia de demostración',
        ]);
        $canchas = [
            Cancha::create(['negocio_id' => $negocio->id, 'nombre' => 'Cancha de vóley (demo)', 'deporte' => 'voley', 'orden' => 1]),
        ];
        foreach ($canchas as $cancha) {
            for ($dia = 1; $dia <= 7; $dia++) {
                HorarioAtencion::create(['cancha_id' => $cancha->id, 'dia_semana' => $dia, 'abre' => '08:00', 'cierra' => '23:00']);
                Tarifa::create([
                    'cancha_id' => $cancha->id, 'dia_semana' => $dia, 'desde' => '08:00', 'hasta' => '23:00',
                    'precio_hora' => '20.00', 'vigente_desde' => $hoy->subDays(30)->toDateString(),
                ]);
            }
        }
        $negocio->reglasOVacias()->update([
            'duracion_minima_min'         => 60,
            'duracion_maxima_min'         => 180,
            'paso_min'                    => 30,
            'alinear_inicio_min'          => 30,
            'margen_entre_reservas_min'   => 0,
            'anticipacion_minima_min'     => 60,
            'anticipacion_maxima_dias'    => 30,
            'regla_cruce_franjas'         => 'prorrateo',
            'adelanto_tipo'               => 'porcentaje',
            'adelanto_valor'              => '50.00',
            'adelanto_plazo_min'          => 180,
            'adelanto_obligatorio'        => true,
            'cancelacion_limite_horas'    => 24,
            'cancelacion_devolucion'      => 'credito',
            'reprogramacion_limite_horas' => 12,
            'tolerancia_no_show_min'      => 15,
            'updated_by'                  => $admin->id,
        ]);

        // ── Clientes ficticios (teléfonos 9000000xx) ──────────────────────────
        $nombres = [
            'Equipo Las Águilas (demo)', 'Club Voley Norte (demo)', 'Ana Quispe (demo)', 'Luis Mamani (demo)',
            'Rosa Condori (demo)', 'Promoción 2015 (demo)', 'Carlos Apaza (demo)', 'Vóley Damas (demo)',
        ];
        $lista = [];
        foreach ($nombres as $i => $nombre) {
            [$cliente] = $clientes->crear([
                'nombre' => $nombre, 'telefono' => sprintf('9000000%02d', $i + 1), 'es_frecuente' => $i < 2,
            ], $admin);
            $lista[] = $cliente;
        }

        // Cuenta de cliente para la app (rol cliente) unida a un cliente ficticio. El teléfono se guarda
        // normalizado (con el 51), igual que en el registro desde la app, para que su ficha se encuentre.
        $telefonoCuenta = \App\Models\Cliente::normalizarTelefono('900000099');
        $cuentaCliente = User::create([
            'name' => 'Cliente App (demo)', 'email' => 'cliente@demo.canchavoley',
            'password' => Hash::make(self::CLAVE_DEMO), 'rol' => 'cliente', 'activo' => true, 'telefono' => $telefonoCuenta,
        ]);
        \App\Models\Cliente::create([
            'uuid' => (string) Str::uuid(), 'negocio_id' => $negocio->id, 'user_id' => $cuentaCliente->id,
            'nombre' => 'Cliente App (demo)', 'telefono' => $telefonoCuenta,
        ]);

        // ── Historial de las dos semanas anteriores (ya jugadas y pagadas) ────
        // Para que Reportes y la caja de días pasados tengan datos. El servicio de reservas no
        // registra reservas en el pasado, así que estas se insertan directamente, con el precio que
        // dan las tarifas ficticias de arriba y un pago completo antes de jugar.
        $calculadora = app(CalculadoraPrecio::class);
        $regla = $negocio->reglasOVacias();
        $operacion = 1;
        // [días antes de hoy, cancha, hora de inicio, minutos, cliente, método de pago]
        $historial = [
            [13, 0, '19:00', 120, 0, 'efectivo'],
            [12, 0, '18:00', 60, 2, 'yape'],
            [11, 0, '20:00', 120, 5, 'plin'],
            [10, 0, '19:00', 90, 1, 'yape'],
            [9, 0, '10:00', 120, 7, 'efectivo'],
            [8, 0, '18:00', 60, 4, 'yape'],
            [7, 0, '19:00', 120, 0, 'efectivo'],
            [6, 0, '18:30', 90, 3, 'plin'],
            [5, 0, '20:00', 60, 2, 'yape'],
            [4, 0, '17:00', 120, 1, 'efectivo'],
            [3, 0, '19:00', 120, 5, 'yape'],
            [2, 0, '09:00', 120, 6, 'efectivo'],
            [1, 0, '18:00', 120, 0, 'yape'],
            [1, 0, '20:00', 60, 4, 'plin'],
        ];
        $pasadas = 0;
        foreach ($historial as [$dias, $c, $hora, $minutos, $ci, $metodo]) {
            $inicio = CarbonImmutable::parse($hoy->subDays($dias)->toDateString() . ' ' . $hora);
            $fin = $inicio->addMinutes($minutos);
            $precio = $calculadora->calcular($canchas[$c], $inicio, $fin, $regla)['precio'] ?? '0.00';
            $reserva = Reserva::create([
                'uuid' => (string) Str::uuid(), 'negocio_id' => $negocio->id, 'cancha_id' => $canchas[$c]->id,
                'cliente_id' => $lista[$ci]->id, 'inicio' => $inicio, 'fin' => $fin, 'duracion_min' => $minutos,
                'estado' => 'confirmada', 'asistencia' => 'asistio', 'check_in_en' => $inicio->subMinutes(5),
                'origen' => $ci % 3 === 0 ? 'whatsapp' : 'llamada',
                'precio_calculado' => $precio, 'precio_final' => $precio, 'precio_manual' => false,
                'adelanto_requerido' => $regla->adelantoPara($precio),
                'codigo_qr' => strtoupper(Str::random(20)),
                'creado_por' => $recepcion->id, 'notas' => 'Reserva ficticia de demostración (historial)',
            ]);
            Pago::create([
                'uuid' => (string) Str::uuid(), 'negocio_id' => $negocio->id, 'reserva_id' => $reserva->id,
                'tipo' => 'pago', 'metodo' => $metodo, 'monto' => $precio,
                'numero_operacion' => $metodo === 'efectivo' ? null : sprintf('DEMO%04d', $operacion++),
                'pagado_en' => $inicio->subMinutes(10), 'estado' => 'valido', 'registrado_por' => $recepcion->id,
            ]);
            $pasadas++;
        }

        // ── Reservas y pagos de los próximos días (con los servicios reales) ──
        // [días desde hoy, cancha, hora inicio, minutos, cliente, pago] — una sola cancha, sin cruces.
        // Mañana: 16:00 Ana Quispe (adelanto), 18:00–20:00 Equipo Las Águilas (pagado) y 20:00 Luis Mamani (sin pago).
        $plan = [
            [1, 0, '18:00', 120, 0, 'completo_efectivo'],
            [1, 0, '16:00', 60, 2, 'adelanto_yape'],
            [1, 0, '20:00', 60, 3, null],
            [2, 0, '17:00', 90, 1, 'adelanto_plin'],
            [2, 0, '20:00', 120, 5, 'completo_yape'],
            [3, 0, '10:00', 60, 4, 'adelanto_yape'],
            [3, 0, '19:00', 120, 0, 'completo_efectivo'],
            [4, 0, '18:30', 90, 6, null],
            [5, 0, '09:00', 120, 7, 'adelanto_yape'],
            [5, 0, '16:00', 60, 2, 'completo_efectivo'],
            [6, 0, '15:00', 180, 1, 'adelanto_plin'],
        ];
        $creadas = 0;
        foreach ($plan as [$dias, $c, $hora, $minutos, $ci, $pago]) {
            $inicio = CarbonImmutable::parse($hoy->addDays($dias)->toDateString() . ' ' . $hora);
            [$reserva] = $reservas->crear([
                'cancha_id' => $canchas[$c]->id, 'cliente_id' => $lista[$ci]->id,
                'inicio' => $inicio->toIso8601String(), 'fin' => $inicio->addMinutes($minutos)->toIso8601String(),
                'origen' => $ci % 3 === 0 ? 'whatsapp' : 'llamada', 'notas' => 'Reserva ficticia de demostración',
            ], $ci % 2 === 0 ? $admin : $recepcion);
            $creadas++;
            if ($pago === null) {
                continue;
            }
            [$tipo, $metodo] = explode('_', $pago);
            $monto = $tipo === 'completo' ? $reserva->precio_final : $reserva->adelanto_requerido;
            $pagos->registrar([
                'reserva_uuid' => $reserva->uuid,
                'tipo' => $tipo === 'completo' ? 'pago' : 'adelanto',
                'metodo' => $metodo,
                'monto' => $monto,
                'numero_operacion' => $metodo === 'efectivo' ? null : sprintf('DEMO%04d', $operacion++),
            ], $recepcion);
        }

        $this->info('Datos de demostración cargados: 1 negocio, 1 cancha a S/ 20 por hora, ' . count($lista) . " clientes, {$creadas} reservas próximas y {$pasadas} ya jugadas (historial de dos semanas).");
        $this->line('Cuentas de demostración (clave ' . self::CLAVE_DEMO . '): admin@demo.canchavoley, '
            . 'recepcion@demo.canchavoley, cliente@demo.canchavoley');
        $this->line('Salvo la tarifa de S/ 20 por hora, todo es inventado: no son datos del negocio real.');

        return self::SUCCESS;
    }

    private function usuario(string $nombre, string $email, string $rol): User
    {
        return User::create([
            'name' => $nombre, 'email' => $email, 'password' => Hash::make(self::CLAVE_DEMO),
            'rol' => $rol, 'activo' => true,
        ]);
    }
}
