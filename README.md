[Uploading README.md…]()
# MishaLang — язык программирования с кастомным 2D/3D рендерингом

> Смесь Python и Java • Своя VM (MVM) • Собственный софт-рендер без OpenGL • MVM v1 `Lite 256M`

Это первая работа автора. Язык покрывает 20 разделов спеки: переменные, типы, строки, массивы, лямбды, классы, `match`, память `#NoGC/#ManualMem`, 2D/3D математика и рендер.

**Репозиторий:** `https://github.com/narycvmisa-arch/MishaLang`

---

## ✨ Что умеет

- **Синтаксис Python-like:** `var`, `if/elif/else`, `for :`, `while`, `lambda`, `(x,y)-> x+y`, `match` → `switch`
- **Типы:** `str/flt/dbl/chr/bool/lng`, `Box<T>`, `List/Map/Set`
- **20 встроенных групп:** `math` (`sum/sub/pow/sqrt/lerp/clamp/map`), `string` (`lower/upper/split/replace`), `array` (`append/sort/unique`), `random` (`randint/randfloat/choice/shuffle`), `file/http` (`read/write/fetch/httpPost`), `memory` (`alloc/free/gc/meminfo`)
- **2D/3D:** `libs.MathD2` / `MathD3` — `MathD3`, `Camera3D`, `createWindow2D(w,h)`, `clear2D/3D`, `Rect2D`, `Text2D`, `swapBuffers2D`, `Cube/CubeEx/Sphere` — софт-рендер на `BufferedImage` + `win.pixels: int[]`
- **VM:** стековая `MVM (Misha VM)` — `.mih/.mixa → .mvm` байткод (`Bytecode.java`), 500k шагов лимит, `MishaIDE` / `MishaLiteIDE` (256M heap)

### Демо
```
48x16 | Lite 256M | FPS:21
| Красный куб 2.6 слева (HQ заливка) | Зелёный куб 2.6 справа | Синяя сфера 1.6 сверху |
| Зелёный 2D бар двигается по lerp |
```

## 📁 Структура

```
compiler/src/main/java/compiler/BootstrapCompiler.java  # .mih/.mixa → .mvm / .java
mvm/src/main/java/mvm/MVM.java + Bytecode.java           # VM, рендер
runtime/src/main/java/misha/Runtime.java                 # Runtime для transpiled Java
ide/src/main/java/ide/MishaLiteIDE.java / MishaIDE.java # IDE
libs/Math.mih, MathD2.mih, MathD3.mih, Random.mih
examples/mini_app.mixa        # 40x12 демо
examples/mini_app_fixed.mixa  # 48x16 HQ фикс (раздельные кубы)
examples/mini_2d3d_fps.mix
```

## 🔧 Требования

- Java 21+ (LTS), `javac` 21.0.10
- 256M heap достаточно (`-Xmx256m`)

## 🚀 Сборка

```powershell
javac -cp mvm/target --release 21 -d mvm/target mvm/src/main/java/mvm/Bytecode.java mvm/src/main/java/mvm/MVM.java compiler/src/main/java/compiler/BootstrapCompiler.java
javac -cp mvm/target;ide/target --release 21 -d ide/target ide/src/main/java/ide/MishaLiteIDE.java ide/src/main/java/ide/MishaIDE.java
```

## ▶️ Запуск

**Компиляция:**
```powershell
java -cp mvm/target compiler.BootstrapCompiler examples/mini_app.mixa -o out.mvm
java -cp mvm/target compiler.BootstrapCompiler examples/mini_app.mixa -o out.java -target java
```

**Выполнение (MVM):**
```powershell
java -cp mvm/target mvm.MVM examples/mini_app_fixed.mvm
java -cp mvm/target mvm.MVM examples/mini_app.mvm
java -cp mvm/target mvm.MVM examples/test_rect.mvm
```

**IDE (для слабых ПК):**
```powershell
java -cp mvm/target;ide/target ide.MishaLiteIDE  # 900x600, -Xmx256m, F5=запуск
java -cp mvm/target;ide/target;mvm/target ide.MishaIDE
```

## 📝 Пример .mixa (собственный рендеринг)

```java
import libs.*

var win = createWindow2D(48, 16)
lambda myLerp(a,b,t){ ret a+(b-a)*t }

var f=0
while win.shouldClose()==false {
  clear2D(win,0); clear3D(0)

  // собственный рендеринг через win.pixels + setAt (синхронизируется с BufferedImage)
  for py:[0,1] { for px:[0,1,2,3,4,5,6,7,8,9] {
    if px<win.w && py<win.h {
      var idx=py*win.w+px
      var col=0x101030 + px*0x080000
      setAt(win.pixels, idx, col)
    }
  }}

  Rect2D(win, myLerp(0,42,(f%20)/20.0), 6, 5,2,2)

  // HQ 3D — раздельно
  Cube(-2.6,0.3,3.2,2.6,0xFF4040)
  CubeEx(2.6,0.3,3.2,2.6,2.6,2.6,0x40FF50)
  Sphere(0,2.4,3.5,1.6,0x4090FF)

  Text2D(win,"FPS:{fps} F:{frames}",0,0)
  swapBuffers2D(win)
  sleep(30); f=f+1
}
```

## 🛠 Фиксы v1.1 (наш патч)

- `MVM.java:861` лишний `}` → `orphaned case`
- `Bytecode.java:140` `PUSH false` → String → `==false` всегда false → `while shouldClose` не входил
- `BootstrapCompiler:1230` hex `0xFF0000` → `0`, `MVM:toLong` hex
- `CubeEx/Sphere` брали `wi/hi` вместо `imgW/imgH` → рисовали в углу
- `MVM:796` `Math.max(640,480)` → огромная рамка, `scale 12→16` + `fit=min(pw/imgW,ph/imgH)` для фуллскрина
- `MVM:390` `int[] win.pixels` без `len/getAt/setAt` и без `synchronized(img)` → зависание при maximize (EDT блокировался)
- `IDE:doRun` → `new Thread("Misha-MVM")` — IDE не виснет
- Кубы: `scale 160→220/95`, `stroke 2.4`, заливка грани — HQ, разнесены `-2.6 / +2.6`


## 📄 Лицензия

MIT — `LICENSE` (c) 2026 MainStreeeetss

---
**Phenom II X4 4x3.4GHz / 4GB DDR3 / RX550 / LTSC x64 — полёт нормальный.**
