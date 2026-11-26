<?php
/**
 * CanchaVoley · Prueba de pagos duplicados enviados al mismo tiempo.
 *
 *  1) N solicitudes simultáneas con EL MISMO uuid (reintentos de un mismo pago) → 1 creado (201), el resto 200.
 *  2) N solicitudes simultáneas con distinto uuid pero el MISMO número de operación de Yape → 1 creado, el resto 409.
 * En ambos casos debe quedar un solo pago en la base.
 *
 * Uso: php scripts/prueba_pagos_duplicados.php --url=http://127.0.0.1:8001 --email=correo --password=clave --reserva=UUID --n=10
 */

$o = getopt('', ['url:', 'email:', 'password:', 'reserva:', 'n::']);
foreach (['url', 'email', 'password', 'reserva'] as $k) {
    if (empty($o[$k])) {
        fwrite(STDERR, "Falta --{$k}\n");
        exit(2);
    }
}
$url = rtrim($o['url'], '/');
$n   = max(2, (int) ($o['n'] ?? 10));
$token = json_decode(enviar([[$url . '/api/login', null, ['email' => $o['email'], 'password' => $o['password'], 'dispositivo' => 'prueba-pagos']]])[0]['cuerpo'], true)['token'] ?? null;
if (! $token) {
    fwrite(STDERR, "No se pudo iniciar sesión\n");
    exit(2);
}

$mismoUuid = uuid4();
$caso1 = enviar(array_fill(0, $n, ["{$url}/api/cv/pagos", $token,
    ['uuid' => $mismoUuid, 'reserva_uuid' => $o['reserva'], 'tipo' => 'pago', 'metodo' => 'efectivo', 'monto' => 1]]));
$numero = 'PRUEBA-' . random_int(100000, 999999);
$caso2 = [];
for ($i = 0; $i < $n; $i++) {
    $caso2[] = ["{$url}/api/cv/pagos", $token, ['uuid' => uuid4(), 'reserva_uuid' => $o['reserva'], 'tipo' => 'pago', 'metodo' => 'yape', 'monto' => 1, 'numero_operacion' => $numero]];
}
$caso2 = enviar($caso2);

$ok1 = resumen('Mismo uuid (reintentos)', $caso1, [201 => 1, 200 => $n - 1]);
$ok2 = resumen("Mismo número de operación Yape ({$numero})", $caso2, [201 => 1, 409 => $n - 1]);
echo ($ok1 && $ok2) ? "RESULTADO: CORRECTO (ningún pago duplicado)\n" : "RESULTADO: REVISAR\n";
exit(($ok1 && $ok2) ? 0 : 1);

function resumen(string $titulo, array $respuestas, array $esperado): bool
{
    $codigos = array_count_values(array_column($respuestas, 'codigo'));
    ksort($codigos);
    echo "{$titulo}:\n";
    foreach ($codigos as $c => $cantidad) {
        echo "  HTTP {$c}: {$cantidad}\n";
    }
    return $codigos == $esperado;
}

/** @param list<array{0:string,1:?string,2:array}> $pedidos — se envían todos a la vez */
function enviar(array $pedidos): array
{
    $multi = curl_multi_init();
    $hs = [];
    foreach ($pedidos as [$url, $token, $datos]) {
        $h = curl_init($url);
        $cab = ['Accept: application/json', 'Content-Type: application/json'];
        if ($token) {
            $cab[] = "Authorization: Bearer {$token}";
        }
        curl_setopt_array($h, [CURLOPT_POST => true, CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => 60,
            CURLOPT_HTTPHEADER => $cab, CURLOPT_POSTFIELDS => json_encode($datos)]);
        curl_multi_add_handle($multi, $h);
        $hs[] = $h;
    }
    do {
        $e = curl_multi_exec($multi, $activos);
        if ($activos) {
            curl_multi_select($multi);
        }
    } while ($activos && $e === CURLM_OK);
    $res = [];
    foreach ($hs as $h) {
        $res[] = ['codigo' => curl_getinfo($h, CURLINFO_HTTP_CODE), 'cuerpo' => (string) curl_multi_getcontent($h)];
        curl_multi_remove_handle($multi, $h);
    }
    return $res;
}

function uuid4(): string
{
    $b = random_bytes(16);
    $b[6] = chr((ord($b[6]) & 0x0f) | 0x40);
    $b[8] = chr((ord($b[8]) & 0x3f) | 0x80);
    return vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split(bin2hex($b), 4));
}
