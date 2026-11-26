<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * CanchaVoley · Etapa 1. Clientes del negocio.
 *  - Sin cuenta: los registra recepción por llamada (user_id NULL).
 *  - Con cuenta: se registran desde la app Android (user_id → users con rol `cliente`).
 * El teléfono identifica al cliente (modo llamada) y evita duplicados.
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('clientes', function (Blueprint $table) {
            $table->id();
            $table->uuid('uuid')->unique();
            $table->foreignId('negocio_id')->constrained('negocios')->cascadeOnDelete();
            $table->foreignId('user_id')->nullable()->unique()->constrained('users')->nullOnDelete();
            $table->string('nombre', 120);
            $table->string('telefono', 20);
            $table->string('telefono_alterno', 20)->nullable();
            $table->text('notas')->nullable();
            $table->boolean('es_frecuente')->default(false);
            $table->foreignId('creado_por')->nullable()->constrained('users')->nullOnDelete();
            $table->timestamps();
            $table->softDeletes();

            $table->unique(['negocio_id', 'telefono']);
            $table->index(['negocio_id', 'nombre']);
            $table->index(['negocio_id', 'updated_at']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('clientes');
    }
};
