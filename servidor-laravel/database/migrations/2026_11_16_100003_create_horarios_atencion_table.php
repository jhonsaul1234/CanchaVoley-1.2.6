<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * CanchaVoley · Etapa 1. Horario de atención por cancha y día (1 = lunes … 7 = domingo).
 * Varias filas por día = turnos partidos; un día sin filas = cerrado.
 * Si `cierra` <= `abre`, el turno termina al día siguiente (atención pasada la medianoche).
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('horarios_atencion', function (Blueprint $table) {
            $table->id();
            $table->foreignId('cancha_id')->constrained('canchas')->cascadeOnDelete();
            $table->unsignedTinyInteger('dia_semana');
            $table->time('abre');
            $table->time('cierra');
            $table->timestamps();

            $table->index(['cancha_id', 'dia_semana']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('horarios_atencion');
    }
};
