<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * CanchaVoley · Etapa 1. Historial de cada reserva: creada, aprobada, confirmada, rechazada,
 * cancelada, reprogramada, pago registrado/anulado, asistencia. Solo se agregan filas.
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('reserva_eventos', function (Blueprint $table) {
            $table->id();
            $table->foreignId('reserva_id')->constrained('reservas')->cascadeOnDelete();
            $table->string('tipo', 30);
            $table->string('descripcion', 255);
            $table->json('datos')->nullable();
            $table->foreignId('user_id')->nullable()->constrained('users')->nullOnDelete();
            $table->uuid('op_uuid')->nullable();
            $table->timestamp('created_at')->nullable();

            $table->index(['reserva_id', 'id']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('reserva_eventos');
    }
};
