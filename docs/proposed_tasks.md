# Proposed Maintenance Tasks

## 1. Fix typo in README
- **Issue:** The word "theoratically" is misspelled in the Function section of the README.【F:README.md†L7】
- **Impact:** The typo reduces the polish of the public-facing documentation.
- **Resolution:** Spelling corrected to "theoretically" and the gesture instructions were clarified alongside the fix.

## 2. Resolve Zabbix panel visibility bug
- **Issue:** The weather panel layout declares an inner `LinearLayout` with the ID `zabbix_layout`, which conflicts with the Zabbix include defined in `activity_main.xml`. As a result, `findViewById(R.id.zabbix_layout)` in `MainActivity` points to the weather panel child instead of the dedicated Zabbix panel, so swipe gestures toggle the wrong view.【F:app/src/main/res/layout/weather_layout.xml†L63-L96】【F:app/src/main/res/layout/activity_main.xml†L68-L75】【F:app/src/main/java/com/example/clockapp/MainActivity.java†L84-L150】
- **Impact:** The intended Zabbix panel never becomes visible, breaking one of the core dashboard features.
- **Resolution:** Renamed the nested weather layout to `weather_metrics_layout`, allowing the activity to correctly reference the actual Zabbix container.

## 3. Align gesture comments with behavior
- **Issue:** The inline comments in `MainActivity`'s swipe handler describe the opposite behavior of what the code actually does (right swipe shows Zabbix, left swipe shows weather).【F:app/src/main/java/com/example/clockapp/MainActivity.java†L124-L129】
- **Impact:** Future maintainers may implement gesture changes incorrectly because the documentation contradicts the implementation.
- **Resolution:** Updated the comments to reflect the real gesture behavior and documented the mapping in the README.

## 4. Add coverage for weather parsing
- **Issue:** There are no unit or instrumentation tests in the project, so critical behavior like `WeatherService.FetchWeatherTask`'s JSON parsing is unverified.【9c5059†L1-L6】【F:app/src/main/java/com/example/clockapp/WeatherService.java†L83-L143】
- **Impact:** Regressions in weather parsing (e.g., handling API errors or missing fields) could ship unnoticed, degrading the display.
- **Resolution:** Extracted the JSON parsing into a reusable helper and covered success and failure paths with JVM unit tests.
