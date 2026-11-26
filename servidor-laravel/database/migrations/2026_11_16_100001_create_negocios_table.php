<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * CanchaVoley · Etapa 1. Negocio dueño de las canchas. Hoy hay uno solo (el de la
 * hermana del autor); la tabla deja preparado el uso por varios negocios.
 * Sus datos reales los llena la administradora (no se insertan aquí).
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('negocios', function (Blueprint $table) {
            $table->id();
            $table->string('nombre', 120);
            $table->string('telefono', 20)->nullable();
            $table->string('direccion', 200)->nullable();
            $table->string('zona_horaria', 40)->default('America/Lima');
            $table->char('moneda', 3)->default('PEN');
            $table->boolean('activo')->default(true);
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('negocios');
    }
};
