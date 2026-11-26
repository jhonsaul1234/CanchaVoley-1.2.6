<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * CanchaVoley · Etapa 1. Franjas de precio por cancha y día. `precio_hora` en soles.
 * Las vigencias permiten cambiar precios sin alterar las reservas ya hechas
 * (el precio se congela en cada reserva). No se insertan precios: los carga la administradora.
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('tarifas', function (Blueprint $table) {
            $table->id();
            $table->foreignId('cancha_id')->constrained('canchas')->cascadeOnDelete();
            $table->unsignedTinyInteger('dia_semana');
            $table->time('desde');
            $table->time('hasta');
            $table->decimal('precio_hora', 10, 2);
            $table->date('vigente_desde');
            $table->date('vigente_hasta')->nullable();
            $table->timestamps();

            $table->index(['cancha_id', 'dia_semana', 'vigente_desde']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('tarifas');
    }
};
