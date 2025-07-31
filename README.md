# BusFlow

BusFlow is an Android application designed to replace printed duty sheets for bus drivers. It presents each driver’s roster in an easy-to-read multi-color timeline or paginated table view, helps them keep track of timing points, and provides a live map view to monitor on-time performance and nearby buses.

---

## Table of Contents

- [Why BusFlow?](#why-busflow)  
- [Key Features](#key-features)  
- [Architecture & Modules](#architecture--modules)  
- [Getting Started](#getting-started)  
  - [Prerequisites](#prerequisites)  
  - [Installation](#installation)  
- [Usage](#usage)  
- [Contributing](#contributing)  
- [License](#license)  

---

## Why BusFlow?

Printed duty sheets are often hard to read and require drivers to memorize multiple timing points. BusFlow solves these pain points by:

- **Replacing paper rosters** with a digital schedule that’s legible at a glance  
- **Visualizing duties** in color-coded timelines or tables  
- **Highlighting timing points** so drivers always know whether they’re early, on time, or running late  
- **Providing a live map** to show the route, current position, and nearby buses with the same destination  

---

## Key Features

- **Multi-Color Timeline**  
  Color-coded segments represent each duty, break, or relief period for intuitive at-a-glance status.
  
- **Paginated Table View**  
  A compact, scrollable table mode with pagination for drivers who prefer tabular schedules.
  
- **Real-Time Schedule Adherence**  
  In MapActivity, timing points are compared against actual vs. scheduled times to indicate “Ahead”, “On Time”, or “Behind.”
  
- **Live Map Tracking**  
  Displays the driver’s bus marker, upcoming stops, and icons for other buses on the same route using Mapsforge and MQTT.
  
- **Offline & Online Modes**  
  Falls back to an offline map asset if network connectivity is lost, while still displaying the stored schedule.
  
- **Configurable via ThingsBoard**  
  Fetches device-specific settings and schedules from a central IoT server using MQTT/REST.

---

## Architecture & Modules

- **Activities**  
  - `ScheduleActivity` – Renders the multi-color timeline or table view of today’s roster.  
  - `MapActivity` – Shows live map, schedule adherence icons, and arrival confirmation logic.  
  - `TimeTableActivity` – Entry point where drivers select their AID and load schedule data.
  
- **Helpers & Managers**  
  - `MapViewController` – Abstraction over Mapsforge to draw polylines, markers, and detection zones.  
  - `MqttHelper`, `MqttManager` – Handle real-time data publishing/subscribing for telemetry and config.  
  - `ScheduleStatusManager` – Computes and updates the “Ahead/On Time/Behind” status.  
  - `LocationManager` – Wraps Android’s fused location provider for GPS updates.  
  - `NetworkStatusHelper` – Monitors connectivity and toggles offline UI states.

- **Data Models**  
  - `BusRoute`, `RouteData`  
  - `BusStop`, `BusStopInfo`, `BusStopWithTimingPoint`  
  - `BusItem` (driver/device config)  
  - `ScheduleItem` (start/end times and stop list)  
  - `AttributesData` (for posting telemetry)

- **Services & APIs**  
  - Retrofit interfaces under `ApiService` & `ApiServiceBuilder` for REST calls.  
  - GSON for JSON serialization/deserialization.

- **UI & Resources**  
  - Layouts in `res/layout/` (including `activity_map.xml`, `activity_schedule.xml`)  
  - Vector drawables for bus icons and timing markers  
  - Offline map asset (e.g. `assets/new-zealand.map`)

---

## Getting Started

### Prerequisites

- **Android Studio** (Arctic Fox or later)  
- **JDK 11+**  
- **Android API level 21+**  
- **Mapsforge libraries** (included via Gradle)  
- **Google Play Services** (for fused location)  
- **MQTT client** (Paho or equivalent)  
- **Internet permission** in `AndroidManifest.xml`

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
