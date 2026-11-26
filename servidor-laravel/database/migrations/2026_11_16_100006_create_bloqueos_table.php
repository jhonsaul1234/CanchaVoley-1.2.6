<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/** CanchaVoley · Etapa 1. Cierres puntuales (mantenimiento, feriado…). cancha_id NULL = todo el local. */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('bloqueos', function (Blueprint $table) {
            $table->id();
            $table->foreignId('negocio_id')->constrained('negocios')->cascadeOnDelete();
            $table->foreignId('cancha_id')->nullable()->constrained('canchas')->cascadeOnDelete();
            $table->dateTime('inicio');
            $table->dateTime('fin');
            $table->string('motivo', 200);
            $table->foreignId('creado_por')->nullable()->constrained('users')->nullOnDelete();
            $table->timestamps();
            $table->softDeletes();

            $table->index(['cancha_id', 'inicio', 'fin']);
            $table->index(['negocio_id', 'updated_at']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('bloqueos');
    }
};
