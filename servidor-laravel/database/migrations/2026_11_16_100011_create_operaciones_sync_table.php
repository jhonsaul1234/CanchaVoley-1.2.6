<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * CanchaVoley · Etapa 1. Operaciones recibidas desde los equipos (escritorio y Android).
 * El op_uuid único hace que un reenvío reciba la misma respuesta sin aplicarse dos veces.
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('operaciones_sync', function (Blueprint $table) {
            $table->id();
            $table->uuid('op_uuid')->unique();
            $table->foreignId('negocio_id')->nullable()->constrained('negocios')->nullOnDelete();
            $table->foreignId('user_id')->nullable()->constrained('users')->nullOnDelete();
            $table->uuid('dispositivo_uuid')->nullable();
            $table->string('tipo', 30);
            $table->uuid('entidad_uuid')->nullable();
            $table->json('datos');
            $table->string('resultado', 12);              // aplicada | rechazada
            $table->json('respuesta');
            $table->dateTime('creada_en_dispositivo')->nullable();
            $table->dateTime('recibida_en');
            $table->timestamps();

            $table->index('entidad_uuid');
            $table->index(['dispositivo_uuid', 'recibida_en']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('operaciones_sync');
    }
};
