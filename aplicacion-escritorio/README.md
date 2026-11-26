# CanchaVoley — apps de Windows y Android

Sistema de reservas de la cancha de vóley: una sola base de código Kotlin Multiplatform
(Compose) para la PC del negocio (Windows, instalador `.msi`) y para celulares Android.
Funciona con y sin internet: lo hecho sin conexión se guarda en SQLite y se sincroniza
con el servidor (`../cancha-backend`), que valida cada reserva y cada pago.

Nació de una copia independiente de ProLech (ProLech original no se modifica); el código de
ProLech ya se retiró de este repositorio.

- Código: `composeApp/src/commonMain/kotlin/pe/canchavoley/app`
  (`datos`, `dominio`, `plataforma`, `ui`, `di`), lo propio de Windows en `jvmMain` y lo de
  Android en `androidMain`. Base local: `composeApp/src/commonMain/sqldelight/.../CanchaVoley.sq`.
- Windows: `composeDesktopApp` · Android: `composeAndroidApp` (`pe.canchavoley.app`).

## Compilar en la PC (recomendado)

```
scripts\compilar.cmd
```

Ejecuta las pruebas, arma la app de Windows, la comprueba con `CanchaVoley.exe --autoprueba`,
crea el `.msi` y el APK y deja todo en `dist\` (ver `docs/COMPILAR_E_INSTALAR.md`).

## Comandos sueltos

```
.\gradlew :composeApp:jvmTest              # pruebas (88)
.\gradlew :composeDesktopApp:run           # abrir la app de escritorio
.\gradlew :composeDesktopApp:packageMsi    # instalador de Windows
.\gradlew :composeAndroidApp:assembleDebug # APK
```

## Documentación

- [docs/MANUAL_DE_USUARIO.md](docs/MANUAL_DE_USUARIO.md) — uso diario por perfil, sin internet, estados.
- [docs/COMPILAR_E_INSTALAR.md](docs/COMPILAR_E_INSTALAR.md) — script de compilación, pruebas, instalador, APK y prueba de punta a punta.
- [docs/ARQUITECTURA.md](docs/ARQUITECTURA.md) — diseño técnico, sincronización, conflictos y seguridad.
- [docs/INFORME_FUNCIONALIDADES.md](docs/INFORME_FUNCIONALIDADES.md) — qué está hecho, qué está probado y qué falta.
- Servidor, API y base de datos: `../cancha-backend/docs/`.
