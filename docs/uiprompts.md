# UI Rework Prompts

---

## Prompt 1 — Nav Bar & Navigation
**Mode: Plan first, then execute**

Before making any changes, read the existing nav bar and navigation implementation thoroughly.

Replace the existing nav bar with a pill-shaped bar at the bottom of the screen. It should contain the following items in order left to right: Settings, Dashboard, Daily Detail, Questions, History, Devices.

Each item should display as an icon only with no labels. The icons are: Settings = settings/cog icon, Dashboard = home icon, Daily Detail = document icon, Questions = checklist icon, History = graph icon. The Devices icon should keep its existing icon, all dynamic features, and styling completely unchanged.

The four centre icons (Dashboard, Daily Detail, Questions, History) should have a sliding pill/highlight overlay that animates horizontally between them when switching pages. Settings and Devices act as independent tap buttons — the highlight does not slide to them.

A vertical divider line should sit on the right of Settings and the left of Devices. These lines should not touch the top or bottom of the pill.

The nav bar auto-hides when the user scrolls down and reappears when they scroll up. It is hidden entirely on the Settings and Devices pages — those pages should use their own return arrow to navigate back to the last accessed page.

Remove the Daily Detail link button from the Dashboard page.

**Navigation**

The Android system back-swipe and back button should navigate the user back through pages in the order they were visited. Each page's state and layout should be preserved as the user left it, but only for the duration of the current app session — all state resets when the app is closed. Remove in-app side-swipe gestures as a method of navigating between pages. Pages should only be navigated to via the nav bar icons or the system back gesture.

---

## Prompt 2 — Top Bar & Date Selection System
**Mode: Plan first, then execute**

Before making any changes, read the existing implementations of each data page, any existing top bar component, the date tracking systems on each page, the Questions A/B toggle, and all existing page action icons to ensure nothing is lost.

Build a reusable Top Bar component and apply it to all four data pages: Dashboard, Daily Detail, Questions, and History. Settings and Devices are excluded — they keep their existing page titles and back arrows unchanged.

**Top Bar Layout**

The Top Bar is underlined with a horizontal line that runs from left to right but does not touch the screen edges. The margin of this line should match the content margin of the page below it.

The bar has three zones:

- **Left** — the selected date, left-aligned to the start of the underline. Tapping it opens a day picker. On app load it defaults to the current date.
- **Centre** — a page title or in-page navigation element, vertically inline with the date. This is passed in as a parameter.
- **Right** — one or more page action icons, right-aligned to the end of the underline. These are passed in as parameters and are page-specific.

The centre content and right icons for each page are:
- Dashboard — centre: page title, right: pen/edit icon
- Daily Detail — centre: page title, right: pen/edit icon
- Questions — centre: existing A/B toggle restyled to match the Top Bar design language, right: existing edit icon
- History — centre: page title, right: + icon to add a metric

**Date System**

Each data page maintains its own independent date tracking. Daily Detail should gain a date tracking system matching the current setup used by the other pages. If a user follows a metric link from Dashboard to Daily Detail, the currently selected Dashboard date carries over. Otherwise all page dates are fully independent of each other.

**Implementation Note**

Build the Top Bar as a single reusable component that accepts centre content and right icon(s) as parameters so it can be used consistently across all four pages without duplication.

---

## Prompt 3 — Dashboard Redesign
**Mode: Plan first, then execute**

Before making any changes, read the existing Dashboard implementation thoroughly.

**Top Bar**

The Dashboard uses the shared Top Bar component. It has the page title in the centre and a pen/edit icon on the right. The Top Bar is underlined with a horizontal line that does not touch the screen edges — the margin matches the content margin of the page. The left side shows the selected date which opens a day picker when tapped and defaults to today on app load. Each page tracks its own date independently.

**Widget Grid**

The dashboard content should be widget-based. There are two widget sizes:
- 1x1 — the size of a current metric widget
- 1x2 — the size of the full-width bar displays such as habits or lifestyle question bars

Every metric should have both a 1x1 and 1x2 widget. Additional widget types (device status, device battery, reminders, combination metric widgets) will be designed later — for now use the existing metric tile designs. Design the widget system so that adding new widget types in future only requires adding a new self-contained composable with no changes to the grid logic.

Widgets should sit inside the same margin as the Top Bar underline and align to it.

**Performance**

Implement the widget grid using a virtualised/lazy grid so only visible widgets render. Store the widget layout config in a lightweight local database (e.g. Room) and save changes incrementally as edits are made. Each widget should be a self-contained composable that manages its own data fetch independently, subscribing only to the data it needs. Do not pass all metric data down from a parent.

**Edit Mode**

Tapping the pen icon in the top right enters edit mode:
- A plus icon appears to the left of the pen icon, opening a dropdown catalogue of available widgets to add
- The pen icon is replaced with a tick icon — tapping it exits edit mode
- All changes save automatically as they are made
- Widgets wiggle slightly to indicate edit mode
- Tapping and holding a widget allows it to be dragged and repositioned, similar to rearranging apps on a phone home screen — the widget being dragged should be clearly highlighted as selected
- A red X appears in the top right corner of each widget — tapping it removes that widget

**Widget Navigation**

When a metric widget is tapped outside of edit mode it should open the Daily Detail page, auto-scroll to the matching category tile, and expand it. The currently selected date on the Dashboard should carry over to Daily Detail when following this link.

If a metric widget represents data that requires user input and that input has not been filled in for the selected date, tapping it should navigate the user to the relevant input location (e.g. Questions page or weight popup) instead.

---

## Prompt 4 — Daily Detail Page Redesign
**Mode: Plan first, then execute**

1
---

## Prompt 5 — History Page Redesign
**Mode: Plan first, then execute**

Before making any changes, read the existing History page implementation thoroughly.

**Top Bar**

The History page uses the shared Top Bar component. It has the page title in the centre and a + icon on the right to add a metric to the table. The Top Bar is underlined with a horizontal line that does not touch the screen edges — the margin matches the content margin of the page. The left side shows the selected date which opens a day picker when tapped and defaults to today on app load. Each page tracks its own date independently.

**Duration & Regularity**

Move the existing duration selector into a dropdown. Add a second dropdown for data point regularity with the options: Hourly, Daily, Weekly, Monthly.

**Selected Value Tile**

The tile below the graph shows the value for the currently selected data point. The display is context-aware per metric type — averages for metrics like HR and glucose, a single value for metrics like weight, and totals for metrics like steps and calories.

Swiping left or right on the tile moves through data points one at a time, snapping to each point, in the unit matching the current regularity — hours in Hourly, days in Daily, weeks in Weekly, months in Monthly. Swiping past the first or last point of a period rolls over to the adjacent period and updates the top bar date accordingly.

Tapping the tile expands it into a dropdown table showing every data point for the selected regularity and duration. The table font size should be noticeably smaller than the selected value displayed at the top of the tile.

**Graph**

If there is no data after a certain point the graph line should end at the last available data point rather than extending a flat line to the end of the chart.

The user can drag the vertical intersection line left and right across the graph to move through data points. When released it should snap to the nearest data point like a magnet.

The graph scrubber and the tile swipe are fully synced — moving either one updates both the displayed tile value and the graph line position simultaneously.

---

## Prompt 6 — Questions Page Updates
**Mode: Normal**

Before making any changes, read the existing Questions page implementation thoroughly.

Make the following two changes to the Questions page:

**1. Remove starring from edit menu**
Remove the starring feature from the edit menu entirely.

**2. Top Bar**
The Questions page uses the shared Top Bar component. It has the existing A/B toggle restyled to sit in the centre zone of the Top Bar, matching its design language, and the existing edit icon on the right. The Top Bar is underlined with a horizontal line that does not touch the screen edges — the margin matches the content margin of the page. The left side shows the selected date which opens a day picker when tapped and defaults to today on app load. Each page tracks its own date independently.

Ensure the A/B toggle retains all its existing functionality — only its visual style should change to fit the Top Bar.

---

## Prompt 7 — Settings Page Redesign
**Mode: Plan first, then execute**

Before making any changes, read the existing Settings page implementation thoroughly to ensure no existing settings or functionality are lost.

Settings keeps its existing page title and back arrow. It does not use the shared Top Bar component. No edit mode is required — tile order and visibility are fixed.

**Layout**

Replace the current Settings layout with collapsible category tiles. In their collapsed state tiles show a summary. When tapped they expand to show their full contents. Multiple tiles can be open at the same time.

The page is divided into three sections, each containing their own set of category tiles:

- **Settings** — all current user-facing settings, organised into logical category tiles
- **Experimental** — experimental features
- **Developer** — developer options

All three sections are always visible to all users. When organising existing settings into category tiles, do not remove, rename, or change the behaviour of any existing setting.