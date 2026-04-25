# Plan funkce toolbar widgetu

## Cíl

Přidat volitelný vstup do hlavního IDE toolbaru pro Project Switcher, podobně jako funguje výchozí IntelliJ Platform **Project Widget**. Toolbar vstup má sloužit pro rychlý přístup k proskenovaným projektům, zatímco stávající tool window zůstane plnohodnotná správa.

Toto je pouze plán budoucí funkce. Implementace zatím nebyla provedena.

## Aktuální stav pluginu

Plugin má hlavní UI jako tool window:

- Tool window id: `Projects`
- Factory: `cz.atomsoft.projectswitcher.projectswitcherplugin.MyToolWindowFactory`
- Registrace: `src/main/resources/META-INF/plugin.xml`
- Globální nastavení je aplikační, přes `ProjectSwitcherSettings`
- Panel podporuje:
  - nakonfigurované kořenové složky pro scan
  - detekci projektů s deduplikací vnořených Gradle/Maven/IDEA projektů
  - zobrazení aktivní Git branche
  - projektové ikony odpovídající IDEA recent-project stylu, pokud jsou dostupné
  - řazení abecedně a podle posledního použití
  - flat a tree režim
  - persistovaný search query
  - persistovaný stav rozbalení stromu a scroll
  - zvýraznění aktivního projektu
  - otevření v aktuálním okně i otevření v novém okně

Toolbar funkce má znovu použít existující logiku a nemá duplikovat scan projektů, řazení, ikony, branche ani detekci aktivního projektu.

## Zjištění z platformy

Lokální IntelliJ Platform SDK použité projektem je IDEA 2025.2.4. Kontrola bundlovaných platformních souborů potvrdila, že nový hlavní toolbar je sestavený přes action groups.

Relevantní deklarace nalezená v IDEA 2025.2.4:

```xml
<group id="MainToolbarNewUI">
  <group id="MainToolbarLeft" searchable="false">
    <action id="main.toolbar.Project" class="com.intellij.openapi.wm.impl.headertoolbar.ProjectToolbarWidgetAction"/>
    <group id="MainToolbarGeneralActionsGroup" searchable="false">
      <separator/>
    </group>
  </group>
  <group id="MainToolbarCenter" searchable="false">
    <action id="main.toolbar.Filename" class="com.intellij.openapi.wm.impl.headertoolbar.FilenameToolbarWidgetAction"/>
  </group>
  <group id="MainToolbarRight" searchable="false">
    <reference ref="SearchEverywhere"/>
    <reference ref="SettingsEntryPoint"/>
  </group>
</group>
```

Výchozí project widget je tedy:

- Action id: `main.toolbar.Project`
- Třída: `com.intellij.openapi.wm.impl.headertoolbar.ProjectToolbarWidgetAction`
- Toolbar group: `MainToolbarLeft`

Stejná toolbar struktura byla nalezena i v lokálně instalovaném Android Studio Canary:

- Cesta z konfigurace Android Studia: `C:\PJazyky\_IDE\android-studio-canary`
- Kontrolovaný soubor: `lib/app.jar!/idea/PlatformActions.xml`
- Obsahuje `MainToolbarNewUI`, `MainToolbarLeft` a `main.toolbar.Project`

Funkce je tedy realistická pro IDEA i Android Studio, pokud zůstaneme u IntelliJ Platform API dostupného přes `com.intellij.modules.platform`.

## Užitečná platformní API

V lokálním IDEA 2025.2.4 SDK existují tyto relevantní třídy:

- `com.intellij.openapi.actionSystem.ex.CustomComponentAction`
  - Umožňuje akci vykreslit vlastní Swing komponentu v toolbaru.
  - Obsahuje metody `createCustomComponent(Presentation, String)` a `updateCustomComponent(JComponent, Presentation)`.

- `com.intellij.openapi.actionSystem.ex.ToolbarLabelAction`
  - `DumbAwareAction`, která implementuje `CustomComponentAction`.
  - Vhodná pro jednoduché label-like toolbar položky.

- `com.intellij.openapi.roots.ui.configuration.actions.IconWithTextAction`
  - Implementuje `CustomComponentAction`.
  - Používá se u některých platformních toolbar/popup akcí, které potřebují ikonu a text.

- `com.intellij.openapi.wm.impl.headertoolbar.ProjectToolbarWidgetAction`
  - Výchozí implementace project widgetu.
  - Rozšiřuje interní expandable combo action a vytváří popup.
  - Je užitečná jako reference chování, ale přímé závislosti na interních `impl` třídách je lepší se vyhnout.

Výchozí project widget používá také:

- `ProjectWidget.Actions`
  - Action group použitá uvnitř popupu výchozího Project Widgetu.
  - Přidávají se tam existující IDE akce jako `OpenFile`, `NewProject` nebo `ProjectFromVersionControl`.

Z toho vycházejí dvě použitelné integrační cesty:

1. Přidat vlastní toolbar action do `MainToolbarLeft`.
2. Přidat akci do popupu výchozího widgetu přes `ProjectWidget.Actions`.

## Doporučený produktový tvar

Nevkládat celý existující postranní panel přímo do toolbaru.

Toolbar má omezené místo, často se aktualizuje a očekává se od něj kompaktní a stabilní UI. Celý panel se search polem, řazením, přepínačem tree/flat, settings, refreshem a scroll stavem by byl v toolbaru těžkopádný a nepůsobil by jako nativní IDE chování.

Doporučený tvar:

- Stávající tool window zůstane plná správa.
- Toolbar widget bude kompaktní launcher nebo stavový indikátor.
- Po kliknutí otevře popup se seznamem proskenovaných projektů.
- Bude používat stejný datový model a renderovací pravidla jako tool window.

Samotný toolbar widget by měl zobrazovat jen nejdůležitější informaci:

- projektovou ikonu
- aktuální název projektu nebo stabilní label typu `Projects`
- volitelně aktuální Git branch
- dropdown šipku/chevron v nativním toolbar stylu

Popup může obsahovat bohatší interakci:

- filtrovaný seznam projektů
- název projektu a branch
- zvýraznění aktuálního projektu
- right-click nebo sekundární akci pro otevření v novém okně, pokud to bude prakticky fungovat
- volitelný vstup do nastavení
- volitelnou akci pro otevření tool window

Search by měl pravděpodobně být v popupu, ne trvale přímo v toolbaru.

## Proč search a settings nedávat přímo do toolbaru

Technicky je search field v toolbaru možný přes `CustomComponentAction`, protože toolbar může hostovat vlastní Swing komponenty. Pro tento plugin to ale není doporučené:

- Zabere příliš velkou šířku toolbaru.
- Může přebírat focus editoru.
- Může hůř fungovat s layoutem a customizací toolbaru.
- Neodpovídá patternu výchozího Project Widgetu.
- Duplikovalo by to existující persistovaný search v tool window.

Lepší rozdělení:

- Toolbar: kompaktní launcher nebo zobrazení aktuálního stavu.
- Popup: rychlé hledání nebo speed-search.
- Tool window: persistovaný plný search/filter/sort/tree/settings zážitek.

Settings by také neměly být samostatný toolbar control. Vhodnější je položka v hlavičce/patičce popupu, tool window nebo standardní Settings dialog.

## Možnosti implementace

### Varianta A: Samostatný toolbar widget

Registrovat novou akci pod `MainToolbarLeft`, pravděpodobně poblíž `main.toolbar.Project`.

Příklad tvaru registrace:

```xml
<actions>
  <action id="BranchProjectSwitcher.ToolbarWidget"
          class="cz.atomsoft.projectswitcher.projectswitcherplugin.ToolbarProjectSwitcherAction"
          text="Project Switcher"
          icon="AllIcons.Nodes.Project">
    <add-to-group group-id="MainToolbarLeft"
                  anchor="after"
                  relative-to-action="main.toolbar.Project"/>
  </action>
</actions>
```

Možní implementační kandidáti:

- `DumbAwareAction`, `CustomComponentAction`
- nebo běžná `DumbAwareAction` s toolbar presentation, pokud bude stačit jednoduché tlačítko

Chování po kliknutí:

- Obnovit nebo načíst cachovaný seznam projektů.
- Otevřít `JBPopup`, `ListPopup` nebo vlastní popup.
- Primární akce otevře projekt v aktuálním okně.
- Tam, kde to bude praktické, nabídnout "Open in New Window".

Výhody:

- Nejviditelnější a nejrychlejší přístup.
- Nezávislé na výchozím Project Widgetu.
- Může zobrazit branch přímo v toolbaru.
- Může se později rozšířit na bohatší popup.

Nevýhody:

- Přidá další toolbar položku poblíž už existujícího Project Widgetu.
- Vyžaduje opatrnou práci se šířkou.
- Musí se ověřit v IDEA i Android Studio new UI.

### Varianta B: Akce uvnitř výchozího Project Widget popupu

Registrovat akci do `ProjectWidget.Actions`.

Příklad tvaru:

```xml
<actions>
  <action id="BranchProjectSwitcher.OpenPopup"
          class="cz.atomsoft.projectswitcher.projectswitcherplugin.OpenBranchProjectSwitcherPopupAction"
          text="Project Switcher"
          icon="AllIcons.Nodes.Project">
    <add-to-group group-id="ProjectWidget.Actions"/>
  </action>
</actions>
```

Výhody:

- Velmi nativní.
- Minimální nová UI plocha.
- Menší riziko přeplněného toolbaru.
- Používá existující vstupní bod výchozího Project Widgetu.

Nevýhody:

- Je o klik hlouběji.
- Neumí zobrazit stav projektu/branche přímo v toolbaru.
- Je méně objevitelné, pokud uživatel očekává vlastní vstup pluginu.

### Varianta C: Nahradit nebo skrýt výchozí Project Widget

Toto není doporučené.

Platformní widget je core komponenta IDE. Nahrazovat ho by vytvářelo zbytečné kompatibilitní a UX riziko, obzvlášť napříč IDEA a Android Studio.

## Doporučená implementační strategie

Začít variantou A, ale variantu B nechat jako fallback nebo sekundární integraci.

Doporučený postup:

1. Vytáhnout sdílený model seznamu projektů z tool window.
   - Vstup: globální settings, scan roots, aktuální search query, sort mode, view mode.
   - Výstup: list/tree renderovatelných project entries.
   - Musí zachovat současné chování scanneru a detekci aktivního projektu.

2. Přidat `ToolbarProjectSwitcherAction`.
   - Implementovat `DumbAwareAction`.
   - `CustomComponentAction` použít jen pokud bude potřeba nativně vypadající custom komponenta.
   - Komponenta musí zůstat kompaktní.
   - Update logika musí být levná a nesmí při každém toolbar update skenovat disk.

3. Přidat popup pro přepínání projektů.
   - Preferovat `JBPopupFactory` nebo action-based `ListPopup`.
   - Použít speed-search nebo malé search pole nahoře.
   - Renderovat projektovou ikonu, název a branch.
   - Zvýraznit aktivní projekt.
   - Pokročilé controls držet minimální.

4. Přidat přístup do nastavení.
   - Položka v hlavičce/patičce popupu: `Configure Scan Folders...`
   - Volitelná položka: `Open Project Switcher Tool Window`

5. Registrovat action v `plugin.xml`.
   - Primárně `MainToolbarLeft`, za `main.toolbar.Project`.
   - Ověřit viditelnost a customizaci v new UI.
   - Zvážit i položku v `ProjectWidget.Actions`.

6. Ověřit v obou IDE.
   - IDEA přes `runIde`.
   - Android Studio, pokud bude dostupný odpovídající lokální IDE target nebo konfigurace Gradle IntelliJ Platform test IDE.

## Znovupoužití dat a stavu

Toolbar funkce nemá zavádět oddělené nastavení pro scan roots, recent use, aktivní projekt, ikony nebo branche.

Znovu použít:

- `ProjectSwitcherSettings`
- `ProjectScanner`
- provider projektových ikon
- logiku timestampů z recent projektů
- matching aktivního projektu podle path
- search/filter logiku tam, kde to dává smysl

Popup lokální stav může být dočasný. Persistovaný stav má zůstat existující aplikační stav, pokud funkce výslovně nepotřebuje vlastní nastavení, například:

- zobrazovat toolbar widget: ano/ne
- režim toolbar labelu: název projektu, branch, projekt plus branch, nebo statický label

Jakékoliv nové nastavení má být také aplikační, ne project-level.

## Výkonové poznámky

Toolbar action `update()` se může spouštět často. Nesmí:

- skenovat nakonfigurované složky
- dělat blokující průchod filesystemem
- spouštět Git příkazy
- alokovat velký Swing tree/list

Doporučené chování:

- Toolbar update omezit na aktuální projekt/název/path/branch, pokud už jsou dostupné.
- Používat cachované výsledky scanu ze service.
- Refresh scanu dělat jen při otevření popupu, kliknutí na refresh, změně settings nebo background debounce.
- Pokud bude nutné skenovat při otevření popupu, zobrazit hned cachované výsledky a aktualizovat asynchronně.

## Kompatibilita

Používat stabilní public API, kde je to možné:

- `AnAction`
- `DumbAwareAction`
- `CustomComponentAction`
- `JBPopupFactory`
- `DefaultActionGroup`
- `ActionManager`

Vyhnout se přímé závislosti na interních třídách:

- `com.intellij.openapi.wm.impl.headertoolbar.*`

Tyto třídy jsou užitečné jako reference, ale nejsou ideální jako stabilní plugin API.

Group id `MainToolbarLeft` a `ProjectWidget.Actions` existují v kontrolované lokální IDEA i Android Studio instalaci. Před release je potřeba ověřit je proti deklarovanému podporovanému rozsahu IDE.

## UX detaily, které zachovat

Toolbar popup by měl zachovat vizuální rozhodnutí ze současného tool window:

- nativní projektové ikony s barevnými iniciálami, pokud jsou dostupné
- Git branch na sekundárním řádku s IDE branch ikonou
- zvýraznění aktivního projektu
- hover highlight odpovídající nativnímu IDE chování
- primární klik otevře v aktuálním okně
- "Open in New Window" jako sekundární akce

Samotná toolbar komponenta se nemá stát malým control panelem.

## Otevřené otázky

- Má být toolbar widget zapnutý automaticky, nebo volitelně?
- Má být přímo za výchozím Project Widgetem, nebo spíš v oblasti `MainToolbarGeneralActionsGroup`?
- Má popup respektovat persistovaný search z tool window, nebo má mít toolbar popup transientní hledání?
- Má popup zobrazovat jen flat list, nebo také tree režim?
- Má popup podporovat right-click, nebo má být "Open in New Window" viditelná akce/modifikátor?
- Má se položka pluginu přidat i do výchozího Project Widget popupu, i když bude existovat samostatný toolbar widget?

## Navržený první milestone

Nejmenší užitečný milestone:

- Přidat toolbar tlačítko/action do `MainToolbarLeft`.
- Po kliknutí otevřít nativní popup s flat filtrovaným seznamem projektů.
- Zobrazit projektovou ikonu, název a branch.
- Primární klik otevře projekt v aktuálním okně.
- Dole přidat akci do nastavení.
- Znovu použít globální settings a scanner logiku.

Tree mode, persistovaný popup search a pokročilejší controls mohou přijít později, pokud se kompaktní popup osvědčí.
