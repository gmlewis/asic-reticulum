# Universal Reticulum Hat & Carrier: Pinout & Multiplexing Matrix

**Revision**: v1.0  
**Date**: September 2026  
**Project**: Reticulum Network Stack / ASIC-Reticulum  

The Universal Reticulum Hat is an open-hardware carrier board engineered to support two distinct host architectures:
1. **Form Factor A (Pocket Linux Terminal)**: Raspberry Pi Zero 2W / Milk-V Duo S (Linux host running `gonomadnet`, `gorrcd`, and full RNS).
2. **Form Factor B (All-in-One Communicator)**: Espressif ESP32-C5-DevKitC-1 or Heltec WiFi LoRa 32 V4 (Bare-metal / FreeRTOS host).

Both configurations share the same onboard peripherals:
- **Cryptographic Accelerator Socket**: 10-pin ($2\times 5$) header connecting to the Tiny Tapeout ASIC or Gowin Tang Primer 25K PMOD (Ed25519, X25519, SHA-256, SHA-512, AES-128, PRNG).
- **LoRa Radio**: EBYTE E22-900M22S (Semtech SX1262, up to +22 dBm) with 50-ohm coplanar waveguide to edge-mount SMA.
- **Display**: ST7789 2.8" $320\times 240$ SPI TFT LCD with PWM backlight control.
- **Keyboard**: STEMMA QT / Qwiic (JST-SH 1.0mm 4-pin) or $1\times 4$ 2.54mm header for M5Stack CardKB / BB Q10 I2C keyboard.
- **Power Subsystem**: USB-C, TP4056 LiPo charger, DMG2305UX MOSFET auto-switching power path, and AP2112K-3.3 ultra-low-noise 600mA LDO.

---

## 1. Master Peripheral Routing Matrix

| Peripheral Signal | Shared Bus Net | Form Factor A: Pi Zero 2W (40-Pin Header) | Form Factor B: ESP32-C5 (DevKitC-1) | Form Factor B: Heltec V4 (WiFi LoRa 32) | Notes & Configuration |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **LoRa SCK** | `NET_LORA_SCK` | Pin 23 (`GPIO 11`, SPI0_SCLK) | Pin 13 (`GPIO 10`) | *Internal (GPIO 9)* | 10 MHz SPI Clock |
| **LoRa MOSI** | `NET_LORA_MOSI` | Pin 19 (`GPIO 10`, SPI0_MOSI) | Pin 14 (`GPIO 11`) | *Internal (GPIO 10)* | SPI Master Out |
| **LoRa MISO** | `NET_LORA_MISO` | Pin 21 (`GPIO 9`, SPI0_MISO) | Pin 15 (`GPIO 12`) | *Internal (GPIO 11)* | SPI Master In |
| **LoRa NSS** | `NET_LORA_NSS` | Pin 24 (`GPIO 8`, SPI0_CE0) | Pin 16 (`GPIO 13`) | *Internal (GPIO 8)* | Active-Low Chip Select |
| **LoRa BUSY** | `NET_LORA_BUSY` | Pin 22 (`GPIO 25`) | Pin 17 (`GPIO 14`) | *Internal (GPIO 13)* | SX1262 Busy Status |
| **LoRa DIO1** | `NET_LORA_DIO1` | Pin 15 (`GPIO 22`) | Pin 18 (`GPIO 15`) | *Internal (GPIO 14)* | Packet RX/TX Interrupt |
| **LoRa RST** | `NET_LORA_RST` | Pin 13 (`GPIO 27`) | Pin 19 (`GPIO 16`) | *Internal (GPIO 12)* | Active-Low Reset |
| **Crypto SCLK** | `NET_CRP_SCLK` | Pin 40 (`GPIO 21`, SPI1_SCLK) | Pin 9 (`GPIO 6`) | Pin 21 (`GPIO 41`) | QSPI Clock (40–80 MHz) |
| **Crypto CS#** | `NET_CRP_CS` | Pin 38 (`GPIO 20`, SPI1_CE0) | Pin 10 (`GPIO 7`) | Pin 22 (`GPIO 42`) | QSPI Chip Select |
| **Crypto IO0** | `NET_CRP_IO0` | Pin 36 (`GPIO 16`, SPI1_MOSI) | Pin 5 (`GPIO 2`) | Pin 23 (`GPIO 45`) | QSPI MOSI / D0 |
| **Crypto IO1** | `NET_CRP_IO1` | Pin 35 (`GPIO 19`, SPI1_MISO) | Pin 6 (`GPIO 3`) | Pin 24 (`GPIO 46`) | QSPI MISO / D1 |
| **Crypto IO2** | `NET_CRP_IO2` | Pin 37 (`GPIO 26`) | Pin 7 (`GPIO 4`) | Pin 25 (`GPIO 47`) | QSPI WP# / D2 |
| **Crypto IO3** | `NET_CRP_IO3` | Pin 13 (`GPIO 27` / shared) | Pin 8 (`GPIO 5`) | Pin 26 (`GPIO 48`) | QSPI HOLD# / D3 |
| **Crypto IRQ#**| `NET_CRP_IRQ` | Pin 18 (`GPIO 24`, EXT_INT) | Pin 11 (`GPIO 8`) | Pin 20 (`GPIO 39`) | Active-Low IRQ (10k pullup) |
| **Keypad SDA** | `NET_I2C_SDA` | Pin 3 (`GPIO 2`, I2C1_SDA) | Pin 22 (`GPIO 18`) | Pin 11 (`GPIO 17`) | 4.7k Pullup to 3.3V |
| **Keypad SCL** | `NET_I2C_SCL` | Pin 5 (`GPIO 3`, I2C1_SCL) | Pin 23 (`GPIO 19`) | Pin 12 (`GPIO 18`) | 4.7k Pullup to 3.3V |
| **LCD CS** | `NET_LCD_CS` | Pin 26 (`GPIO 7`, SPI0_CE1) | Pin 24 (`GPIO 20`) | Pin 17 (`GPIO 38`) | ST7789 Chip Select |
| **LCD DC** | `NET_LCD_DC` | Pin 11 (`GPIO 17`) | Pin 25 (`GPIO 21`) | Pin 16 (`GPIO 37`) | ST7789 Data/Command |
| **LCD RST** | `NET_LCD_RST` | Pin 7 (`GPIO 4`) | Pin 26 (`GPIO 22`) | Pin 15 (`GPIO 36`) | ST7789 Hardware Reset |
| **LCD BL PWM** | `NET_LCD_BL` | Pin 12 (`GPIO 18`, PWM0) | Pin 27 (`GPIO 23`) | Pin 14 (`GPIO 35`) | Backlight NPN Driver |
| **UART TXD** | `NET_HOST_TX` | Pin 8 (`GPIO 14`, UART0_TXD) | Pin 28 (`GPIO 24`) | Pin 9 (`GPIO 43`) | Optional Host-to-Host link |
| **UART RXD** | `NET_HOST_RX` | Pin 10 (`GPIO 15`, UART0_RXD)| Pin 29 (`GPIO 25`) | Pin 10 (`GPIO 44`) | Optional Host-to-Host link |

---

## 2. Cryptographic Accelerator Socket (J_CRYPTO)

The crypto accelerator connects via a standard 10-pin ($2\times 5$, 2.54mm pitch) male box header or female socket matching the PMOD / Tiny Tapeout demo carrier pinout:

```
          J_CRYPTO Header (Top View)
              +---+---+
   NET_CRP_SCLK | 1 | 2 | NET_CRP_CS#
    NET_CRP_IO0 | 3 | 4 | NET_CRP_IO1
    NET_CRP_IO2 | 5 | 6 | NET_CRP_IO3
   NET_CRP_IRQ# | 7 | 8 | NET_CRP_RST#
          +3.3V | 9 | 10| GND
              +---+---+
```

- **Pin 1 (`SCLK`)**: SPI/QSPI Clock. Driven by host master at up to 80 MHz.
- **Pin 2 (`CS#`)**: Active-low Chip Select. Initiates frame transmission.
- **Pin 3 (`IO0`)**: Data Bit 0 (Standard SPI MOSI during command phase; bidirectional during quad phase).
- **Pin 4 (`IO1`)**: Data Bit 1 (Standard SPI MISO during single-bit read; bidirectional during quad phase).
- **Pin 5 (`IO2`)**: Data Bit 2 (Hardware write-protect / upper quad nybble).
- **Pin 6 (`IO3`)**: Data Bit 3 (Hardware hold / upper quad nybble).
- **Pin 7 (`IRQ#`)**: Active-low hardware completion interrupt. Pulled up to +3.3V via 10k resistor. When the accelerator finishes an Ed25519 verify (12,800 cycles) or SHA-512 block (1,024 cycles), it pulls this line low to wake the host CPU without polling.
- **Pin 8 (`RST#`)**: Hardware reset line (active low), connected to main system reset.
- **Pin 9 (`+3.3V`)**: Power rail supplied by onboard AP2112K-3.3 LDO (up to 600 mA).
- **Pin 10 (`GND`)**: Solid ground reference tied directly to PCB ground plane.

---

## 3. Multiplexing Jumper Settings

The Hat uses standard 3-pin $2.54\text{ mm}$ male headers with 2-pin shunt jumpers (`JP1` through `JP19`) located centrally on the board:

```
         3-Pin Jumper Structure
       [ Pin 1 ] - [ Pin 2 ] - [ Pin 3 ]
       (Host A)     (Device)    (Host B)
```

### Jumper Configuration Modes:

#### Mode A: Form Factor A (Raspberry Pi Zero 2W Host)
*Plug Raspberry Pi into 40-pin stacking header. Leave MCU socket unpopulated.*
- **LoRa Jumpers (`JP_LORA1..6`)**: Install shunts on **Pins 1-2**.
- **Crypto Jumpers (`JP_CRP1..7`)**: Install shunts on **Pins 1-2**.
- **Display & Keypad Jumpers (`JP_UI1..6`)**: Install shunts on **Pins 1-2**.

#### Mode B1: Form Factor B (ESP32-C5 Host + Onboard EBYTE LoRa)
*Plug ESP32-C5-DevKitC-1 into dual female socket headers. Leave Pi header unpopulated.*
- **LoRa Jumpers (`JP_LORA1..6`)**: Install shunts on **Pins 2-3**.
- **Crypto Jumpers (`JP_CRP1..7`)**: Install shunts on **Pins 2-3**.
- **Display & Keypad Jumpers (`JP_UI1..6`)**: Install shunts on **Pins 2-3**.

#### Mode B2: Form Factor B (Heltec WiFi LoRa 32 V4 Host)
*Plug Heltec V4 into socket headers. EBYTE LoRa footprint is unpopulated; Heltec board provides its own +28 dBm radio.*
- **LoRa Jumpers (`JP_LORA1..6`)**: **LEAVE ALL OPEN (NO SHUNTS)** to prevent driving floating traces.
- **Crypto Jumpers (`JP_CRP1..7`)**: Install shunts on **Pins 2-3**.
- **Display & Keypad Jumpers (`JP_UI1..6`)**: Install shunts on **Pins 2-3**.

---

## 4. Power Rails & Distribution

| Rail | Voltage | Source | Maximum Current | Consumers |
| :--- | :--- | :--- | :--- | :--- |
| `VBUS` | 5.0 V | USB-C 16-Pin | 3.0 A (negotiated 5V) | TP4056 Charger, 5V Pass-through to Pi Header |
| `VBAT` | 3.7 V – 4.2 V | 1S LiPo via JST-PH | 2.0 A peak | Auto-power switch P-MOSFET source |
| `VSYS` | 3.6 V – 5.0 V | Auto-Power Path (DMG2305UX) | 2.5 A | AP2112K-3.3 Input, Pi Pin 2/4 (5V rail) |
| `+3.3V`| 3.3 V (regulated) | AP2112K-3.3 LDO | 600 mA continuous | E22 LoRa, Crypto Socket, ST7789 LCD, CardKB |

### Power Path Priority:
1. When **USB-C is connected**: `VBUS` turns off the P-Channel MOSFET (`DMG2305UX`) via gate pull-up through `BAT54C`, powering `VSYS` and the 3.3V regulator directly from USB. Simultaneously, the `TP4056` charges the LiPo battery at up to 1.0 A.
2. When **USB-C is disconnected**: Gate drops to ground via $100\text{ k}\Omega$ resistor, turning the P-MOSFET ON instantly (< 10 µs) with only $0.035\ \Omega$ $R_{DS(on)}$ drop. No resets or brownouts occur.
