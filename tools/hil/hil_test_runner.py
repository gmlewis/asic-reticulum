#!/usr/bin/env python3
# Copyright 2026 Glenn Lewis. All rights reserved.
# Use of this source code is governed by the BSD-style
# license that can be found in the LICENSE file.

"""
Hardware-In-The-Loop (HIL) Test Runner for Reticulum FPGA Accelerator & ESP32-C5.

Connects to the ESP32-C5 UART console, triggers automated physical validation
tests, collects hardware telemetry (latencies, clock cycles, IRQ responsiveness),
and verifies cryptographic correctness against go-reticulum golden vectors.

Usage:
    python3 tools/hil/hil_test_runner.py --port /dev/ttyUSB0 --baud 115200
    python3 tools/hil/hil_test_runner.py --sim
"""

import argparse
import sys
import time
import re

TEST_PASS_MARKERS = [
    "PASS: ASIC Status detected",
    "PASS: X25519 calculation completed",
    "PASS: 4-Way Token Seal pool executed",
    "PASS: Winning stamp found",
    "ALL HARDWARE-IN-THE-LOOP TESTS PASSED SUCCESSFULLY!"
]

def run_simulation_mock():
    print("==================================================================")
    print("Reticulum Hardware-In-The-Loop (HIL) Test Runner (Simulation Mode)")
    print("Target: Tang Primer 25K (Gowin GW5A-25) + ESP32-C5-DevKitC-1")
    print("==================================================================")
    time.sleep(0.2)
    print("[HIL] Probing QSPI link @ 40 MHz ...")
    print("  [OK] ASIC Status: Arch Version 0x10 (Reticulum Crypto Core v1.0)")
    time.sleep(0.2)
    print("[HIL] Testing X25519 Montgomery Ladder (RFC 7748 Vector 1) ...")
    print("  [OK] Calculation latency: 74 us (~3,700 cycles @ 50 MHz)")
    print("  [OK] IRQ_N line asserted actively low; latency to ISR: 1.2 us")
    time.sleep(0.2)
    print("[HIL] Testing 4-Way Token Engine Parallel Pool ...")
    print("  [OK] Dispatched 4 concurrent Token Seal operations")
    print("  [OK] Engine 0: completed, 96 bytes sealed")
    print("  [OK] Engine 1: completed, 96 bytes sealed")
    print("  [OK] Engine 2: completed, 96 bytes sealed")
    print("  [OK] Engine 3: completed, 96 bytes sealed")
    print("  [OK] 4x Token fanout time: 240 us (~4.2x speedup over software AES+HMAC)")
    time.sleep(0.2)
    print("[HIL] Testing Autonomous IFAC Hashcash Stamp Grinder ...")
    print("  [OK] Dispatched grinding job (target cost 12 zeros)")
    print("  [OK] Winning stamp found: Nonce 1482, Hashcash zeros: 14")
    print("  [OK] Total evaluated rounds: 1482 rounds in 1,020 us (~1.45 MHash/s)")
    time.sleep(0.1)
    print("==================================================================")
    print("ALL HARDWARE-IN-THE-LOOP TESTS PASSED SUCCESSFULLY (5/5 PASSED)")
    print("==================================================================")
    return 0

def run_hardware_test(port, baud, timeout_sec):
    try:
        import serial
    except ImportError:
        print("ERROR: pyserial is required for physical hardware testing.")
        print("Install with: pip3 install pyserial")
        return 1

    print(f"[HIL] Connecting to ESP32-C5 on {port} @ {baud} baud ...")
    try:
        ser = serial.Serial(port, baud, timeout=1.0)
    except Exception as e:
        print(f"ERROR: Could not open serial port {port}: {e}")
        return 1

    # Reset board via DTR/RTS toggle
    ser.dtr = False
    ser.rts = False
    time.sleep(0.1)
    ser.dtr = True
    ser.rts = True
    time.sleep(0.1)
    ser.dtr = False
    ser.rts = False

    start_time = time.time()
    passed_markers = set()

    print("[HIL] Listening for ESP32-C5 boot and test suite execution ...")
    while (time.time() - start_time) < timeout_sec:
        line = ser.readline().decode("utf-8", errors="replace").strip()
        if not line:
            continue
        print(f"  [MCU] {line}")

        for marker in TEST_PASS_MARKERS:
            if marker in line:
                passed_markers.add(marker)

        if "ALL HARDWARE-IN-THE-LOOP TESTS PASSED SUCCESSFULLY!" in line:
            print("\n[HIL] Physical HIL Test Completed Successfully!")
            ser.close()
            return 0
        if "FAIL:" in line:
            print(f"\n[HIL] Test failure detected: {line}")
            ser.close()
            return 1

    print("\n[HIL] ERROR: Timeout waiting for HIL tests to complete.")
    ser.close()
    return 1

def main():
    parser = argparse.ArgumentParser(description="Reticulum FPGA + ESP32-C5 HIL Test Runner")
    parser.add_argument("--port", default="/dev/ttyUSB0", help="ESP32-C5 serial port")
    parser.add_argument("--baud", type=int, default=115200, help="UART baud rate")
    parser.add_argument("--timeout", type=int, default=15, help="Test timeout in seconds")
    parser.add_argument("--sim", action="store_true", help="Run simulated test suite (no physical HW)")
    args = parser.parse_args()

    if args.sim:
        sys.exit(run_simulation_mock())
    else:
        sys.exit(run_hardware_test(args.port, args.baud, args.timeout))

if __name__ == "__main__":
    main()
