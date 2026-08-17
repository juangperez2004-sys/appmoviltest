#!/usr/bin/env python3
"""
Copia la mejor foto de cada trabajador del PC (carpeta "Data full") a
app/src/main/assets/fotos/, con el nombre exacto del trabajador según
nombres.json. Así la app muestra la foto real de los trabajadores del PC.

Uso:  python copiar_fotos_pc.py
Elige por trabajador la foto con mayor tamaño de archivo (mejor calidad).
"""
import json
import os
import shutil

RAIZ = os.path.dirname(os.path.abspath(__file__))
NOMBRES = os.path.join(RAIZ, "app", "src", "main", "assets", "nombres.json")
ORIGEN = os.path.join(os.path.dirname(RAIZ), "Data full")
DESTINO = os.path.join(RAIZ, "app", "src", "main", "assets", "fotos")

EXT = (".jpg", ".jpeg", ".png")


def mejor_foto(carpeta: str) -> str | None:
    """La foto con mayor tamaño de archivo dentro de la carpeta del trabajador."""
    mejor, tam = None, -1
    for f in os.listdir(carpeta):
        ruta = os.path.join(carpeta, f)
        if not os.path.isfile(ruta) or not f.lower().endswith(EXT):
            continue
        t = os.path.getsize(ruta)
        if t > tam:
            mejor, tam = ruta, t
    return mejor


def main() -> None:
    with open(NOMBRES, encoding="utf-8") as fh:
        nombres = json.load(fh)
    os.makedirs(DESTINO, exist_ok=True)

    copiadas, faltantes = 0, []
    for i, nombre in enumerate(nombres, 1):
        carpeta = os.path.join(ORIGEN, nombre)
        foto = mejor_foto(carpeta) if os.path.isdir(carpeta) else None
        if foto is None:
            faltantes.append(nombre)
            continue
        destino = os.path.join(DESTINO, f"{nombre}.jpg")
        shutil.copy2(foto, destino)
        copiadas += 1
        if i % 50 == 0:
            print(f"  {i}/{len(nombres)}")

    total_mb = sum(
        os.path.getsize(os.path.join(DESTINO, f))
        for f in os.listdir(DESTINO)
        if f.lower().endswith(EXT)
    ) / (1024 * 1024)
    print(f"\nCopiadas: {copiadas}  Faltantes: {len(faltantes)}  Tamaño total: {total_mb:.1f} MB")
    for n in faltantes:
        print("  SIN FOTO:", n)


if __name__ == "__main__":
    main()
