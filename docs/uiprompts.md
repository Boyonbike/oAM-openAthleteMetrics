## Prompt 8 — UI Fixes
**Mode: Plan first, then execute**

Before making any changes, read the implementations of DataPageTopBar, SettingsScreen, DevicesScreen, QuestionsScreen, HistoryScreen, DailyDetailScreen, and the widget composables thoroughly.

**Fix 1 — Top Bar vertical alignment**

The shared DataPageTopBar component sits too close to the top of the screen. Increase the top padding/margin so it has the same visual breathing room as the TopAppBar used on the Settings and Devices pages.

Then update the Settings and Devices pages to match the shared Top Bar's styling: add the same horizontal underline beneath their title row, and match the font size and weight of their title text to the DataPageTopBar title style. The goal is that all six pages feel visually consistent at the top.

**Fix 2 — Widget catalogue size selector**

In the widget catalogue sheet, the current Small/Wide size selector is unclear. Replace it with a toggle chip using the app's existing chip styling — one chip for Small and one for Wide, with the selected chip clearly highlighted. Only one can be selected at a time.

**Fix 3 — Page date swipe**

On all four data pages (Dashboard, Daily Detail, Questions, History), swiping left or right anywhere on the page content should change the selected date by one day — swipe left goes forward one day, swipe right goes back one day. This should not interfere with vertical scrolling or existing horizontal interactions within widgets or tiles.

**Fix 4 — History tile table toggle button**

On the History page, the selected value tile currently toggles the data table by tapping anywhere on the tile, making it impossible to scroll or swipe. Replace the tap-to-toggle behaviour with a dedicated show/hide button (e.g. a chevron icon) positioned clearly within the tile. Tapping the rest of the tile and swiping left/right should continue to work as before without toggling the table.

**Fix 5 — Questions edit menu reorder handle**

In the Questions page edit menu, replace the separate up and down arrow buttons on each question row with a single drag handle icon. The user should be able to long-press and drag the handle to reposition the question, using the standard Android drag-to-reorder pattern consistent with the rest of the app.

**Fix 6 — Daily Detail fake habits**

The Daily Detail page contains hardcoded placeholder habit and lifestyle data. Remove all hardcoded/fake data. The Lifestyle and Habits sections in Daily Detail should only show real data from the user's actual question responses already in the system. If a user has no habits or lifestyle questions defined, the section should show an appropriate empty state rather than placeholder content.

**Fix 7 — Habits widget starred dependency**

The habits widget currently only shows starred habits. Remove the starred filter — the widget should show all habits the user has created, regardless of starring status. The visual design and widget selection menu will be updated in a later prompt; for now just remove the starred dependency so real user habits are displayed.