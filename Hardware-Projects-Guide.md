# DIY Reticulum Hardware Projects: Complete Buyer, Assembly & Flashing Guide

Welcome to the **Reticulum Hardware Projects Guide**! This document provides everything you need to build, assemble, flash, and operate the three standalone off-grid hardware projects supported by [`go-reticulum`](https://github.com/gmlewis/go-reticulum) and [`go-nomadnet`](https://github.com/gmlewis/go-nomadnet), accelerated by the open-source silicon coprocessor in [`asic-reticulum`](https://github.com/gmlewis/asic-reticulum).

> [!NOTE]
> **No compiler toolchains, Python scripts, or source code cloning required!**
> Every executable and firmware artifact referenced in this guide is pre-compiled and downloadable directly from GitHub Releases.
> For microcontrollers (ESP32-C5 in Projects 2 and 3), you can flash firmware **directly from your web browser** with zero installation using in-browser Web Serial tools like [ESPConnect](https://thelastoutpostworkshop.github.io/ESPConnect/) or the [Espressif Web Flasher](https://espressif.github.io/esptool-js/).

---

## Table of Contents

- [The Three Hardware Projects at a Glance](#the-three-hardware-projects-at-a-glance)
- [Universal Reticulum Hat PCB (Carrier Board)](#universal-reticulum-hat-pcb-carrier-board)
  - [Ordering from PCBWay or JLCPCB](#ordering-from-pcbway-or-jlcpcb)
- [Project 1: The Pocket Linux Terminal (Form Factor A)](#project-1-the-pocket-linux-terminal-form-factor-a)
  - [Hardware Bill of Materials (BOM)](#1-hardware-bill-of-materials-bom)
  - [Files to Download from GitHub](#2-files-to-download-from-github)
  - [Hardware Assembly Step-by-Step](#3-hardware-assembly-step-by-step)
  - [Flashing & Software Configuration](#4-flashing--software-configuration)
- [Project 2: The Standalone Pocket Communicator (Form Factor B)](#project-2-the-standalone-pocket-communicator-form-factor-b)
  - [Hardware Bill of Materials (BOM)](#1-hardware-bill-of-materials-bom-1)
  - [Files to Download from GitHub](#2-files-to-download-from-github-1)
  - [Hardware Assembly & Pinout](#3-hardware-assembly--pinout)
  - [Flashing & First Boot](#4-flashing--first-boot)
- [Project 3: The Autonomous Pocket Hub & Repeater (Form Factor C)](#project-3-the-autonomous-pocket-hub--repeater-form-factor-c)
  - [Hardware Bill of Materials (BOM)](#1-hardware-bill-of-materials-bom-2)
  - [Files to Download from GitHub](#2-files-to-download-from-github-2)
  - [Hardware Assembly](#3-hardware-assembly)
  - [Flashing & Initial Operation](#4-flashing--initial-operation)
  - [Connecting to the Hub via Wi-Fi AP](#5-connecting-to-the-hub-via-wi-fi-ap)
- [RF Safety & Best Practices](#rf-safety--best-practices)
- [Troubleshooting & FAQ](#troubleshooting--faq)

---

## The Three Hardware Projects at a Glance

| Specification | Project 1: Pocket Linux Terminal | Project 2: Pocket Communicator | Project 3: Autonomous Pocket Hub |
| :--- | :--- | :--- | :--- |
| **Form Factor** | Form Factor A (`pocket_terminal`) | Form Factor B (`pocket_communicator`) | Form Factor C (`pocket_hub`) |
| **Primary Host** | Raspberry Pi Zero 2W (or SBC) | ESP32-C5 RISC-V SoC | ESP32-C5 RISC-V SoC |
| **Operating System** | Raspberry Pi OS Lite (64-bit Linux) | Bare-metal firmware / RTOS | Bare-metal firmware / RTOS |
| **User Interface** | 2.8" Color TFT LCD + CardKB Keyboard | 2.8" Color TFT LCD + CardKB Keyboard | **Headless** (No LCD, No Keyboard) |
| **Networking** | LoRa + Wi-Fi client + Bluetooth | LoRa + Wi-Fi 6 + Bluetooth | LoRa + Wi-Fi 6 SoftAP Repeater |
| **Core Software** | Full `gonomadnet` TUI + `gorrcd` | Embedded `gonomadnet` / `gornsd` | Standalone **`gorrcd` daemon ONLY** |
| **Typical Battery Life** | 4–6 hours (1500–2500 mAh LiPo) | 12–18 hours (1500–2500 mAh LiPo) | 24–48 hours (or indefinite on solar) |
| **Hardware Crypto** | **Optional** (ASIC/FPGA offload, or pure-Go CPU software) | **Optional** (ASIC/FPGA offload, or MCU software) | **Optional** (ASIC/FPGA offload, or MCU software) |

---

## Universal Reticulum Hat PCB (Carrier Board)

All three projects can be built using the **Universal Reticulum Hat & Carrier PCB** (`hw/pcb/reticulum-hat`). A single manufactured PCB accommodates:
- **Bottom Stacking**: 40-pin header for Raspberry Pi Zero 2W (Project 1).
- **Top Sockets**: Dual 22-pin headers for ESP32-C5 DevKit (Projects 2 and 3).
- **LoRa RF**: Footprint for EBYTE E22-900M22S (+22 dBm SX1262) with edge SMA jack.
- **Crypto Accelerator**: Standard $2\times 5$ (10-pin) QSPI socket for Tiny Tapeout 08/09/10 or Tang Primer 25K PMOD.
- **Power System**: USB-C with Power Delivery pulldowns, TP4056 1A LiPo battery charger, dynamic power path MOSFET, and AP2112K 3.3V 600mA ultra-low-noise LDO.

### Ordering from PCBWay or JLCPCB

You do not need KiCad installed to order boards. Use the pre-exported manufacturing package in the release assets:

1. Download **`reticulum-hat-gerbers.zip`**, **`BOM.csv`**, and **`CPL.csv`** from [GitHub Releases](https://github.com/gmlewis/asic-reticulum/releases/latest).
2. Go to [PCBWay](https://www.pcbway.com) or [JLCPCB](https://jlcpcb.com).
3. **PCB Specifications**:
   - Layers: **2 Layers**
   - Dimensions: **$65.0\text{ mm} \times 56.0\text{ mm}$**
   - Material: **FR-4 standard TG150–170**
   - Thickness: **1.6 mm**
   - Surface Finish: **ENIG (Electroless Nickel Immersion Gold)** *(recommended for RF and castellated LoRa soldering)*
   - Copper Weight: **1 oz**
   - Solder Mask: Black, Blue, or Matte Green
4. **SMT Assembly (Optional Turnkey)**:
   - Upload `BOM.csv` and `CPL.csv` for surface-mount components (TP4056, AP2112K, passives, USB-C jack).

---

## Project 1: The Pocket Linux Terminal (Form Factor A)

The **Pocket Linux Terminal** is the ultimate handheld Reticulum computer. It boots a complete 64-bit Linux OS on a Raspberry Pi Zero 2W, starts the `gorrcd` mesh chat daemon in the background, and displays the full interactive `gonomadnet` Terminal UI on a vibrant 2.8" color screen with physical thumb-typing.

```
+-------------------------------------------------------------+
|  [2.8" SPI TFT LCD (ST7789)]                                |
|  +-------------------------------------------------------+  |
|  | * NomadNet *  [Announces: 12]  [RRC Hub: Connected]   |  |
|  | > Nodes  > Conversations  > Channels  > Micron Pages  |  |
|  +-------------------------------------------------------+  |
|                                                             |
|  [Carrier PCB with SX1262 LoRa + SMA Antenna + QSPI ASIC]   |
|  [Raspberry Pi Zero 2W (stacked underneath)]                |
|                                                             |
|  [M5Stack CardKB I2C Keyboard (QWERTY + Sym Keys)]          |
+-------------------------------------------------------------+
```

### 1. Hardware Bill of Materials (BOM)

| Item | Description / Model | Sourcing / Purchasing Link | Approx. Cost |
| :--- | :--- | :--- | :--- |
| **SBC** | Raspberry Pi Zero 2W (with headers) | [Adafruit #5291](https://www.adafruit.com/product/5291) / [Pimoroni](https://shop.pimoroni.com) | ~$15 |
| **Micro-SD Card** | 16 GB or 32 GB Class 10 / A1 Micro-SD | SanDisk Ultra / Kingston (Amazon / DigiKey) | ~$6 |
| **Carrier PCB** | Universal Reticulum Hat PCB | Fabricated via PCBWay or JLCPCB | ~$2–5 |
| **LoRa Module** | EBYTE E22-900M22S (Semtech SX1262, +22 dBm) | LCSC `C963388` / AliExpress / Mouser | ~$7 |
| **Antenna** | 868 MHz (EU) or 915 MHz (US/AU) SMA Antenna | [Adafruit #1859](https://www.adafruit.com/product/1859) / DigiKey | ~$8 |
| **Display** | 2.8" SPI TFT LCD ($320\times 240$, ST7789) | [Adafruit #3787](https://www.adafruit.com/product/3787) / AliExpress | ~$12 |
| **Keyboard** | M5Stack CardKB (I2C v1.1) | [M5Stack Store](https://shop.m5stack.com/products/cardkb-computer-keyboard-unit-v1-1) / Adafruit | ~$9 |
| **Battery** | 1S 3.7V 1500–2500 mAh LiPo (JST-PH 2.0mm) | [Adafruit #328](https://www.adafruit.com/product/328) | ~$10 |
| **Cable** | 4-pin STEMMA QT / Qwiic JST-SH Cable (100mm) | [Adafruit #4210](https://www.adafruit.com/product/4210) | ~$1.50 |
| *(Optional)* **Crypto** | Tiny Tapeout 08/09/10 Carrier or Tang Primer 25K | [Tiny Tapeout](https://tinytapeout.com) / [Sipeed](https://sipeed.com) | ~$25–45 |

> [!NOTE]
> ### Why is Hardware Crypto Optional? (Software Fallback)
> **You do NOT need the Tiny Tapeout ASIC or Tang Primer FPGA to build and use this terminal!**
>
> - **100% Software Fallback**: All Reticulum and LXMF cryptographic algorithms (Ed25519 signing/verification, X25519 ECDH key exchange, AES-128-CBC packet encryption, HMAC-SHA256 authentication, and SHA-256 LXMF Hashcash stamp grinding) are natively implemented in pure Go using standard library primitives. The Raspberry Pi Zero 2W's quad-core 64-bit ARM CPU handles all of them natively in software without any extra hardware.
> - **Standard Binaries**: If you don't have the crypto accelerator chip, simply download the standard pre-compiled binaries (`gonomadnet-pocket_terminal-linux-arm64`, `gorrcd-pocket_terminal-linux-arm64`, `gornsd-pocket_terminal-linux-arm64`). Everything works out of the box.
> - **What the ASIC / FPGA Adds (When Installed)**:
>   - **LXMF Stamp Grinding (Proof-of-Work)**: Hardware SHA-256 engine with midstate restore grinds high-difficulty stamps in milliseconds instead of seconds, without pegging CPU cores at 100%.
>   - **X25519 & Token Encryption**: Offloads scalar multiplication and packet burst encryption (such as fanouts in busy RRC chat rooms).
>   - **Battery Conservation**: The dedicated silicon draws only a few milliwatts, preventing CPU thermal throttling and substantially extending battery life on an off-grid handheld.

### 2. Files to Download from GitHub

Directly download the latest standalone binaries without git or Go toolchains:

```bash
# 1. Download the pre-built NomadNet Terminal UI for Pi Zero 2W
curl -LO https://github.com/gmlewis/go-nomadnet/releases/latest/download/gonomadnet-pocket_terminal-linux-arm64

# 2. Download the RRC Chat Hub Daemon for Pi Zero 2W
curl -LO https://github.com/gmlewis/go-reticulum/releases/latest/download/gorrcd-pocket_terminal-linux-arm64

# 3. Download the Reticulum Transport Daemon & Status Monitor
curl -LO https://github.com/gmlewis/go-reticulum/releases/latest/download/gornsd-pocket_terminal-linux-arm64
curl -LO https://github.com/gmlewis/go-reticulum/releases/latest/download/gornstatus-pocket_terminal-linux-arm64
```

> [!TIP]
> - **No crypto chip?** Use the 4 standard downloads above. All cryptography runs smoothly in software on the Pi's CPU.
> - **Have the ASIC or FPGA chip plugged into the QSPI socket?** Download `gorrcd-pocket_terminal-asic-linux-arm64` (or `-fpga-`) and `gornsd-pocket_terminal-asic-linux-arm64` instead to enable hardware acceleration.

### 3. Hardware Assembly Step-by-Step

1. **Solder the Raspberry Pi 40-Pin Header**: Solder the 40-pin female stacking header onto the underside of the Universal Reticulum Hat.
2. **Mount the LoRa Module**: Reflow or hand-solder the EBYTE E22-900M22S module onto the top side of the Hat. Solder the edge-mount SMA female connector.
3. **Attach the LoRa Antenna**:
   > [!CAUTION]
   > **CRITICAL RF WARNING**: Always screw the SMA antenna firmly onto the connector BEFORE powering the board. Transmitting with no load will damage the SX1262 power amplifier!
4. **Configure Solder Jumpers**: On the underside solder jumper pads, bridge the **MODE A** position for `JP_LORA`, `JP_LCD`, and `JP_I2C`. This routes the peripheral SPI, chip-select, and I2C lines directly to the Pi's 40-pin header.
5. **Attach Display & Keyboard**:
   - Plug the 8-pin 2.8" ST7789 LCD module into header `J_LCD`.
   - Connect the M5Stack CardKB to the STEMMA QT / Qwiic 4-pin port (`J_I2C`) using the flexible 4-pin cable.
6. **Stack & Power**: Press the Hat onto the Raspberry Pi Zero 2W. Plug the 3.7V LiPo battery into `J_BAT`.

### 4. Flashing & Software Configuration

#### Step 4.1: Flash the OS
1. Insert your micro-SD card into your computer.
2. Open **[Raspberry Pi Imager](https://www.raspberrypi.com/software/)**.
3. Choose OS: **Raspberry Pi OS Lite (64-bit)** (Debian Bookworm).
4. Click the gear icon to configure settings:
   - Set hostname (e.g. `reticulum-term`).
   - Enable SSH with password or public key.
   - Configure your local Wi-Fi credentials for initial setup.
5. Write the image to the SD card.

#### Step 4.2: Configure Hardware Drivers in `config.txt`
Before ejecting the SD card, open the `bootfs` partition on your computer and edit `/boot/firmware/config.txt` (or `/boot/config.txt`):

```ini
# Enable hardware SPI and I2C buses
dtparam=spi=on
dtparam=i2c_arm=on

# 2.8" ST7789 Display Driver (320x240 @ 60 Hz)
dtoverlay=fbtft,spi0-0,st7789v,reset_pin=27,dc_pin=25,led_pin=18,rotate=90,speed=48000000
```

#### Step 4.3: Install Binaries & Start
1. Insert the SD card into the Pi Zero 2W and power it on via USB-C.
2. Connect over SSH: `ssh pi@reticulum-term.local`.
3. Move the downloaded binaries to `/usr/local/bin` and set permissions:
   ```bash
   sudo chmod +x gonomadnet-pocket_terminal-linux-arm64
   sudo mv gonomadnet-pocket_terminal-linux-arm64 /usr/local/bin/gonomadnet

   sudo chmod +x gorrcd-pocket_terminal-linux-arm64
   sudo mv gorrcd-pocket_terminal-linux-arm64 /usr/local/bin/gorrcd

   sudo chmod +x gornsd-pocket_terminal-linux-arm64
   sudo mv gornsd-pocket_terminal-linux-arm64 /usr/local/bin/gornsd
   ```
4. Create the Reticulum configuration at `~/.reticulum/config`:
   ```ini
   [interfaces]
     [[LoRa Interface]]
       type = RNodeInterface
       interface_enabled = True
       port = /dev/spidev0.0
       frequency = 914900000
       bandwidth = 125000
       txpower = 22
       spreading_factor = 7
       coding_rate = 5
   ```
5. Test launch:
   ```bash
   # Launch the full terminal UI on the framebuffer
   gonomadnet
   ```
   You can now browse nodes, chat in local rooms, and communicate off-grid!

---

## Project 2: The Standalone Pocket Communicator (Form Factor B)

The **Standalone Pocket Communicator** runs an ultra-low-power embedded client directly on the **ESP32-C5** RISC-V SoC. It boots in less than 200 milliseconds, draws under 80 mA of current, and provides a clean messaging interface with physical keyboard and display without running a full Linux OS.

```
+-------------------------------------------------------------+
|  [2.8" SPI TFT LCD (ST7789)]                                |
|  +-------------------------------------------------------+  |
|  | * RETICULUM COMMUNICATOR *        [LoRa: 915 MHz OK]  |  |
|  | Last MSG: Glenn: "Meeting at checkpoint B in 10m"     |  |
|  +-------------------------------------------------------+  |
|                                                             |
|  [Carrier PCB with ESP32-C5 DevKit + SX1262 LoRa]           |
|                                                             |
|  [M5Stack CardKB I2C Keyboard]                              |
+-------------------------------------------------------------+
```

### 1. Hardware Bill of Materials (BOM)

- **MCU Board**: ESP32-C5-DevKitC-1 (RISC-V 240 MHz, Dual-Band Wi-Fi 6, 8MB Flash).
- **Carrier PCB**: Universal Reticulum Hat PCB with EBYTE E22-900M22S LoRa module (+22 dBm SX1262).
- **Display**: 2.8" SPI TFT LCD (ST7789).
- **Keyboard**: M5Stack CardKB I2C (v1.1).
- **Battery**: 1S 3.7V LiPo with JST-PH 2.0mm connector.
- **Antenna**: 868 MHz / 915 MHz SMA Antenna.

### 2. Files to Download from GitHub

Download the pre-compiled firmware bundle:

```bash
# Firmware binary bundle for ESP32-C5
curl -LO https://github.com/gmlewis/go-nomadnet/releases/latest/download/pocket_communicator-esp32c5-firmware.bin

# Flashing script
curl -LO https://github.com/gmlewis/go-nomadnet/releases/latest/download/flash-pocket-communicator.sh
chmod +x flash-pocket-communicator.sh
```

### 3. Hardware Assembly & Pinout

If using the Universal Hat in Mode B:
1. Solder dual 22-pin female socket headers at `J_ESP32_L` and `J_ESP32_R`.
2. Solder jumper pads to **MODE B** on `JP_LORA`, `JP_LCD`, `JP_I2C`, and `JP_CRYPTO`.
3. Firmly seat the **ESP32-C5-DevKitC-1** into the headers (USB-C port facing outward).
4. Attach the 915 MHz / 868 MHz antenna.
5. Connect the M5Stack CardKB to `J_I2C`.

### 4. Flashing & First Boot

You have two easy ways to flash the firmware onto the ESP32-C5:

#### Method A: In-Browser Web Flasher (Zero-Install — Recommended)

You do **not** need Python, `pip`, or command-line tools installed. You can flash the board directly from your web browser using the Web Serial API (supported in Google Chrome, Microsoft Edge, Brave, and Opera):

1. Connect the ESP32-C5 to your computer using a USB-C data cable.
2. Open either of these web flashers in a supported browser:
   - **[ESPConnect](https://thelastoutpostworkshop.github.io/ESPConnect/)** *(simple drag-and-drop web flasher)*
   - **[Espressif Official Web Flasher (esptool-js)](https://espressif.github.io/esptool-js/)** *(official Espressif Web Serial tool)*
   - **[Adafruit WebSerial ESPTool](https://adafruit.github.io/Adafruit_WebSerial_ESPTool/)**
3. Click **Connect** (or "Connect to ESP"). A browser popup will appear listing connected USB serial devices.
4. Select your board (e.g. `USB JTAG/serial debug unit`, `CP2102`, or `CH340`) and click **Connect**.
5. Set the Flash Offset to **`0x0`** and click **Choose File** (or "Browse") to select `pocket_communicator-esp32c5-firmware.bin`.
6. Click **Program** (or **Flash**). The web flasher will erase the flash, write the firmware with a live progress bar, and verify the checksum.
7. Once flashing reaches 100%, press the **RESET (EN)** button on the ESP32 board. The communicator will boot immediately!

#### Method B: Command-Line Flashing via `esptool.py`

For terminal enthusiasts or headless environments:

1. Connect the board via USB-C.
2. Install `esptool` if not already installed:
   ```bash
   pip install esptool
   ```
3. Flash the firmware (replace `/dev/ttyUSB0` with your serial device, e.g. `/dev/tty.usbmodem*` on macOS):
   ```bash
   esptool.py --chip esp32c5 -p /dev/ttyUSB0 -b 921600 write_flash 0x0 pocket_communicator-esp32c5-firmware.bin
   ```
4. Open the serial console to verify boot:
   ```bash
   tio /dev/ttyUSB0 -b 115200
   ```
   The LCD displays the initialization screen, checks the SX1262 LoRa radio, and awaits keystrokes from the CardKB.

---

## Project 3: The Autonomous Pocket Hub & Repeater (Form Factor C)

The **Autonomous Pocket Hub & Repeater** is a dedicated, self-contained mesh relay. It has **no keyboard and no display**—it runs standalone **`gorrcd`** on an ESP32-C5 with an SX1262 LoRa transceiver and a high-efficiency Wi-Fi 6 SoftAP. Place it on a roof, hilltop, or backpack, and any user within Wi-Fi or LoRa range can connect and communicate!

```
+-------------------------------------------------------------+
|  [Weatherproof Enclosure / Pelican Case]                    |
|                                                             |
|  +-------------------------------------------------------+  |
|  |  Universal Reticulum Hat / ESP32-C5 Hub Board        |  |
|  |  - ESP32-C5 (240 MHz RISC-V)                          |  |
|  |  - Semtech SX1262 LoRa (+22 dBm / +28 dBm)            |  |
|  |  - Wi-Fi 6 AP: "Reticulum-Hub-A1" (192.168.4.1)       |  |
|  |  - Standalone `gorrcd` In-Memory Room Engine         |  |
|  +-------------------------------------------------------+  |
|                                                             |
|  [18650 Li-Ion Cell (3500 mAh) + 5V Solar Charging Panel]   |
|  [External Tuned 915 MHz / 868 MHz Fiberglass Antenna]       |
+-------------------------------------------------------------+
```

### 1. Hardware Bill of Materials (BOM)

| Item | Description / Recommendation | Approx. Cost |
| :--- | :--- | :--- |
| **MCU Board** | ESP32-C5-DevKitC-1 (240 MHz RISC-V, Wi-Fi 6) | ~$15–20 |
| **RF Transceiver** | EBYTE E22-900M22S SX1262 (+22 dBm) | ~$7 |
| **Antenna** | High-Gain 3 dBi–5.8 dBi Tuned SMA Fiberglass Antenna | ~$15 |
| **Power System** | 18650 Li-Ion Cell with holder OR 5V USB-C Solar Power Bank | ~$12 |
| **Enclosure** | IP67 Weatherproof Junction Box (with SMA bulkhead) | ~$8 |

### 2. Files to Download from GitHub

```bash
# Standalone gorrcd Hub Firmware for ESP32-C5
curl -LO https://github.com/gmlewis/go-reticulum/releases/latest/download/gorrcd-pocket_hub-esp32c5-firmware.bin

# Pre-configured Hub Configuration
curl -LO https://github.com/gmlewis/go-reticulum/releases/latest/download/rrcd.toml
```

### 3. Hardware Assembly

1. Assemble the Universal Hat with the ESP32-C5 DevKit and E22 LoRa module.
2. **NO display and NO keyboard** are installed.
3. Install the board inside the weatherproof enclosure.
4. Mount the SMA antenna through the enclosure bulkhead and tighten securely.
5. Connect the 1S LiPo/Li-Ion battery to `J_BAT` or connect external 5V power to the USB-C jack.

### 4. Flashing & Initial Operation

#### Method A: In-Browser Web Flasher (Zero-Install — Recommended)

1. Connect the ESP32-C5 to your computer via USB-C.
2. Open **[ESPConnect](https://thelastoutpostworkshop.github.io/ESPConnect/)** or **[Espressif Web Flasher](https://espressif.github.io/esptool-js/)** in Google Chrome, Microsoft Edge, Brave, or Opera.
3. Click **Connect** and select your ESP32 serial port.
4. Set Flash Offset to **`0x0`** and select the downloaded file `gorrcd-pocket_hub-esp32c5-firmware.bin`.
5. Click **Program / Flash**.
6. When complete, disconnect from your computer and connect your LiPo battery or solar power source.

#### Method B: Command-Line Flashing via `esptool.py`

```bash
esptool.py --chip esp32c5 -p /dev/ttyUSB0 -b 921600 write_flash 0x0 gorrcd-pocket_hub-esp32c5-firmware.bin
```
4. The hub boots in 150 ms, begins transmitting Reticulum announces on LoRa (`rrc.hub`), and broadcasts a Wi-Fi 6 access point:
   - **SSID**: `Reticulum-Hub-XXXX` *(where XXXX is the last 4 hex digits of the hub identity)*
   - **Default Password**: `reticulum` (or open, as configured in `rrcd.toml`)
   - **Hub IP Address**: `192.168.4.1` (TCP port `4242`)

### 5. Connecting to the Hub via Wi-Fi AP

Any smartphone, laptop, or tablet can connect to the hub without an internet connection:

1. Connect your phone or laptop to the Wi-Fi network `Reticulum-Hub-XXXX`.
2. Open **NomadNet** (or your Reticulum client) on your phone or laptop.
3. Add a new interface in your configuration (`~/.reticulum/config` or in NomadNet's Interface menu):
   ```ini
   [[Pocket Hub Wi-Fi]]
     type = TCPClientInterface
     interface_enabled = True
     outgoing = True
     target_host = 192.168.4.1
     target_port = 4242
   ```
4. NomadNet will instantly connect! Open the **Channels** tab (`c` key) to join the hub's default room (`#general`) and begin chatting across the off-grid LoRa mesh!

---

## RF Safety & Best Practices

> [!WARNING]
> **RF POWER AMPLIFIER PROTECTION**:
> Never supply power to the EBYTE E22-900M22S without a 50-ohm antenna securely connected. Transmitting without an antenna generates an infinite Voltage Standing Wave Ratio (VSWR), reflecting RF power back into the final stage transistors and permanently destroying the SX1262.

- **Regional Frequencies**:
  - **North America / Australia**: $902.0\text{ MHz} - 928.0\text{ MHz}$ (Default: $914.9\text{ MHz}$).
  - **Europe**: $863.0\text{ MHz} - 870.0\text{ MHz}$ (Default: $868.1\text{ MHz}$).
  - Set `txpower = 22` (+22 dBm / 160 mW) for E22.
- **Antenna Placement**: For best line-of-sight propagation, keep the antenna vertical and elevated at least 1 meter away from large metal surfaces or human bodies.

---

## Troubleshooting & FAQ

### 1. `gonomadnet: no TTY detected; falling back to daemon mode`
- **Cause**: Launched without an active interactive terminal (e.g. backgrounded or over SSH without pseudo-terminal).
- **Fix**: Run interactively from the console, or pass `-t` to force terminal UI mode.

### 2. `cannot open /dev/spidev0.0: No such file or directory`
- **Cause**: SPI interface is disabled in the Raspberry Pi OS device tree.
- **Fix**: Ensure `dtparam=spi=on` is present in `/boot/firmware/config.txt` and reboot.

### 3. `CardKB keyboard not responding`
- **Cause**: I2C bus disabled or wiring reversed.
- **Fix**: Check that `dtparam=i2c_arm=on` is in `config.txt`. Test bus communication with `i2cdetect -y 1`. The CardKB should appear at I2C address `0x5F`.

### 4. `esptool: Failed to connect to ESP32: Timed out waiting for packet header`
- **Cause**: The board did not enter the ROM bootloader automatically.
- **Fix**: Hold down the **BOOT** button on the ESP32 board, press and release the **RESET (EN)** button, and then release the **BOOT** button while running `esptool.py`.
