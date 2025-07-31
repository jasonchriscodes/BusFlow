# BusFlow

BusFlow is an Android application designed to replace printed duty sheets by presenting each driver’s roster in an intuitive multi-color timeline or paginated table view. It highlights timing points, tracks on-time performance, and provides a live map to show your route, current position, and nearby buses.

---

## Table of Contents

- [Why BusFlow?](#why-busflow)  
- [Key Features](#key-features)  
- [Screenshots](#screenshots)  
- [Architecture & Modules](#architecture--modules)  
- [Getting Started](#getting-started)  
  - [Prerequisites](#prerequisites)  
  - [Installation](#installation)  
- [Usage](#usage)  
- [Contributing](#contributing)  
- [License](#license)  

---

## Why BusFlow?

Printed duty sheets can be hard to read on the go, and memorizing multiple timing points adds cognitive load. BusFlow:

- **Replaces paper rosters** with a digital schedule that’s crystal-clear at a glance  
- **Visualizes duties** in color-coded timelines or a scrollable table  
- **Highlights timing points** so you know instantly if you’re early, on time, or running late  
- **Provides a live map** for real-time position, route guidance, and tracking other buses  

---

## Key Features

- **Multi-Color Timeline**  
  Displays duties, breaks, and relief periods as proportional, color-coded segments.

- **Paginated Table View**  
  Switch to a concise, paginated table for drivers who prefer row-and-column layouts.

- **Real-Time Schedule Adherence**  
  MapActivity shows “Ahead”, “On Time”, or “Behind” icons at each timing point.

- **Live Map Tracking**  
  Uses Mapsforge + MQTT to plot your bus marker, route polyline, red/green timing-point stops, and other buses on the same route.

- **Offline Fallback**  
  Automatically loads an offline map asset if connectivity drops, with an on-screen offline notice for other-bus tracking.

- **Centralized Configuration**  
  Fetch device-specific settings and today’s roster from a ThingsBoard IoT server via MQTT/REST.

---

## Screenshots

### Multi-Color Timeline View  
![Timeline view showing today’s roster with color-coded segments](docs/screenshot-timeline.png)  
*ScheduleActivity in timeline mode: duties, breaks, reliefs.*

### Live Map & Schedule Status  
![Map view with bus marker, upcoming stop label, and timing-point status icons](docs/screenshot-map.png)  
*MapActivity: route polyline, current bus marker, red/green timing-point markers, and other buses.*

---

## Architecture & Modules

- **Activities**  
  - `ScheduleActivity` – timeline/table roster UI  
  - `MapActivity` – live map, timing-point status, arrival confirmation  
  - `TimeTableActivity` – entry screen for selecting AID and loading data

- **Helpers & Managers**  
  - `MapViewController` – Mapsforge integration (polyline, markers, zones)  
  - `MqttHelper` & `MqttManager` – telemetry, config via MQTT  
  - `ScheduleStatusManager` – computes Ahead/On-Time/Behind  
  - `LocationManager` – fused-location wrapper  
  - `NetworkStatusHelper` – online/offline UI

- **Data Models**  
  `BusRoute`, `RouteData`, `BusStop`, `BusStopWithTimingPoint`, `BusItem`, `ScheduleItem`, `AttributesData`

- **Services & APIs**  
  Retrofit + GSON under `ApiService` / `ApiServiceBuilder`

- **UI & Resources**  
  Layouts in `res/layout/`; vector drawables; offline map in `app/src/main/assets/`

---

## Getting Started

### Prerequisites

- Android Studio (Arctic Fox or later)  
- JDK 11+  
- Android API 21+  
- Internet & Location permissions in `AndroidManifest.xml`:
  ```xml
  <uses-permission android:name="android.permission.INTERNET"/>
  <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
