# Universal Reticulum Hat & Carrier

**An Open-Hardware Companion Board for the Reticulum Network Stack with Hardware Cryptographic Acceleration, LoRa Mesh, and Dual-Host Support.**

![KiCad 8](https://img.shields.io/badge/KiCad-v8.0-blue.svg)
![Hardware Status](https://img.shields.io/badge/Hardware-v1.0--Release-brightgreen.svg)
![JLCPCB SMT](https://img.shields.io/badge/JLCPCB-SMT%20Ready-orange.svg)
![PCBWay](https://img.shields.io/badge/PCBWay-Turnkey%20Ready-red.svg)

---

## Overview

The **Universal Reticulum Hat** is a high-reliability hardware platform designed to give any Reticulum enthusiast, developer, or emergency responder an instant, pocketable Reticulum communicator.

It bridges the software Reticulum ecosystem (`gonomadnet`, `gorrcd`, `rnsd`) with cutting-edge silicon acceleration (the `asic-reticulum` Tiny Tapeout ASIC and Gowin Tang Primer 25K FPGA accelerator).

```
                      +---------------------------------------+
                      |       Universal Reticulum Hat         |
                      |                                       |
 [ Raspberry Pi ]<--->| [ 3-Way Jumper Multiplexing Block ]   |
  (Form Factor A)     |    |              |              |    |
                      |    v              v              v    |
 [ ESP32-C5 /   ]<--->| [SX1262 LoRa] [ASIC Socket] [ST7789 & |
   Heltec V4 ]        | (+22/+28 dBm)  (7-pin QSPI)  CardKB]  |
  (Form Factor B)     +---------------------------------------+
                                          |
                              [ USB-C / LiPo Charger ]
                              [ AP2112K-3.3 Power-Path]
```

### Key Capabilities:
- **Dual-Host Support**:
  - **Form Factor A (Pocket Linux Terminal)**: Directly stacks on a **Raspberry Pi Zero 2W** or **Milk-V Duo S** via standard 40-pin header. Runs full Linux, `gonomadnet` TUI, `gorrcd` mesh chat server, and `rnsd`.
  - **Form Factor B (All-in-One Communicator)**: Accepts an **Espressif ESP32-C5-DevKitC-1** (dual-band 2.4/5 GHz Wi-Fi 6 RISC-V) or **Heltec WiFi LoRa 32 V4** (ESP32-S3 + +28 dBm PA) daughterboard.
- **Hardware Crypto Socket**: Dedicated 10-pin ($2\times 5$) header supporting the `asic-reticulum` **7-pin QSPI + IRQ# protocol**. Offloads Ed25519 signatures ($140\times$ faster than software), X25519 key exchanges ($105\times$ faster), SHA-256/512 ($45\times$ faster), and AES-128 ($40\times$ faster).
- **Semtech SX1262 LoRa Radio**: Onboard EBYTE E22-900M22S (+22 dBm, ~160 mW) with 50-ohm controlled impedance coplanar waveguide to edge-mount SMA and internal u.FL antenna jacks.
- **Display & Keyboard UI**:
  - Direct mounting header and FPC ribbon for **ST7789 2.8" $320\times 240$ SPI TFT LCD** with PWM backlight dimming.
  - JST-SH STEMMA QT / Qwiic port and Dupont header for **M5Stack CardKB** (50-key tactile QWERTY keyboard) or BB Q10 I2C keyboard.
- **Uninterruptible Power Subsystem**:
  - USB-C power input with dual 5.1k CC pull-downs (compatible with all PD and USB-A chargers).
  - Integrated TP4056 1A linear LiPo battery charger with dual charge status LEDs.
  - Zero-drop DMG2305UX P-MOSFET auto-switching power path (seamlessly transitions between USB-C and battery without system reboot).
  - AP2112K-3.3 ultra-low-noise 600mA LDO regulator for crystal-clean RF and crypto logic rails.

---

## Hardware Architecture & Project Structure

```
hw/pcb/reticulum-hat/
├── reticulum-hat.kicad_pro       # Top-level KiCad 8 Project File
├── reticulum-hat.kicad_sch       # Master Hierarchical Schematic Sheet
├── schematics/
│   ├── power.kicad_sch           # USB-C, TP4056 Charger, DMG2305UX Power Path, AP2112K LDO
│   ├── esp32c5_socket.kicad_sch  # Dual 22-pin Female Sockets for ESP32-C5 / Heltec V4
│   ├── pi_header.kicad_sch       # Raspberry Pi 40-Pin Stacking Female Header
│   ├── lora_sx1262.kicad_sch     # EBYTE E22-900M22S SX1262 Module & 50-Ohm RF Traces
│   ├── crypto_socket.kicad_sch   # 10-Pin (2x5) QSPI + Dedicated IRQ Header for ASIC/FPGA
│   ├── display_keypad.kicad_sch  # ST7789 2.8" TFT LCD & STEMMA QT CardKB Interface
│   └── bus_mux.kicad_sch         # 3-Way Jumper Multiplexing Blocks (Host A vs Host B)
├── BOM.md                        # Formatted Component Bill of Materials & Sourcing Guide
├── BOM.csv                       # JLCPCB & PCBWay SMT Automated Assembly Pick-List
├── PINOUT-MATRIX.md              # Complete Pin Routing Table for Pi Zero, C5, and Heltec V4
└── README.md                     # Hardware Manual & Ordering Guide (This File)
```

---

## Operating Modes & Jumper Configuration

Peripheral lines are routed via 19 standard 3-pin $2.54\text{ mm}$ male headers (`JP1` through `JP19`) with 2-pin shunt jumpers:

```
        Pin 1 (Host A: Pi)  ---  Pin 2 (Device Bus)  ---  Pin 3 (Host B: MCU)
```

### 1. Mode A: Form Factor A (Raspberry Pi Zero 2W Host)
- **Board Setup**: Install the Raspberry Pi Zero 2W under or over the Hat using an extra-tall 40-pin stacking header. Leave the MCU socket headers empty.
- **Jumper Positions**:
  - `JP_LORA1..6`: Shunts on **Pins 1-2** (connects EBYTE SX1262 to Pi SPI0).
  - `JP_CRP1..7`: Shunts on **Pins 1-2** (connects Crypto Socket to Pi SPI1 + GPIO24 IRQ).
  - `JP_UI1..6`: Shunts on **Pins 1-2** (connects ST7789 LCD and CardKB to Pi I2C1 + GPIO).
- **Software**: Boot Raspberry Pi OS Lite, run `rnsd`, `gorrcd`, and `gonomadnet`.

### 2. Mode B1: Form Factor B (ESP32-C5 Host + Onboard EBYTE LoRa)
- **Board Setup**: Plug an **ESP32-C5-DevKitC-1** into the two 22-pin female socket headers. Leave the Pi 40-pin header unpopulated.
- **Jumper Positions**:
  - `JP_LORA1..6`: Shunts on **Pins 2-3** (connects EBYTE SX1262 to ESP32-C5 SPI).
  - `JP_CRP1..7`: Shunts on **Pins 2-3** (connects Crypto Socket to ESP32-C5 QSPI).
  - `JP_UI1..6`: Shunts on **Pins 2-3** (connects LCD and CardKB to ESP32-C5 I2C/SPI).
- **Software**: Flash ESP-IDF firmware with FreeRTOS Reticulum client, 5 GHz Wi-Fi 6 AP, and ST7789 display driver.

### 3. Mode B2: Form Factor B (Heltec WiFi LoRa 32 V4 Host)
- **Board Setup**: Plug a **Heltec WiFi LoRa 32 V4** into the socket headers. **Do NOT populate the onboard EBYTE LoRa module** (the Heltec board already has its own +28 dBm SX1262 and IPEX antenna).
- **Jumper Positions**:
  - `JP_LORA1..6`: **REMOVE ALL SHUNTS (LEAVE OPEN)**.
  - `JP_CRP1..7`: Shunts on **Pins 2-3** (connects Crypto Socket to Heltec GPIOs).
  - `JP_UI1..6`: Shunts on **Pins 2-3** (connects CardKB and ST7789 to Heltec GPIOs).

---

## Manufacturing & Ordering Instructions

The design files are 100% turnkey ready for both **JLCPCB** and **PCBWay**.

### Option 1: JLCPCB SMT Assembly (Recommended for Budget / Fast Prototyping)
1. In KiCad 8, open `reticulum-hat.kicad_pro`.
2. Generate Fabrication Outputs:
   - Gerbers (`.gbr`) and Excellon Drill files (`.drl`), compressed into a single `Gerber_Universal_Reticulum_Hat_v1.0.zip`.
   - BOM: Export using the provided `hw/pcb/reticulum-hat/BOM.csv`.
   - CPL (Component Placement List): Export centroid file (`.pos` / `.csv`).
3. On [JLCPCB.com](https://jlcpcb.com):
   - Upload `Gerber_Universal_Reticulum_Hat_v1.0.zip`.
   - Select **2 Layers**, Thickness: **1.6mm**, Surface Finish: **ENIG** (Electroless Nickel Immersion Gold — essential for high-frequency RF pads).
   - Select **PCB Assembly (SMT)**: Single-side top surface assembly.
   - Upload `BOM.csv` and `CPL.csv`. All LCSC part numbers in `BOM.csv` match JLCPCB "Basic" and "Preferred" components automatically!

### Option 2: PCBWay Turnkey Assembly (Recommended for Premium Quality / Custom Options)
1. On [PCBWay.com](https://www.pcbway.com):
   - Choose **PCB Instant Quote**.
   - Dimensions: $65.0\text{ mm} \times 56.0\text{ mm}$, 2 Layers, $1.6\text{ mm}$ thickness, 1 oz Copper.
   - Surface Finish: **ENIG** (Immersion Gold).
   - Silkscreen: White on Black (or your preferred color).
   - Check **SMT Assembly**.
2. Upload Gerber archive, `BOM.md` / `BOM.csv`, and Centroid file.
3. PCBWay’s engineering team will match all passives and connectors using DigiKey/Mouser part numbers provided in `BOM.md`.

---

## Mechanical Assembly & 3D Printable Enclosure

The Universal Reticulum Hat adheres strictly to the **Raspberry Pi HAT Mechanical Specification**:
- **Dimensions**: $65.0\text{ mm} \times 56.0\text{ mm}$ with $3.5\text{ mm}$ radius rounded corners.
- **Mounting Holes**: 4 $\times$ $M2.5$ screw holes spaced at $58.0\text{ mm} \times 49.0\text{ mm}$.
- **Standoffs**: Use 11 mm female-to-female $M2.5$ brass hex standoffs when stacking on a Raspberry Pi Zero 2W.
- **Enclosure Design**: An open-source 3D printable handheld clamshell enclosure (STL and STEP files in `hw/cad/`) holds the complete stack:
  - Bottom tray: Raspberry Pi Zero 2W / ESP32-C5 and 3.7V 1200mAh LiPo pouch cell.
  - Middle tier: Universal Reticulum Hat with SMA antenna protruding from the top edge.
  - Top bezel: Flush-mount cutouts for the 2.8" TFT LCD and snap-fit holder for the M5Stack CardKB keyboard.

---

## License & Open Hardware

Designed and distributed under the **CERN Open Hardware Licence Version 2 - Strongly Reciprocal (CERN-OHL-S)**.  
Copyright (c) 2026 Glenn Lewis and Contributors.
