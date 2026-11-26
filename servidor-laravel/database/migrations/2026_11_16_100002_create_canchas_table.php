<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/** CanchaVoley · Etapa 1. Canchas del negocio (hoy una; preparado para más). */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('canchas', function (Blueprint $table) {
            $table->id();
            $table->foreignId('negocio_id')->constrained('negocios')->restrictOnDelete();
            $table->string('nombre', 80);
            $table->string('deporte', 30)->default('voley');
            $table->string('descripcion', 255)->nullable();
            $table->boolean('activa')->default(true);
            $table->unsignedSmallInteger('orden')->default(1);
            $table->timestamps();

            $table->unique(['negocio_id', 'nombre']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('canchas');
    }
};
