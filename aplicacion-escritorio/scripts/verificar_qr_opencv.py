"""
CanchaVoley · verificación de los QR generados por la app (CodigoQr.kt) con un lector real.

Uso (después de `gradlew :composeApp:jvmTest`, que deja las matrices en %TEMP%/qr-canchavoley):
    python scripts/verificar_qr_opencv.py [carpeta]

Dibuja cada QR como lo vería una cámara (girado, en perspectiva, desenfocado, con ruido y poca
luz) y lo lee con OpenCV (cv2.QRCodeDetector). Requiere: pip install opencv-python numpy
"""
import glob
import os
import sys
import tempfile

import cv2
import numpy as np


def imagen(matriz, modulo=10, margen=4):
    filas = len(matriz)
    lado = (filas + 2 * margen) * modulo
    img = np.full((lado, lado), 255, np.uint8)
    for f, fila in enumerate(matriz):
        for c, v in enumerate(fila):
            if v == "1":
                y, x = (f + margen) * modulo, (c + margen) * modulo
                img[y:y + modulo, x:x + modulo] = 0
    return img


def variantes(img):
    h, w = img.shape
    yield "nítido", img
    rot = cv2.getRotationMatrix2D((w / 2, h / 2), 17, 0.85)
    yield "girado 17°", cv2.warpAffine(img, rot, (w, h), borderValue=255)
    origen = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
    destino = np.float32([[w * 0.08, h * 0.05], [w * 0.95, 0], [w * 0.88, h * 0.97], [0, h * 0.9]])
    yield "en perspectiva", cv2.warpPerspective(img, cv2.getPerspectiveTransform(origen, destino), (w, h), borderValue=255)
    yield "desenfocado", cv2.GaussianBlur(img, (7, 7), 2.0)
    # Ruido fuerte de sensor (σ=28) con el suavizado mínimo que aplica cualquier cámara (3×3).
    # Sin ningún suavizado, cv2.QRCodeDetector a veces no detecta los QR más pequeños (limitación del detector).
    ruido = np.clip(img.astype(np.int16) + np.random.default_rng(7).normal(0, 28, img.shape), 0, 255).astype(np.uint8)
    yield "con ruido de cámara", cv2.GaussianBlur(ruido, (3, 3), 0)
    yield "poca luz y bajo contraste", (img.astype(np.float32) * 0.35 + 40).astype(np.uint8)
    pequeno = cv2.resize(img, (w // 3, h // 3), interpolation=cv2.INTER_AREA)
    yield "pequeño (a 1/3)", pequeno


def main():
    carpeta = sys.argv[1] if len(sys.argv) > 1 else os.path.join(tempfile.gettempdir(), "qr-canchavoley")
    archivos = sorted(glob.glob(os.path.join(carpeta, "qr*.txt")))
    if not archivos:
        print(f"No hay matrices en {carpeta}: ejecuta antes gradlew :composeApp:jvmTest")
        return 2
    detector = cv2.QRCodeDetector()
    total = ok = 0
    for ruta in archivos:
        matriz = open(ruta, encoding="utf-8").read().split("\n")
        esperado = open(ruta.replace(".txt", ".esperado"), encoding="utf-8").read()
        for nombre, img in variantes(imagen(matriz)):
            total += 1
            leido, _, _ = detector.detectAndDecode(img)
            bien = leido == esperado
            ok += bien
            print(f"{'OK ' if bien else 'MAL'} {os.path.basename(ruta)} {nombre:<26} {leido[:40]!r}")
    print(f"Leídos correctamente: {ok} de {total}")
    return 0 if ok == total else 1


if __name__ == "__main__":
    sys.exit(main())
