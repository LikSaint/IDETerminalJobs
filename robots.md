# robots.md — заметки для ИИ-ассистентов

Карта проекта для агента, который продолжает разработку: что где лежит, как устроено, как собрать и на какие грабли уже наступали. Пользовательское описание — в `README.md`.

## Что это

Плагин IntelliJ Platform «Job Bar» (id `dev.jobbar`), написан на Kotlin. Это tool window со списком «джоб» — сохранённых shell-команд проекта: запуск, статус по выводу, консоль, варианты параметров. Основная целевая IDE — WebStorm 2025.3 на macOS.

## Структура

```
build.gradle.kts            сборка: IntelliJ Platform Gradle Plugin 2.19, Kotlin 2.2, JVM 21
                            sinceBuild/untilBuild = 253 / 253.*
                            `version = "x.y.z"` — ИСТОЧНИК версии плагина (patchPluginXml перезапишет plugin.xml)
gradle.properties           configuration cache, in-process Kotlin compiler
src/main/resources/META-INF/plugin.xml
                            projectService JobBarService + toolWindow "Job Bar" (anchor=bottom)
src/main/kotlin/dev/jobbar/
  JobModels.kt              модель данных: JobDefinition, JobVariant, JobRunMode, JobType, JobPresets
  JobBarService.kt          хранение джоб (PersistentStateComponent → .idea/jobBar.xml), CRUD, порядок, export/import XML
  JobRuntimeManager.kt      запуск процессов, консоли, вычисление статуса по паттернам, «Run all»
  JobBarToolWindowFactory.kt  UI tool window: строки джоб, индикатор статуса, тулбар, контекстное меню,
                            Add/Run/Stop/Restart/Edit/Import/Export actions (класс JobBarView и inner-классы actions)
  JobEditorDialog.kt        диалог создания/редактирования (вкладки General / Parameters / Status);
                            таблица вариантов — ToolbarDecorator над EditableModel (add/remove/↑/↓),
                            плюс SortVariantsAction (popup сортировок) и AutoSortAction (CheckboxAction)
```

## Ключевые понятия

### Модель (`JobModels.kt`)
- `JobDefinition` — сериализуется через `XmlSerializer` по public `var`. Новые поля **обязательно** с дефолтом, иначе не загрузятся старые `.idea/jobBar.xml` и экспортированные XML. После добавления поля проверь `copyDeep()`: вложенные списки нужно копировать вручную.
- `JobRunMode`: `ONE_SHOT` (команда завершается) / `TRACKED` (долгоживущий процесс).
- `variants` + `parameterName`: плейсхолдер `${parameterName}` в команде заменяется значением варианта. Если плейсхолдера нет, значение дописывается в конец (`resolveCommand`).
- `runAllVariants` — показывать пункт «All» в popup вариантов. Имеет смысл только для `ONE_SHOT`.
- `variantSortOrder` (enum `VariantSortOrder`, сравнение через `NaturalComparator`) + `autoSortVariants`. Порядок `variants` в модели — тот, в котором они показываются и запускаются. Сортировка применяется в `JobEditorDialog`: разово через кнопку, а при `autoSortVariants` — после каждой правки и ещё раз в `result()`. Runtime ничего не сортирует.
- `JobPresets.forType` — паттерны по умолчанию для каждого `JobType`.

### Runtime (`JobRuntimeManager.kt`)
- Project-level сервис. Состояние каждой джобы — в `JobRuntime`, в памяти, ключ — `job.id`.
- Запуск: `GeneralCommandLine` → `/bin/zsh -lc <command>` (на Windows — `cmd.exe /c`), `KillableColoredProcessHandler`, консоль из `TextConsoleBuilderFactory`.
- Публичные входы: `run(job, variant)`, `runAll(job)`, `stop`, `restart` (повторяет `runtime.lastVariant`), `openConsole`. Внутри всё сходится в `start(job, variant, continueSequence)`.
- Статусы (`JobRuntimeStatus`):
  - `STARTING` — tracked-процесс ждёт recovery-паттерна (лоадер);
  - `RUNNING` — one-shot выполняется (лоадер);
  - `READY` / `WARNING` — tracked работает без ошибок / с ошибками;
  - `SUCCESS` / `ERROR` — итог one-shot;
  - `STOPPED`, `IDLE`.
- `inspect()` проверяет каждый фрагмент вывода (без ANSI) по error / recovery / busy паттернам. В `scanBuffer` хранится хвост 1 КБ, чтобы ловить совпадения на стыке фрагментов. События сортируются по позиции в тексте.
  - TRACKED: события сразу меняют статус.
  - ONE_SHOT: события только выставляют `cycleHasError`. Итог считается в `processTerminated`: exit code ≠ 0 → ERROR; иначе `cycleHasError` → ERROR; иначе SUCCESS.
- «Run all»: `runtime.sequence` + `sequenceIndex` (флаг `inSequence`). `advanceSequence()` вызывается из `processTerminated`. При SUCCESS следующий вариант запускается через `invokeLater` с проверкой, что прогон не остановили. При ERROR или STOPPED прогон прекращается, в консоль пишется итог. Между вариантами статус держится `RUNNING`, чтобы лоадер не мигал.
- Об изменениях UI узнаёт через `addListener` / `fireChanged()`, который вызывается на EDT через `invokeLater`.

### UI (`JobBarToolWindowFactory.kt`)
- `JobBarView.refresh()` полностью пересобирает строки при любом изменении сервиса или runtime. Это просто и работает, но не держи в строках состояние между перерисовками.
- `statusIndicator(runtime)` — иконка статуса и tooltip (`AnimatedIcon.Default` — лоадер, `StatusDotIcon` — цветная точка).
- Кнопки строки: tracked в активном статусе или идущий «Run all» → Stop (+ Restart только для tracked); иначе → Run. `activeStatuses` — в companion.
- `RunJobAction`: если вариантов нет — сразу `run`, иначе popup `JBPopupFactory.createActionGroupPopup` (с «All» сверху, если включено).
- Меню ⋮ tool window (`gearActions`): Add Job, Import, Export.

### Хранение и обмен
- `JobBarService` — `@State(name = "JobBar", storages = [Storage("jobBar.xml")])`, project-level.
- Export / Import — `XmlSerializer.serialize(state)` / `deserialize`; формат совпадает с содержимым `jobBar.xml` (`<State><option name="jobs">…`). Import заменяет весь список после подтверждения.

## Сборка и проверка

```bash
JAVA_HOME=/Applications/WebStorm.app/Contents/jbr/Contents/Home \
  ~/.gradle/wrapper/dists/gradle-9.1.0-bin/*/gradle-9.1.0/bin/gradle \
  buildPlugin -PlocalIdePath=/Applications/WebStorm.app/Contents --offline
```
- Gradle wrapper в репозитории нет. Используется Gradle из `~/.gradle/wrapper/dists`.
- Результат: `build/distributions/JobBar-<version>.zip`. Пользователь ставит его через Install Plugin from Disk и перезапускает IDE.
- При каждом релизе поднимай версию в `build.gradle.kts`, а в `plugin.xml` ставь такую же, чтобы не путаться.
- Автотестов пока нет (testFramework подключён, но тестов нет). Проверка — ручная в IDE, поэтому чётко пиши пользователю, что проверено, а что нет.
- Логи IDE: `~/Library/Logs/JetBrains/WebStorm2025.3/idea.log`. Дампы зависших write actions: `.../bg-wa/`. Системные свойства работающей IDE: `jcmd <pid> VM.system_properties` (jcmd лежит в `…/WebStorm.app/Contents/jbr/Contents/Home/bin`).
- Исходников платформы локально нет. При необходимости классы берутся из `/Applications/WebStorm.app/Contents/lib/*.jar`; javap в JBR отсутствует.

## Грабли, на которые уже наступили

1. **Выбор файла при импорте (macOS).**
   - Стандартный `FileChooser` / `createFileChooser` на macOS прогоняет выбранный путь через VFS (`FileChooserDialogHelper.selectedFiles` → `refreshAndFindFileByPath`). Для файлов вне проекта, которые VFS ещё не видела, это давало ошибку «The IDE was unable to locate the following files», особенно сразу после старта IDE.
   - Поэтому импорт использует AWT `FileDialog` и читает файл напрямую через `Files.readString`, без VFS.
   - WebStorm запущен с `apple.awt.fileDialogForDirectories=true`, из-за чего обычный `FileDialog` выбирает только папки, а все файлы серые. Обязательно вызывать `JBRFileDialog.get(dialog)?.hints = JBRFileDialog.SELECT_FILES_HINT` (класс `com.jetbrains.JBRFileDialog` из `lib/util.jar`). `FilenameFilter` не использовать: фильтр задаётся через `setFileFilterExtensions`, а расширение дополнительно проверяется после выбора.
2. **Exit code 0 при ошибке.** `yarn run` и подобные утилиты печатают ошибку и завершаются с кодом 0. Поэтому итог one-shot определяется ещё и по паттернам. Не возвращай логику «только exit code».
3. **Restart** должен передавать `lastVariant`, иначе плейсхолдер `${…}` останется в команде неподставленным.
4. `actionPerformed` выполняется на EDT под write-intent lock. Долгие модальные вещи на EDT задерживают фоновые write actions IDE (в `idea.log` появляется «Cannot execute background write action in 10 seconds»). Это не фатально, но тяжёлую работу на EDT не добавляй.

## Соглашения

- Код и комментарии — на английском, общение с пользователем — на русском.
- Используй нативные компоненты платформы (`JBLabel`, `JBUI`, `AllIcons`, `DumbAwareAction`, `Messages`), без собственных стилей и цветов.
- Комментарии редкие и только «почему», в стиле существующего кода.
- Коммиты — в `main` репозитория `git@github.com:LikSaint/IDETerminalJobs.git` (remote по SSH; HTTPS-учётки нет).
