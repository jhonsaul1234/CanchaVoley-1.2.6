<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * CanchaVoley · Etapa 1. Reglas de reserva del negocio (una fila por negocio).
 * Toda columna de regla NULL significa «sin configurar»: el sistema no supone ningún valor.
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('reglas_reserva', function (Blueprint $table) {
            $table->id();
            $table->foreignId('negocio_id')->unique()->constrained('negocios')->cascadeOnDelete();
            $table->unsignedSmallInteger('duracion_minima_min')->nullable();
            $table->unsignedSmallInteger('duracion_maxima_min')->nullable();
            $table->unsignedSmallInteger('paso_min')->nullable();
            $table->unsignedSmallInteger('alinear_inicio_min')->nullable();
            $table->unsignedSmallInteger('margen_entre_reservas_min')->nullable();
            $table->unsignedInteger('anticipacion_minima_min')->nullable();
            $table->unsignedSmallInteger('anticipacion_maxima_dias')->nullable();
            $table->string('regla_cruce_franjas', 20)->nullable();      // prorrateo | franja_inicio
            $table->string('adelanto_tipo', 12)->nullable();            // monto | porcentaje
            $table->decimal('adelanto_valor', 10, 2)->nullable();
            $table->unsignedInteger('adelanto_plazo_min')->nullable();
            $table->boolean('adelanto_obligatorio')->nullable();
            $table->unsignedSmallInteger('cancelacion_limite_horas')->nullable();
            $table->string('cancelacion_devolucion', 12)->nullable();   // total | parcial | ninguna | credito
            $table->decimal('cancelacion_porcentaje', 5, 2)->nullable();
            $table->unsignedSmallInteger('reprogramacion_limite_horas')->nullable();
            $table->unsignedSmallInteger('tolerancia_no_show_min')->nullable();
            $table->foreignId('updated_by')->nullable()->constrained('users')->nullOnDelete();
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('reglas_reserva');
    }
};
