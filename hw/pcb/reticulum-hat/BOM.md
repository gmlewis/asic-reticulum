# Universal Reticulum Hat & Carrier: Bill of Materials (BOM)

**Board Revision**: v1.0  
**Target Manufacturing**: PCBWay & JLCPCB SMT Compatible  
**PCB Parameters**: 2-Layer FR-4, $65.0\text{ mm} \times 56.0\text{ mm}$, 1.6 mm thickness, 1 oz Cu, ENIG Gold Finish  

This document lists every component needed to assemble the Universal Reticulum Hat. The design has been optimized so that all passives, diodes, MOSFETs, regulators, and charger ICs use standard JLCPCB "Basic" or low-cost "Preferred" parts (LCSC part numbers included) and readily available DigiKey / Mouser parts for PCBWay turnkey assembly.

---

## 1. Power Management Subsystem

| Designator | Quantity | Component Description | Package / Footprint | JLCPCB / LCSC Part # | PCBWay / DigiKey Part # | Notes |
| :--- | :---: | :--- | :--- | :--- | :--- | :--- |
| **J_USB** | 1 | USB Type-C 16-Pin Receptacle | USB-C-16P-SMD | `C165948` | GCT `USB4105-GF-A` / Mouser | Power input & charging |
| **U_CHG** | 1 | TP4056 1A Standalone Linear LiPo Charger | SOP-8-PP | `C16581` | TP4056 / LCSC | 4.2V float, CC/CV LiPo |
| **U_REG** | 1 | AP2112K-3.3TRG1 600mA Ultra-Low Noise LDO | SOT-23-5 | `C52924` | `AP2112K-3.3TRG1DICT-ND` | Clean 3.3V RF/ASIC power |
| **Q_PWR** | 1 | DMG2305UX P-Channel MOSFET (-20V, -4.2A, 35mΩ) | SOT-23 | `C84411` | `DMG2305UX-7DICT-ND` | Power path auto-switch |
| **D_PWR** | 1 | BAT54C Dual Common-Cathode Schottky (30V, 200mA) | SOT-23 | `C2198` | `BAT54C-7-FDICT-ND` | Low forward drop OR gate |
| **J_BAT** | 1 | JST-PH 2.0mm 2-Pin Shrouded Male Header | JST-PH-2P (Through-hole) | `C23769` | JST `B2B-PH-K-S` / DigiKey | 1S LiPo Battery input |
| **LED_CHG** | 1 | Red LED 0603 (Charging indicator) | 0603 LED | `C2286` | Lite-On `LTST-C190KRKT` | Indicates active charge |
| **LED_DONE** | 1 | Green LED 0603 (Charge complete indicator) | 0603 LED | `C2290` | Lite-On `LTST-C190KGKT` | Indicates 4.2V cutoff reached |
| **R_CC1, R_CC2** | 2 | $5.1\text{ k}\Omega$ 1% 1/10W Thick Film Resistor | 0603 | `C23186` | Yageo `RC0603FR-075K1L` | Type-C CC UFP pull-downs |
| **R_PROG** | 1 | $1.2\text{ k}\Omega$ 1% 1/10W Resistor (1.0A Charge Rate) | 0603 | `C22787` | Yageo `RC0603FR-071K2L` | Sets TP4056 charging current |
| **R_LED1, R_LED2** | 2 | $1.0\text{ k}\Omega$ 1% 1/10W Resistor | 0603 | `C21190` | Yageo `RC0603FR-071KL` | LED current limit (~2 mA) |
| **R_GATE** | 1 | $100\text{ k}\Omega$ 5% 1/10W Resistor | 0603 | `C25804` | Yageo `RC0603JR-07100KL` | MOSFET gate pull-down |
| **C_IN1, C_OUT1** | 2 | $10\ \mu\text{F}$ 16V X5R/X7R Ceramic Capacitor | 0805 | `C15850` | Samsung `CL21A106KOQNNNE` | USB/LDO input bulk bypass |
| **C_DEC1..C_DEC4** | 4 | $100\text{ nF}$ 25V X7R Ceramic Capacitor | 0603 | `C14663` | Samsung `CL10B104KB8NNNC` | High-frequency decoupling |

---

## 2. LoRa Radio Subsystem (EBYTE SX1262)

| Designator | Quantity | Component Description | Package / Footprint | JLCPCB / LCSC Part # | PCBWay / DigiKey Part # | Notes |
| :--- | :---: | :--- | :--- | :--- | :--- | :--- |
| **U_LORA** | 1 | EBYTE E22-900M22S (Semtech SX1262, +22 dBm) | SMD Castellated Module | `C963388` | EBYTE E22-900M22S | 868 / 915 MHz LoRa tranceiver |
| **J_SMA** | 1 | SMA Female Edge-Mount Jack for 1.6mm PCB | Edge-SMA-1.6mm | `C112398` | Amphenol `132136` / DigiKey | High-gain omni/Yagi antenna |
| **J_UFL** | 1 | IPEX / u.FL Coaxial Receptacle (SMD) | IPEX / MHF-1 | `C14878` | Hirose `U.FL-R-SMT-1(10)` | Compact internal antenna |
| **C_RF1** | 1 | $10\ \mu\text{F}$ 16V X5R Capacitor | 0805 | `C15850` | Samsung `CL21A106KOQNNNE` | LoRa PA burst buffer |
| **C_RF2** | 1 | $100\text{ nF}$ 25V X7R Capacitor | 0603 | `C14663` | Samsung `CL10B104KB8NNNC` | High-frequency RF decoupling |
| **R_DIO1** | 1 | $100\text{ k}\Omega$ 5% Resistor | 0603 | `C25804` | Yageo `RC0603JR-07100KL` | DIO1 pull-down |

---

## 3. Cryptographic Accelerator Socket (ASIC / FPGA)

| Designator | Quantity | Component Description | Package / Footprint | JLCPCB / LCSC Part # | PCBWay / DigiKey Part # | Notes |
| :--- | :---: | :--- | :--- | :--- | :--- | :--- |
| **J_CRYPTO** | 1 | $2\times 5$ (10-Pin) 2.54mm Box Header / Female Socket | 2x5 2.54mm Box Header | `C2883734` | Wurth `61201021621` / DigiKey | 7-Pin QSPI + IRQ# accelerator |
| **R_IRQ** | 1 | $10\text{ k}\Omega$ 1% 1/10W Resistor | 0603 | `C25803` | Yageo `RC0603FR-0710KL` | IRQ# active-low pull-up to 3.3V |
| **C_CRP** | 1 | $100\text{ nF}$ 25V X7R Capacitor | 0603 | `C14663` | Samsung `CL10B104KB8NNNC` | Local accelerator decoupling |

---

## 4. Display & Keyboard UI Interface

| Designator | Quantity | Component Description | Package / Footprint | JLCPCB / LCSC Part # | PCBWay / DigiKey Part # | Notes |
| :--- | :---: | :--- | :--- | :--- | :--- | :--- |
| **J_LCD** | 1 | $1\times 8$ 2.54mm Pitch Female Header | 1x8 2.54mm Socket | `C224364` | Sullins `PPTC081LFBN-RC` | ST7789 2.8" SPI TFT LCD |
| **J_FPC** | 1 | 14-Pin 0.5mm Pitch Bottom-Contact FPC Socket | FPC-14P-0.5mm | `C145558` | Hirose `FH12-14S-0.5SH` | Optional direct flat ribbon |
| **Q_BL** | 1 | 2N3904 NPN BJT Transistor (40V, 200mA) | SOT-23 | `C2137` | `2N3904-TPMSCT-ND` | Backlight PWM switch |
| **R_BL** | 1 | $1.0\text{ k}\Omega$ 1% Resistor | 0603 | `C21190` | Yageo `RC0603FR-071KL` | Backlight base limit resistor |
| **J_STEMMA**| 1 | 4-Pin JST-SH 1.0mm Horizontal SMD Header | JST-SH-4P-SMD | `C145946` | SparkFun `PRT-14417` / Adafruit | STEMMA QT / Qwiic CardKB jack |
| **J_KB** | 1 | $1\times 4$ 2.54mm Male Header | 1x4 2.54mm Header | `C224360` | Sullins `PRPC004SAAN-RC` | Breadboard / Dupont keyboard |
| **R_PU1, R_PU2**| 2 | $4.7\text{ k}\Omega$ 1% 1/10W Resistor | 0603 | `C23162` | Yageo `RC0603FR-074K7L` | I2C bus pull-ups to 3.3V |

---

## 5. Host Socket Headers & Multiplexing Jumpers

| Designator | Quantity | Component Description | Package / Footprint | JLCPCB / LCSC Part # | PCBWay / DigiKey Part # | Notes |
| :--- | :---: | :--- | :--- | :--- | :--- | :--- |
| **J_PI** | 1 | $2\times 20$ (40-Pin) Extra-Tall Stacking Female Header | 2x20 2.54mm Header | `C224369` | Adafruit `1112` / DigiKey | Raspberry Pi Zero 2W / Milk-V |
| **J_MCU_L, J_MCU_R** | 2 | $1\times 22$ (22-Pin) 2.54mm Pitch Female Headers | 1x22 2.54mm Socket | `C224378` | Sullins `PPTC221LFBN-RC` | ESP32-C5-DevKitC-1 / Heltec V4 |
| **JP1 .. JP19** | 19 | $1\times 3$ 2.54mm Breakable Male Pin Headers | 1x3 2.54mm Pin Header | `C224359` | Sullins `PRPC003SAAN-RC` | Bus multiplexing blocks |
| **SHUNTS** | 19 | Standard 2.54mm Open-Top Shunt Jumpers (Black) | 2.54mm Shunt | `C2902344` | Wurth `60900213410` | Route Host A vs Host B |

---

## 6. Mechanical Standoffs & Hardware

| Item Description | Quantity | Dimensions / Specs | Part Number / Reference | Purpose |
| :--- | :---: | :--- | :--- | :--- |
| **Brass Hex Standoffs** | 4 | M2.5 Thread, 11 mm Body Length | McMaster-Carr `92015A110` | HAT standoff height above Pi |
| **M2.5 Pan Head Screws** | 8 | M2.5 $\times$ 5 mm Stainless Steel | McMaster-Carr `92000A102` | Secure HAT to Pi and enclosure |
| **Polymer Acrylic Spacer** | 1 | 2.8" LCD Foam Double-Sided Adhesive | Adafruit `Foam-LCD` | Shock mounting TFT to PCB |
| **CardKB Keyboard** | 1 | M5Stack CardKB 50-Key I2C Keyboard | M5Stack `U035` / DigiKey | Ultra-compact QWERTY entry |
| **LiPo Battery Pack** | 1 | 3.7V 1200mAh – 2000mAh 1S LiPo with JST-PH | Adafruit `258` (1200mAh) / `2011` | 6–10 hour autonomous runtime |
