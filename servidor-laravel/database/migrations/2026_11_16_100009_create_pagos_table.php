<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

/**
 * CanchaVoley · Etapa 1. Pagos (efectivo, Yape, Plin). No se editan ni se borran:
 * un error se corrige anulando el pago y registrando otro.
 *  tipo: adelanto | pago | devolucion      estado: valido | anulado
 * El número de operación de Yape/Plin es único por negocio y método (MySQL admite varios NULL).
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('pagos', function (Blueprint $table) {
            $table->id();
            $table->uuid('uuid')->unique();
            $table->foreignId('negocio_id')->constrained('negocios')->restrictOnDelete();
            $table->foreignId('reserva_id')->constrained('reservas')->restrictOnDelete();
            $table->string('tipo', 12);
            $table->string('metodo', 10);
            $table->decimal('monto', 10, 2);
            $table->string('numero_operacion', 40)->nullable();
            // Al anular un pago su número de operación se mueve aquí, para poder registrarlo de nuevo bien.
            $table->string('numero_operacion_anulado', 40)->nullable();
            $table->dateTime('pagado_en');
            $table->string('estado', 10)->default('valido');
            $table->string('motivo_anulacion', 255)->nullable();
            $table->foreignId('anulado_por')->nullable()->constrained('users')->nullOnDelete();
            $table->dateTime('anulado_en')->nullable();
            $table->foreignId('registrado_por')->nullable()->constrained('users')->nullOnDelete();
            $table->boolean('creado_offline')->default(false);
            $table->uuid('dispositivo_uuid')->nullable();
            $table->string('observacion', 255)->nullable();
            $table->timestamps();

            $table->unique(['negocio_id', 'metodo', 'numero_operacion']);
            $table->index(['reserva_id', 'estado']);
            $table->index(['negocio_id', 'pagado_en']);
            $table->index(['negocio_id', 'updated_at']);
        });

        if (in_array(DB::getDriverName(), ['mysql', 'mariadb'], true)) {
            DB::statement('ALTER TABLE pagos ADD CONSTRAINT pagos_monto_positivo CHECK (monto > 0)');
        }
    }

    public function down(): void
    {
        Schema::dropIfExists('pagos');
    }
};
