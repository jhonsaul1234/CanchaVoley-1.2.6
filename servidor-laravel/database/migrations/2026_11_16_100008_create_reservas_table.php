<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

/**
 * CanchaVoley · Etapa 1. Reservas con inicio y fin (intervalo [inicio, fin)): cualquier duración
 * que permitan las reglas. Nunca se borran: se cancelan o se rechazan.
 *  estado:     pendiente | confirmada | cancelada | rechazada
 *  pendiente_por: aprobacion (solicitud de un cliente) | adelanto (falta el adelanto obligatorio)
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('reservas', function (Blueprint $table) {
            $table->id();
            $table->uuid('uuid')->unique();
            $table->foreignId('negocio_id')->constrained('negocios')->restrictOnDelete();
            $table->foreignId('cancha_id')->constrained('canchas')->restrictOnDelete();
            $table->foreignId('cliente_id')->constrained('clientes')->restrictOnDelete();
            $table->dateTime('inicio');
            $table->dateTime('fin');
            $table->unsignedSmallInteger('duracion_min');
            $table->string('estado', 12);
            $table->string('pendiente_por', 12)->nullable();
            $table->string('asistencia', 12)->nullable();          // asistio | no_asistio
            $table->dateTime('check_in_en')->nullable();
            $table->string('origen', 12);                          // llamada | presencial | whatsapp | app | otro
            $table->decimal('precio_calculado', 10, 2)->nullable();
            $table->decimal('precio_final', 10, 2)->nullable();
            $table->boolean('precio_manual')->default(false);
            $table->decimal('adelanto_requerido', 10, 2)->nullable();
            $table->dateTime('adelanto_vence_en')->nullable();
            $table->string('codigo_qr', 32)->nullable()->unique();
            $table->string('motivo_estado', 255)->nullable();
            $table->boolean('creada_offline')->default(false);
            $table->dateTime('creada_en_dispositivo')->nullable();
            $table->uuid('dispositivo_uuid')->nullable();
            $table->foreignId('creado_por')->nullable()->constrained('users')->nullOnDelete();
            $table->foreignId('actualizado_por')->nullable()->constrained('users')->nullOnDelete();
            $table->foreignId('aprobado_por')->nullable()->constrained('users')->nullOnDelete();
            $table->dateTime('aprobado_en')->nullable();
            $table->text('notas')->nullable();
            $table->timestamps();

            $table->index(['cancha_id', 'estado', 'inicio', 'fin']);
            $table->index(['cliente_id', 'inicio']);
            $table->index(['negocio_id', 'inicio']);
            $table->index(['negocio_id', 'updated_at']);
        });

        if (in_array(DB::getDriverName(), ['mysql', 'mariadb'], true)) {
            DB::statement('ALTER TABLE reservas ADD CONSTRAINT reservas_fin_mayor_que_inicio CHECK (fin > inicio)');
        }
    }

    public function down(): void
    {
        Schema::dropIfExists('reservas');
    }
};
