# Pack Mixer (Fabric 1.21.4)

Mod kliencki, który **łączy wiele texturepacków w jeden**. Wrzucasz packi do folderu `resourcepacks`, mod pokazuje, co jest w środku każdego z nich, a Ty wybierasz osobno dla każdego bloku / przedmiotu / moba / elementu GUI, z którego packa ma pochodzić tekstura (np. obsydian z packa A, reszta z packa B).

**Wymaga licencji** - bez klucza nie otwiera się GUI i mod nic nie robi z packami.

## Budowanie

Wymagania: Java 21.

```
./gradlew build          (Windows: gradlew.bat build)
```

Gotowy plik: `build/libs/packmixer-1.0.0.jar` (nie bierz `-sources.jar`). Wrzuć go do `.minecraft/mods` razem z **Fabric Loader 0.16.10+** i **Fabric API** dla 1.21.4.

Wersje (Fabric API, yarn, loader) są w `gradle.properties`. Jeśli któraś nie istnieje, wybierz aktualne dla 1.21.4 na https://fabricmc.net/develop

> Wgrywając projekt na GitHub, zachowaj foldery (`src`, `gradle`). Najlepiej użyć `git`, a nie wysyłania pojedynczych plików przez przeglądarkę.

## Użycie

1. Wrzuć texturepacki (.zip lub foldery) do `.minecraft/resourcepacks`.
2. W grze otwórz mod: klawisz **K** (do zmiany w sterowaniu), albo przycisk **Pack Mixer** w lewym górnym rogu menu głównego i ekranu packów.
3. Za pierwszym razem pojawi się ekran **aktywacji licencji**: skopiuj swoje ID urządzenia, wyślij je autorowi, a otrzymany klucz `PMX1-...` wklej i kliknij Aktywuj.
4. Zakładki w GUI:
   - **Packi** - lista packów z ikoną, opisem i liczbą tekstur (bloki / przedmioty / moby / GUI). Przyciski: *Użyj wszystkiego* i *Wyczyść z miksu*.
   - **Bloki, Przedmioty, Moby, GUI** - po lewej lista tekstur z wyszukiwarką, po prawej karty z podglądem tej tekstury z **każdego packa, który ją ma**. Kliknij kartę, żeby wybrać źródło. Karta *Auto* = normalna kolejność packów. Na dole panel hurtowy: ustaw wszystkie wyświetlane tekstury z wybranego packa.
5. **Zastosuj i przeładuj** zapisuje miks (`config/packmixer/mix.json`) i przeładowuje zasoby.

Jak to działa: mod dokłada wirtualny pack o najwyższym priorytecie, który dla wybranych tekstur oddaje plik z wybranego packa (razem z animacjami `.mcmeta`), a resztę zostawia normalnej kolejności packów.

## Licencja

Licencja to klucz `PMX1-...` podpisany kluczem prywatnym (Ed25519) i przypisany do jednego urządzenia. Zapisuje się w `config/packmixer/license.key`. Mod zawiera tylko klucz publiczny (`assets/packmixer/license_public.key`), więc sam nie potrafi wystawiać licencji. Generator jest w osobnym, prywatnym folderze `packmixer-licencje`.

Ograniczenia: licencja offline jest jednorazowa przez przypisanie do urządzenia. Pełne unieważnianie kluczy wymaga serwera aktywacji. Moda w Javie da się zdekompilować, więc to ochrona przed kopiowaniem, nie przed zdeterminowanym crackerem (pomaga obfuskacja, np. ProGuard).

## Struktura

```
src/main/java/pl/packmixer/
  PackMixerClient.java        wejście, klawisz K, przyciski w menu
  license/                    LicenseCodec (weryfikacja), LicenseManager
  config/MixConfig.java       zapis miksu
  pack/PackIndex.java         skan packów w tle
  pack/MixerResourcePack.java wirtualny pack z wybranymi teksturami
  mixin/ResourcePackManagerMixin.java  dokłada pack-mikser
  gui/                        LicenseScreen, PackMixerScreen, Ui, TexCache
```
