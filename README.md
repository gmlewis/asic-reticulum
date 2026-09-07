# asic-reticulum

![gonomadnet mascot
The Go gopher was designed by Renee French.
The design is licensed under the Creative Commons 4.0 Attribution license.](assets/gonomadnet-mascot.png)

Hardware accelerator ASIC for the [Reticulum Network Stack](https://reticulum.network) authored in **SpinalHDL** (Scala DSL).

This project implements the silicon offload targets specified in [`ASIC-Plans.md`](https://github.com/gmlewis/go-reticulum/blob/master/ASIC-Plans.md),
targeting open-source silicon flows (**Tiny Tapeout**, **OpenLane**, **SkyWater sky130**, and **IHP SG13G2**).

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
│   │       │   └── X25519Ladder.scala     # Constant-time Montgomery ladder X25519 engine
│   │       └── bus/
│           ├── QspiSlave.scala          # 4-bit QSPI slave transceiver with Stream RX/TX
│           ├── QspiCommandDecoder.scala    # Command decoder FSM & accelerator control lines
│           └── QspiTop.scala               # Top-level 7-pin physical interface & interconnect
├── sim/                               # Table-driven simulation test suites (ScalaTest + SpinalSim)
│   └── reticulum/
│       ├── crypto/
│       │   ├── LeadZeroCounterTest.scala # 32-bit & 256-bit priority sweeps
│       │   ├── Sha256RoundTest.scala     # FIPS vectors & randomized stress tests
│       │   ├── Sha256PipeTest.scala      # Pipelining, midstate restore & backpressure tests
│       │   ├── StamperTest.scala         # Autonomous candidate search & IRQ verification
│       │   ├── Field25519Test.scala      # GF(2^255-19) add/sub/mul/sqr/reduction tests
│       │   └── X25519LadderTest.scala    # RFC 7748 Vectors 1 & 2 + abort verification
│       ├── bus/
│       │   ├── QspiSlaveTest.scala          # Multi-byte RX/TX & CS frame reset tests
│       │   ├── QspiCommandDecoderTest.scala # Opcode decoding, payload streaming & IRQ pulses
│       │   └── QspiTopTest.scala            # End-to-end QSPI grinding, IRQ & readout verification
│       └── parity/
│           ├── GoldenVectors.scala          # Precomputed golden vectors generated from go-reticulum
│           └── GoReticulumParityTest.scala  # Cross-repo verification harness (Stamper, QspiTop, Sha256Pipe)
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
- **Generate Top-Level QSPI Crypto Engine (`QspiTop.v`)**:
  ```bash
  sbt "runMain reticulum.bus.QspiTopVerilog"
  ```

Inspect the generated outputs:
```bash
cat hw/gen/LeadZeroCounter.v
cat hw/gen/Sha256Round.v
cat hw/gen/Sha256Pipe.v
cat hw/gen/Stamper.v
cat hw/gen/FieldMultiplier.v
cat hw/gen/X25519Ladder.v
cat hw/gen/QspiSlave.v
cat hw/gen/QspiTop.v
```

---

## 5. Implementation Roadmap

- [x] **Milestone 0**: Repository setup, build system (`build.sbt`), and SpinalHDL toolchain validation.
- [x] **Milestone 1**: Core primitives — `LeadZeroCounter` and `Sha256Round`.
- [x] **Milestone 2**: Multi-stage pipelined SHA-256 engine with midstate restore register.
- [x] **Milestone 3**: Autonomous IFAC Hashcash Stamp Grinder with nonce streaming and `meetsTarget` interrupt assertion.
- [x] **Milestone 4**: 4-bit QSPI slave interface (`Stream` handshake + command decoder FSM + 7-pin `QspiTop` integration).
- [x] **Milestone 5**: Verification harness comparing SpinalSim / Verilator against `go-reticulum` golden test vectors.
- [x] **Milestone 6**: Montgomery ladder (X25519 / Ed25519) field arithmetic core.
- [ ] **Milestone 7**: AES-128-CBC + HMAC-SHA256 Token engine.
- [ ] **Milestone 8**: Top-level chip integration, OpenLane synthesis, and Tiny Tapeout GDS submission.
