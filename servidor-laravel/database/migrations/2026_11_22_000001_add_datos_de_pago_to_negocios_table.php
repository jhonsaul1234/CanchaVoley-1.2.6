<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * CanchaVoley (22/11/2026): datos de pago que publica el negocio para que el cliente pague su adelanto
 * (Yape o Plin). Los llena la administradora desde la app; aquí no se inserta ningún número.
 * Solo agrega columnas opcionales: no cambia ni borra datos existentes.
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::table('negocios', function (Blueprint $table) {
            $table->string('pago_yape', 20)->nullable()->after('direccion');
            $table->string('pago_plin', 20)->nullable()->after('pago_yape');
            $table->string('pago_titular', 120)->nullable()->after('pago_plin');
            $table->string('pago_instrucciones', 500)->nullable()->after('pago_titular');
        });
    }

    public function down(): void
    {
        Schema::table('negocios', function (Blueprint $table) {
            $table->dropColumn(['pago_yape', 'pago_plin', 'pago_titular', 'pago_instrucciones']);
        });
    }
};
