<?php
/**
 * CanchaVoley · Prueba de reservas simultáneas (protección contra reservas cruzadas).
 *
 * Envía N solicitudes AL MISMO TIEMPO para el mismo horario de la misma cancha y cuenta las respuestas.
 * Resultado correcto: exactamente una 201 (creada) y el resto 409 (horario ocupado). Ninguna 500.
 *
 * Uso (con el backend en marcha y un usuario de recepción o administradora):
 *   php scripts/prueba_concurrencia.php --url=http://127.0.0.1:8001 --email=correo --password=clave \
 *       --cancha=1 --cliente=1 --inicio=2026-12-20T10:00:00-05:00 --fin=2026-12-20T11:00:00-05:00 --n=10 --precio=1
 *   Con --mismo-uuid las N solicitudes reenvían la misma reserva: se espera una 201 y el resto 200.
 *
 * Importante: `php artisan serve` en Windows atiende una solicitud a la vez (no hay paralelismo real).
 * Para una prueba real en Windows, usa el servidor Apache/Nginx de Laragon.
 */

$o = getopt('', ['url:', 'email:', 'password:', 'cancha:', 'cliente:', 'inicio:', 'fin:', 'n::', 'precio::', 'mismo-uuid']);
foreach (['url', 'email', 'password', 'cancha', 'cliente', 'inicio', 'fin'] as $k) {
    if (empty($o[$k])) {
        fwrite(STDERR, "Falta --{$k}\n");
        exit(2);
    }
}
$url = rtrim($o['url'], '/');
$n   = max(2, (int) ($o['n'] ?? 10));
// --mismo-uuid: las N solicitudes son la MISMA reserva reenviada a la vez (reintentos).
$mismoUuid = array_key_exists('mismo-uuid', $o) ? uuid4() : null;

$login = peticion('POST', "{$url}/api/login", ['email' => $o['email'], 'password' => $o['password'], 'dispositivo' => 'prueba-concurrencia']);
if ($login['codigo'] !== 200) {
    fwrite(STDERR, "No se pudo iniciar sesión ({$login['codigo']}): {$login['cuerpo']}\n");
    exit(2);
}
$token = json_decode($login['cuerpo'], true)['token'];

$multi = curl_multi_init();
$handles = [];
for ($i = 0; $i < $n; $i++) {
    $cuerpo = [
        'uuid' => $mismoUuid ?? uuid4(), 'cancha_id' => (int) $o['cancha'], 'cliente_id' => (int) $o['cliente'],
        'inicio' => $o['inicio'], 'fin' => $o['fin'], 'origen' => 'llamada',
    ];
    if (isset($o['precio'])) {
        $cuerpo['precio_final'] = (float) $o['precio'];
    }
    $h = curl_init("{$url}/api/cv/reservas");
    curl_setopt_array($h, [
        CURLOPT_POST => true, CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => 60,
        CURLOPT_HTTPHEADER => ['Accept: application/json', 'Content-Type: application/json', "Authorization: Bearer {$token}"],
        CURLOPT_POSTFIELDS => json_encode($cuerpo),
    ]);
    curl_multi_add_handle($multi, $h);
    $handles[] = $h;
}
$t0 = microtime(true);
do {
    $estado = curl_multi_exec($multi, $activos);
    if ($activos) {
        curl_multi_select($multi);
    }
} while ($activos && $estado === CURLM_OK);
$ms = (int) ((microtime(true) - $t0) * 1000);

$codigos = [];
foreach ($handles as $h) {
    $c = curl_getinfo($h, CURLINFO_HTTP_CODE);
    $codigos[$c] = ($codigos[$c] ?? 0) + 1;
    if ($c >= 500) {
        fwrite(STDERR, 'Respuesta 5xx: ' . substr((string) curl_multi_getcontent($h), 0, 300) . "\n");
    }
    curl_multi_remove_handle($multi, $h);
}
ksort($codigos);
echo "Solicitudes simultáneas: {$n} en {$ms} ms\n";
foreach ($codigos as $c => $cantidad) {
    echo "  HTTP {$c}: {$cantidad}\n";
}
if ($mismoUuid !== null) {
    $ok = ($codigos[201] ?? 0) === 1 && ($codigos[200] ?? 0) === $n - 1;
    echo $ok ? "RESULTADO: CORRECTO (una sola reserva creada; los reintentos reciben la misma, sin 409)\n"
             : "RESULTADO: REVISAR (se esperaba una 201 y el resto 200)\n";
} else {
    $ok = ($codigos[201] ?? 0) === 1 && ($codigos[409] ?? 0) === $n - 1;
    echo $ok ? "RESULTADO: CORRECTO (una sola reserva creada, el resto rechazadas por choque)\n"
             : "RESULTADO: REVISAR (se esperaba una 201 y el resto 409)\n";
}
exit($ok ? 0 : 1);

function peticion(string $metodo, string $url, array $datos): array
{
    $h = curl_init($url);
    curl_setopt_array($h, [
        CURLOPT_CUSTOMREQUEST => $metodo, CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => 30,
        CURLOPT_HTTPHEADER => ['Accept: application/json', 'Content-Type: application/json'],
        CURLOPT_POSTFIELDS => json_encode($datos),
    ]);
    $cuerpo = (string) curl_exec($h);

    return ['codigo' => curl_getinfo($h, CURLINFO_HTTP_CODE), 'cuerpo' => $cuerpo];
}

function uuid4(): string
{
    $b = random_bytes(16);
    $b[6] = chr((ord($b[6]) & 0x0f) | 0x40);
    $b[8] = chr((ord($b[8]) & 0x3f) | 0x80);

    return vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split(bin2hex($b), 4));
}
