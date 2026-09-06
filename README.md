# asic-reticulum

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
│   │       │   ├── Sha256Round.scala      # Single-cycle SHA-256 compression step
│   │       │   ├── Sha256Pipe.scala       # (Upcoming) Pipelined SHA-256 engine
│   │       │   └── Stamper.scala          # (Upcoming) Full autonomous stamp grinder
│   │       └── bus/
│   │           └── QspiSlave.scala        # (Upcoming) 4-bit QSPI slave with Stream interface
│   └── gen/                               # Synthesis-ready generated Verilog output
```

---

## 4. Quick Start

### Prerequisites
- **Java 17** (LTS)
- **sbt** (Scala Build Tool)

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

Inspect the generated outputs:
```bash
cat hw/gen/LeadZeroCounter.v
cat hw/gen/Sha256Round.v
```

---

## 5. Implementation Roadmap

- [x] **Milestone 0**: Repository setup, build system (`build.sbt`), and SpinalHDL toolchain validation.
- [x] **Milestone 1**: Core primitives — `LeadZeroCounter` and `Sha256Round`.
- [ ] **Milestone 2**: Multi-stage pipelined SHA-256 engine with midstate restore register.
- [ ] **Milestone 3**: Autonomous IFAC Hashcash Stamp Grinder with nonce streaming and `meetsTarget` interrupt assertion.
- [ ] **Milestone 4**: 4-bit QSPI slave interface (`Stream` handshake + command decoder FSM).
- [ ] **Milestone 5**: Verification harness comparing SpinalSim / Verilator against `go-reticulum` golden test vectors.
- [ ] **Milestone 6**: Montgomery ladder (X25519 / Ed25519) field arithmetic core.
- [ ] **Milestone 7**: AES-128-CBC + HMAC-SHA256 Token engine.
- [ ] **Milestone 8**: Top-level chip integration, OpenLane synthesis, and Tiny Tapeout GDS submission.
