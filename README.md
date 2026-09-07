# asic-reticulum

![gonomadnet mascot
The Go gopher was designed by Renee French.
The design is licensed under the Creative Commons 4.0 Attribution license.](assets/gonomadnet-mascot.png)

[![CI](https://github.com/gmlewis/asic-reticulum/actions/workflows/ci.yml/badge.svg)](https://github.com/gmlewis/asic-reticulum/actions/workflows/ci.yml)

Hardware accelerator ASIC for the [Reticulum Network Stack](https://reticulum.network) authored in **SpinalHDL** (Scala DSL).

This project implements the silicon offload targets specified in [`ASIC-Plans.md`](https://github.com/gmlewis/go-reticulum/blob/master/ASIC-Plans.md),
targeting open-source silicon flows (**Tiny Tapeout**, **OpenLane**, **SkyWater sky130**, and **IHP SG13G2**).

For an exhaustive technical deep-dive into all design decisions, mathematical formulations, hardware pipelines, and verification results from Milestones 0 through 9, see [`Design-Walkthrough.md`](Design-Walkthrough.md).

> [!TIP]
> ### Looking to build or flash the hardware devices?
> Jump straight to the [**Reticulum Hardware Projects Guide**](https://github.com/gmlewis/asic-reticulum/tree/master/Hardware-Projects-Guide.md) for complete purchasing BOMs, step-by-step assembly, pre-built binary downloads, and zero-install in-browser web flashing for all three standalone off-grid hardware projects:
> - **Project 1: Pocket Linux Terminal** (Raspberry Pi Zero 2W + SPI LCD + CardKB + LoRa + full TUI)
> - **Project 2: Standalone Pocket Communicator** (ESP32-C5 / Heltec V4 + LCD + CardKB + LoRa)
> - **Project 3: Autonomous Pocket Hub & Repeater** (ESP32-C5 + LoRa + Wi-Fi 6 SoftAP)

---

## 1. Architectural Overview

`asic-reticulum` offloads the computationally intensive, fixed-function cryptographic bottlenecks of Reticulum:

1. **LXMF Stamp Grinding (SHA-256 Hashcash)**:
   - Deeply pipelined SHA-256 compression engine with **midstate restore**.
   - Autonomous nonce increment and single-cycle Leading Zero Counter (LZC).
   - Accelerates proof-of-work generation by orders of magnitude while preserving battery life on embedded nodes.
2. **X25519 ECDH & Ed25519 Sign/Verify**:
   - Shared Montgomery ladder field arithmetic unit (~20–40k gates).
   - Offloads link establishment handshakes and announce signature verifications.
3. **AES-128-CBC + HMAC-SHA256 (Fernet Token Engine)**:
   - Streaming packet encryption/decryption pipeline.
   - Accelerates `gorrcd` chat room broadcast fanouts (burst per-member link encryption).
4. **Host Interconnect: 4-bit Quad-SPI (QSPI @ 40–80 MHz) + Hardware IRQ**:
   - 7-pin interface (`CLK`, `CS`, `IO0..IO3`, `IRQ`).
   - Streams 20–40 MB/s directly via the host MCU's General DMA (GDMA) engine (e.g. ESP32-C5) with **zero CPU polling** and **zero memory copies**.

---

## 2. Why SpinalHDL?

- **100% Free & Open-Source (LGPL)**: Runs completely locally with standard `sbt` and JVM; zero proprietary licenses or cloud preprocessors.
- **First-Class Streams & Bus Protocols (`spinal.lib`)**: Native `Stream` (`valid`/`ready` handshakes) with automatic backpressure, skid buffers, and FIFOs. Generates APB3 and AXI4-Lite register maps in single lines.
- **Pipelining Without the Pain (`spinal.lib.pipeline`)**: Expressive stage retiming and hazard detection without manual register plumbing.
- **Native Symbiosis with RISC-V**: Open-source softcores **VexRiscv** (RV32) and **NaxRiscv** (RV64) are written in SpinalHDL, allowing custom instruction co-processor extensions (`Plugin[VexRiscv]`).
- **Clean Verilog Output**: Compiles into standard, human-readable Verilog-2001 or SystemVerilog consumed natively by OpenLane, Yosys, and Tiny Tapeout.

---

## 3. Project Structure

```
asic-reticulum/
├── LICENSE
├── README.md
├── build.sbt                     # Scala & SpinalHDL dependency configuration
├── project/
│   └── build.properties         # sbt runner version (1.10.7)
├── hw/
│   ├── spinal/
│   │   └── reticulum/
│   │       ├── crypto/
│   │       │   ├── LeadZeroCounter.scala  # Difficulty comparator for IFAC stamps
│   │       │   ├── Sha256Constants.scala  # FIPS 180-4 H0 vector and K0..K63 constants
│   │       │   ├── Sha256Round.scala      # Single-cycle SHA-256 compression step
│   │       │   ├── Sha256Pipe.scala       # Pipelined SHA-256 engine with midstate restore
│   │       │   ├── Stamper.scala          # Autonomous IFAC Hashcash stamp grinder
│   │       │   ├── Field25519.scala       # GF(2^255-19) modular arithmetic primitives
│   │       │   ├── X25519Ladder.scala     # Constant-time Montgomery ladder X25519 engine
│   │       │   ├── AesConstants.scala     # AES S-Box, InvS-Box, and Rcon constants
│   │       │   ├── AesCore.scala          # 10-cycle iterative AES-128 encrypt/decrypt core
│   │       │   ├── HmacSha256.scala       # RFC 2104 / FIPS 198-1 HMAC-SHA256 streaming core
│   │       │   └── TokenEngine.scala      # Fernet-style AES-128-CBC + HMAC-SHA256 packet engine
│   │       ├── bus/
│   │       │   ├── QspiSlave.scala          # 4-bit QSPI slave transceiver with Stream RX/TX
│   │       │   ├── QspiCommandDecoder.scala    # Command decoder FSM & accelerator control lines
│   │       │   └── QspiTop.scala               # Top-level 7-pin physical interface & interconnect
│   │       ├── fpga/
│   │       │   └── FpgaTop.scala            # Multi-engine FPGA top-level wrapper
│   │       └── tt/
│   │           └── TinyTapeoutTop.scala     # Tiny Tapeout top module (tt_um_gmlewis_reticulum)
├── openlane/                          # OpenLane physical design & synthesis configs
│   ├── config.json                    # OpenLane 2 configuration (Sky130 50 MHz)
│   ├── config.tcl                     # OpenLane 1 configuration
│   └── pin_order.cfg                  # Macro perimeter pin placement
├── test/                              # Cocotb verification testbench for Tiny Tapeout CI
│   ├── tb.v                           # Verilog simulation top wrapper
│   ├── test.py                        # Cocotb test cases (Status, X25519, Token Seal/Open)
│   └── Makefile                       # Icarus Verilog + Cocotb makefile
├── src/                               # Standalone synthesis Verilog for Tiny Tapeout submission
│   └── tt_um_gmlewis_reticulum.v
├── info.yaml                          # Tiny Tapeout project manifest & pinout specification
├── sim/                               # Table-driven simulation test suites (ScalaTest + SpinalSim)
│   └── reticulum/
│       ├── crypto/
│       │   ├── LeadZeroCounterTest.scala # 32-bit & 256-bit priority sweeps
│       │   ├── Sha256RoundTest.scala     # FIPS vectors & randomized stress tests
│       │   ├── Sha256PipeTest.scala      # Pipelining, midstate restore & backpressure tests
│       │   ├── StamperTest.scala         # Autonomous candidate search & IRQ verification
│       │   ├── Field25519Test.scala      # GF(2^255-19) add/sub/mul/sqr/reduction tests
│       │   ├── X25519LadderTest.scala    # RFC 7748 Vectors 1 & 2 + abort verification
│       │   ├── AesCoreTest.scala         # FIPS 197 AES-128 encrypt & decrypt verification
│       │   ├── HmacSha256Test.scala      # RFC 4231 HMAC test cases & multi-block verification
│       │   └── TokenEngineTest.scala     # In-place Seal, Open, and PKCS#7 padding validation
│       ├── bus/
│       │   ├── QspiSlaveTest.scala          # Multi-byte RX/TX & CS frame reset tests
│       │   ├── QspiCommandDecoderTest.scala # Opcode decoding, payload streaming & IRQ pulses
│       │   └── QspiTopTest.scala            # End-to-end QSPI grinding, IRQ & readout verification
│       ├── tt/
│       │   └── TinyTapeoutTopTest.scala     # TT pin mapping, bus turnaround & crypto tests
│       └── parity/
│           ├── GoldenVectors.scala          # Precomputed golden vectors generated from go-reticulum
│           └── GoReticulumParityTest.scala  # Cross-repo verification harness (Stamper, QspiTop, Sha256Pipe, X25519, Token)
└── gen/                               # Synthesis-ready generated Verilog output
```

---

## 4. Quick Start

### Prerequisites
- **Java 17** (LTS)
- **sbt** (Scala Build Tool)
- **Verilator 5** (for cycle-accurate SpinalSim testbenches)

On macOS:
```bash
# scala for SpinalHDL (in ~/.bashrc):
startscala() {
  export PATH="$HOME/.jenv/bin:/opt/homebrew/opt/openjdk@17/bin:$PATH"
  eval "$(jenv init -)"
}

# Initialize Java 17 and sbt environment from command-line:
startscala
```

### Compiling the Hardware Descriptions
```bash
sbt compile
```

### Running the Unit Test Suites
```bash
sbt test
```

### Generating Verilog
To generate standard, synthesis-ready Verilog into `hw/gen/`:

- **Generate Leading Zero Counter (`LeadZeroCounter.v`)**:
  ```bash
  sbt "runMain reticulum.crypto.LeadZeroCounterVerilog"
  ```
- **Generate SHA-256 Round Function (`Sha256Round.v`)**:
  ```bash
  sbt "runMain reticulum.crypto.Sha256RoundVerilog"
  ```
- **Generate SHA-256 Pipelined Engine (`Sha256Pipe.v`)**:
  ```bash
  sbt "runMain reticulum.crypto.Sha256PipeVerilog"
  ```
- **Generate Autonomous Stamp Grinder (`Stamper.v`)**:
  ```bash
  sbt "runMain reticulum.crypto.StamperVerilog"
  ```
- **Generate 4-bit QSPI Slave Transceiver (`QspiSlave.v`)**:
  ```bash
  sbt "runMain reticulum.bus.QspiSlaveVerilog"
  ```
- **Generate Field Multiplier (`FieldMultiplier.v`)**:
  ```bash
  sbt "runMain reticulum.crypto.FieldMultiplierVerilog"
  ```
- **Generate X25519 Montgomery Ladder (`X25519Ladder.v`)**:
  ```bash
  sbt "runMain reticulum.crypto.X25519LadderVerilog"
  ```
- **Generate AES-128 Iterative Core (`AesCore.v`)**:
  ```bash
  sbt "runMain reticulum.crypto.AesCoreVerilog"
  ```
- **Generate Streaming HMAC-SHA256 Engine (`HmacSha256.v`)**:
  ```bash
  sbt "runMain reticulum.crypto.HmacSha256Verilog"
  ```
- **Generate Token Seal/Open Engine (`TokenEngine.v`)**:
  ```bash
  sbt "runMain reticulum.crypto.TokenEngineVerilog"
  ```
- **Generate Top-Level QSPI Crypto Engine (`QspiTop.v`)**:
  ```bash
  sbt "runMain reticulum.bus.QspiTopVerilog"
  ```
- **Generate FPGA Accelerator Top-Level (`FpgaTop.v`)**:
  ```bash
  sbt "runMain reticulum.fpga.FpgaTopVerilog"
  ```
- **Generate Tiny Tapeout Top-Level (`tt_um_gmlewis_reticulum.v`)**:
  ```bash
  sbt "runMain reticulum.tt.TinyTapeoutVerilog"
  ```

Inspect the generated outputs:
```bash
cat hw/gen/LeadZeroCounter.v
cat hw/gen/Sha256Round.v
cat hw/gen/Sha256Pipe.v
cat hw/gen/Stamper.v
cat hw/gen/FieldMultiplier.v
cat hw/gen/X25519Ladder.v
cat hw/gen/AesCore.v
cat hw/gen/HmacSha256.v
cat hw/gen/TokenEngine.v
cat hw/gen/QspiSlave.v
cat hw/gen/QspiTop.v
cat hw/gen/FpgaTop.v
cat hw/gen/tt_um_gmlewis_reticulum.v
cat src/tt_um_gmlewis_reticulum.v
```

---

## 5. FPGA & ESP32-C5 Hardware-In-The-Loop (HIL) Testbed

Milestone 8 provides complete synthesis wrappers, physical pin constraints, and host firmware drivers for real-world hardware validation:

- **Target FPGA Platforms**:
  - **Sipeed Tang Primer 25K** (Gowin GW5A-25, 23k LUTs, 4x TokenEngines) — constraints in [`hw/fpga/tang_primer_25k.cst`](hw/fpga/tang_primer_25k.cst).
  - **QMTECH AMD Artix-7** (XC7A35T / XC7A100T) — constraints in [`hw/fpga/qmtech_artix7.xdc`](hw/fpga/qmtech_artix7.xdc).
  - Clock & timing false paths defined in [`hw/fpga/timing.sdc`](hw/fpga/timing.sdc).
- **ESP32-C5 Host Driver** ([`fw/esp32c5/`](fw/esp32c5/)):
  - ESP-IDF C driver using hardware GP-SPI master (`SPI2_HOST`) with GDMA channel up to 40-80 MHz.
  - Edge-triggered interrupt handling on `IRQ_N` with zero host CPU polling.
  - Complete self-test suite exercising X25519, 4-way Token pool, and IFAC Hashcash grinding.
- **Automated HIL Test Runner** ([`tools/hil/hil_test_runner.py`](tools/hil/hil_test_runner.py)):
  ```bash
  # Hardware test over USB-UART:
  python3 tools/hil/hil_test_runner.py --port /dev/ttyUSB0 --baud 115200

  # Simulation mock test:
  python3 tools/hil/hil_test_runner.py --sim
  ```

---

## 6. Tiny Tapeout & OpenLane ASIC Synthesis Flow

Milestone 9 packages the complete cryptographic accelerator for tapeout on SkyWater 130nm (`sky130_fd_sc_hd`) through Tiny Tapeout.

### Tiny Tapeout Pinout Mapping

| Pin Name | Type | Signal | Function Description |
| :--- | :--- | :--- | :--- |
| `clk` | Input | `clk` | System clock (typically 20–50 MHz) |
| `rst_n` | Input | `rst_n` | Active-low asynchronous reset |
| `ena` | Input | `ena` | Tile enable from Tiny Tapeout multiplexer |
| `ui_in[0]` | Input | `qspi_sclk` | Quad-SPI bus clock (Mode 0) |
| `ui_in[1]` | Input | `qspi_cs_n` | Quad-SPI active-low chip select |
| `ui_in[7:2]` | Input | — | Reserved inputs (tied low internally) |
| `uio[0]` | Bidirectional | `qspi_io0` | 4-bit Quad-SPI Data Bit 0 (MOSI in 1-bit mode) |
| `uio[1]` | Bidirectional | `qspi_io1` | 4-bit Quad-SPI Data Bit 1 (MISO in 1-bit mode) |
| `uio[2]` | Bidirectional | `qspi_io2` | 4-bit Quad-SPI Data Bit 2 (WP# in standard SPI) |
| `uio[3]` | Bidirectional | `qspi_io3` | 4-bit Quad-SPI Data Bit 3 (HOLD# in standard SPI) |
| `uio[7:4]` | Bidirectional | — | Reserved bidirectional lines (high-Z) |
| `uo_out[0]` | Output | `qspi_irq_n` | Active-low completion interrupt to host MCU |
| `uo_out[1]` | Output | `busy` | Active-high status (engine actively computing) |
| `uo_out[2]` | Output | `heartbeat` | ~1.5 Hz diagnostic blinker (50 MHz / 2^25) |
| `uo_out[7:3]` | Output | — | Reserved status outputs (driven low) |

### Automated OpenLane Synthesis

Physical layout and GDS generation targeting `sky130A` standard cells:

```bash
# Automated local OpenLane run (via Docker):
./scripts/run-openlane.sh
```

Configuration files:
- [`openlane/config.json`](openlane/config.json): OpenLane 2 configuration with 50 MHz clock constraint (`CLOCK_PERIOD = 20.0 ns`).
- [`openlane/config.tcl`](openlane/config.tcl): Backward-compatible OpenLane 1 configuration.
- [`openlane/pin_order.cfg`](openlane/pin_order.cfg): Standard perimeter pin placements conforming to Tiny Tapeout macro slots.
- [`info.yaml`](info.yaml): Tiny Tapeout metadata manifest (tile allocation: `4x2`, 50 MHz clock).

### Cocotb Hardware Verification

Tiny Tapeout automated CI verifies the top-level netlist using Cocotb and Icarus Verilog:

```bash
# Run cocotb testbench:
make -C test
```

Tests validate status register readout, 256-bit X25519 point multiplication, and Token seal/open authenticated encryption roundtrips through the top-level pins.

---

## 7. Implementation Roadmap

- [x] **Milestone 0**: Repository setup, build system (`build.sbt`), and SpinalHDL toolchain validation.
- [x] **Milestone 1**: Core primitives — `LeadZeroCounter` and `Sha256Round`.
- [x] **Milestone 2**: Multi-stage pipelined SHA-256 engine with midstate restore register.
- [x] **Milestone 3**: Autonomous IFAC Hashcash Stamp Grinder with nonce streaming and `meetsTarget` interrupt assertion.
- [x] **Milestone 4**: 4-bit QSPI slave interface (`Stream` handshake + command decoder FSM + 7-pin `QspiTop` integration).
- [x] **Milestone 5**: Verification harness comparing SpinalSim / Verilator against `go-reticulum` golden test vectors.
- [x] **Milestone 6**: Montgomery ladder (X25519 / Ed25519) field arithmetic core.
- [x] **Milestone 7**: AES-128-CBC + HMAC-SHA256 Token engine (with parameterized multi-engine pool support).
- [x] **Milestone 8**: FPGA emulation, synthesis, and hardware-in-the-loop (HIL) testbed with ESP32-C5.
- [x] **Milestone 9**: Top-level chip integration, OpenLane synthesis, and Tiny Tapeout GDS submission.
