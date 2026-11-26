<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * CanchaVoley (22/11/2026): pagos que el CLIENTE informa desde la app Android (método, número de operación
 * y monto). Son solo una referencia: no cambian el saldo. El personal verifica el ingreso en la cuenta del
 * negocio y recién entonces registra el pago de verdad (tabla pagos).
 *  estado: pendiente | verificado | descartado
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('pagos_informados', function (Blueprint $table) {
            $table->id();
            $table->uuid('uuid')->unique();
            $table->foreignId('negocio_id')->constrained('negocios')->restrictOnDelete();
            $table->foreignId('reserva_id')->constrained('reservas')->restrictOnDelete();
            $table->foreignId('user_id')->nullable()->constrained('users')->nullOnDelete();
            $table->string('metodo', 10);
            $table->string('numero_operacion', 40);
            $table->decimal('monto', 10, 2);
            $table->string('nota', 255)->nullable();
            $table->string('estado', 12)->default('pendiente');
            $table->foreignId('pago_id')->nullable()->constrained('pagos')->nullOnDelete();
            $table->foreignId('revisado_por')->nullable()->constrained('users')->nullOnDelete();
            $table->dateTime('revisado_en')->nullable();
            $table->string('motivo_descarte', 255)->nullable();
            $table->timestamps();

            $table->index(['reserva_id', 'estado']);
            $table->index(['negocio_id', 'metodo', 'numero_operacion']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('pagos_informados');
    }
};
