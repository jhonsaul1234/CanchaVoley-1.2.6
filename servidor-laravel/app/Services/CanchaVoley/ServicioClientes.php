<?php

namespace App\Services\CanchaVoley;

use App\Exceptions\CanchaVoley\ReservaException;
use App\Models\Cliente;
use App\Models\Negocio;
use App\Models\OperacionSync;
use App\Models\User;
use Illuminate\Database\UniqueConstraintViolationException;
use Illuminate\Support\Str;

/** CanchaVoley: alta y edición de clientes. El teléfono (normalizado) identifica al cliente. */
class ServicioClientes
{
    /**
     * Crea un cliente o devuelve el existente con ese teléfono (o con ese uuid, si es un reintento).
     * @return array{0: Cliente, 1: bool, 2: bool} cliente, creado ahora, unido a uno existente por teléfono
     */
    public function crear(array $d, User $actor): array
    {
        $this->exigirPersonal($actor);
        $negocio = Negocio::principalOFallar();
        $uuid    = (string) ($d['uuid'] ?? Str::uuid());

        if ($existente = Cliente::withTrashed()->where('uuid', $uuid)->first()) {
            return [$existente, false, false];
        }
        $telefono = $this->telefonoValido($d['telefono'] ?? null);
        $nombre   = trim((string) ($d['nombre'] ?? ''));
        if ($nombre === '') {
            throw new ReservaException('Indica el nombre del cliente.', 422, ['falta' => 'nombre']);
        }

        if ($mismo = Cliente::where('negocio_id', $negocio->id)->where('telefono', $telefono)->first()) {
            return [$mismo, false, true];
        }
        if ($borrado = Cliente::onlyTrashed()->where('negocio_id', $negocio->id)->where('telefono', $telefono)->first()) {
            $borrado->restore();
            return [$borrado, false, true];
        }

        try {
            $cliente = $this->insertar($uuid, $negocio->id, $nombre, $telefono, $d, $actor);
        } catch (UniqueConstraintViolationException $e) {
            // Otro equipo registró el mismo cliente (mismo uuid o mismo teléfono) en el mismo instante.
            if ($ya = Cliente::withTrashed()->where('uuid', $uuid)->first()) {
                return [$ya, false, false];
            }
            if ($mismo = Cliente::where('negocio_id', $negocio->id)->where('telefono', $telefono)->first()) {
                return [$mismo, false, true];
            }
            throw $e;
        }

        return [$cliente, true, false];
    }

    private function insertar(string $uuid, int $negocioId, string $nombre, string $telefono, array $d, User $actor): Cliente
    {
        return Cliente::create([
            'uuid'             => $uuid,
            'negocio_id'       => $negocioId,
            'nombre'           => mb_substr($nombre, 0, 120),
            'telefono'         => $telefono,
            'telefono_alterno' => Cliente::normalizarTelefono($d['telefono_alterno'] ?? null),
            'notas'            => $d['notas'] ?? null,
            'es_frecuente'     => (bool) ($d['es_frecuente'] ?? false),
            'creado_por'       => $actor->id,
        ]);
    }

    public function actualizar(Cliente $cliente, array $d, User $actor): Cliente
    {
        $this->exigirPersonal($actor);
        if (array_key_exists('telefono', $d)) {
            $telefono = $this->telefonoValido($d['telefono']);
            $otro = Cliente::withTrashed()->where('negocio_id', $cliente->negocio_id)
                ->where('telefono', $telefono)->where('id', '!=', $cliente->id)->exists();
            if ($otro) {
                throw new ReservaException('Ese teléfono ya pertenece a otro cliente.', 409);
            }
            $cliente->telefono = $telefono;
        }
        foreach (['nombre', 'notas', 'es_frecuente'] as $campo) {
            if (array_key_exists($campo, $d)) {
                $cliente->{$campo} = $d[$campo];
            }
        }
        if (array_key_exists('telefono_alterno', $d)) {
            $cliente->telefono_alterno = Cliente::normalizarTelefono($d['telefono_alterno']);
        }
        $cliente->save();

        return $cliente;
    }

    /** Uuid con el que un equipo creó sin conexión un cliente que el servidor unió a otro por teléfono. */
    public function porUuidProvisional(string $uuid): ?Cliente
    {
        $op = OperacionSync::where('tipo', 'crear_cliente')->where('entidad_uuid', $uuid)->where('resultado', 'aplicada')->first();
        $canonico = $op?->respuesta['entidad']['uuid'] ?? null;

        return $canonico ? Cliente::where('uuid', $canonico)->first() : null;
    }

    /**
     * Busca por teléfono (modo llamada y ficha del cliente). Los teléfonos se guardan con el 51 delante, pero un
     * registro cargado sin normalizar (como la cuenta de cliente de la demostración hasta el 22/11) quedaba
     * imposible de encontrar: se acepta también el número sin el 51, prefiriendo el normalizado.
     */
    public function porTelefono(string $telefono): ?Cliente
    {
        $negocio = Negocio::principalOFallar();
        $normal  = Cliente::normalizarTelefono($telefono);
        if ($normal === null) {
            return null;
        }
        $variantes = [$normal];
        if (strlen($normal) === 11 && str_starts_with($normal, '51')) {
            $variantes[] = substr($normal, 2);
        }

        return Cliente::where('negocio_id', $negocio->id)->whereIn('telefono', $variantes)
            ->orderByRaw('CASE WHEN telefono = ? THEN 0 ELSE 1 END', [$normal])->first();
    }

    public function telefonoValido(?string $telefono): string
    {
        $t = Cliente::normalizarTelefono($telefono);
        if ($t === null || strlen($t) < 7 || strlen($t) > 15) {
            throw new ReservaException('Indica un teléfono válido (por ejemplo 987654321).', 422, ['falta' => 'telefono']);
        }

        return $t;
    }

    private function exigirPersonal(User $actor): void
    {
        if (! $actor->esPersonal()) {
            throw new ReservaException('No tienes permisos para esta acción.', 403);
        }
    }
}
