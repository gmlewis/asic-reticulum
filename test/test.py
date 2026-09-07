# SPDX-License-Identifier: Apache-2.0

import cocotb
from cocotb.clock import Clock
from cocotb.triggers import ClockCycles

OP_STATUS       = 0x01
OP_ABORT        = 0x02
OP_IRQ_CLEAR    = 0x03
OP_STAMP_START  = 0x10
OP_STAMP_READ   = 0x11
OP_X25519_MULT  = 0x20
OP_X25519_READ  = 0x21
OP_TOKEN_SEAL   = 0x30
OP_TOKEN_OPEN   = 0x31
OP_TOKEN_READ   = 0x32


async def qspi_write_byte(dut, byte_val):
    """Writes one byte over QSPI 4-bit bus (high nibble then low nibble)."""
    hi = (byte_val >> 4) & 0x0F
    lo = byte_val & 0x0F

    # Nibble 1: high nibble
    dut.uio_in.value = hi
    await ClockCycles(dut.clk, 2)
    dut.ui_in.value = dut.ui_in.value.to_unsigned() | 0x01  # sclk = 1
    await ClockCycles(dut.clk, 2)
    dut.ui_in.value = dut.ui_in.value.to_unsigned() & ~0x01  # sclk = 0
    await ClockCycles(dut.clk, 2)

    # Nibble 2: low nibble
    dut.uio_in.value = lo
    await ClockCycles(dut.clk, 2)
    dut.ui_in.value = dut.ui_in.value.to_unsigned() | 0x01  # sclk = 1
    await ClockCycles(dut.clk, 2)
    dut.ui_in.value = dut.ui_in.value.to_unsigned() & ~0x01  # sclk = 0
    await ClockCycles(dut.clk, 2)


async def qspi_read_byte(dut):
    """Reads one byte over QSPI 4-bit bus (high nibble then low nibble)."""
    # Sample high nibble
    await ClockCycles(dut.clk, 4)
    dut.ui_in.value = dut.ui_in.value.to_unsigned() | 0x01  # sclk = 1
    await ClockCycles(dut.clk, 2)
    hi = dut.uio_out.value.to_unsigned() & 0x0F
    await ClockCycles(dut.clk, 2)
    dut.ui_in.value = dut.ui_in.value.to_unsigned() & ~0x01  # sclk = 0

    # Sample low nibble
    await ClockCycles(dut.clk, 4)
    dut.ui_in.value = dut.ui_in.value.to_unsigned() | 0x01  # sclk = 1
    await ClockCycles(dut.clk, 2)
    lo = dut.uio_out.value.to_unsigned() & 0x0F
    await ClockCycles(dut.clk, 2)
    dut.ui_in.value = dut.ui_in.value.to_unsigned() & ~0x01  # sclk = 0

    return (hi << 4) | lo


async def qspi_send_command(dut, opcode, payload=b""):
    """Asserts CS#, sends opcode and 16-bit payload length, writes payload, deasserts CS#."""
    p_len = len(payload)
    len_msb = (p_len >> 8) & 0xFF
    len_lsb = p_len & 0xFF

    # CS# = 0 (ui_in[1] = 0)
    dut.ui_in.value = dut.ui_in.value.to_unsigned() & ~0x02
    await ClockCycles(dut.clk, 4)

    await qspi_write_byte(dut, opcode)
    await qspi_write_byte(dut, len_msb)
    await qspi_write_byte(dut, len_lsb)
    for b in payload:
        await qspi_write_byte(dut, b)

    await ClockCycles(dut.clk, 12)
    # CS# = 1 (ui_in[1] = 1)
    dut.ui_in.value = dut.ui_in.value.to_unsigned() | 0x02
    await ClockCycles(dut.clk, 5)


async def reset_dut(dut):
    """Initializes clock, asserts reset, and initializes IO lines."""
    clock = Clock(dut.clk, 20, unit="ns")  # 50 MHz
    cocotb.start_soon(clock.start())

    dut.ena.value = 1
    dut.rst_n.value = 0
    dut.ui_in.value = 0x02  # sclk=0, cs_n=1
    dut.uio_in.value = 0x00

    await ClockCycles(dut.clk, 10)
    dut.rst_n.value = 1
    await ClockCycles(dut.clk, 10)


@cocotb.test()
async def test_status(dut):
    """Test 1: Verifies status register, version string, and tri-state bus turnaround."""
    await reset_dut(dut)

    # Initial checks: irq_n=1, busy=0, uio_oe=0
    uo = dut.uo_out.value.to_unsigned()
    assert (uo & 0x01) == 1, "uo_out[0] (irq_n) should be high initially"
    assert (uo & 0x02) == 0, "uo_out[1] (busy) should be low initially"
    assert (dut.uio_oe.value.to_unsigned() & 0x0F) == 0, "uio_oe[3:0] must be 0 while idle"

    # Query status via OP_STATUS
    dut.ui_in.value = dut.ui_in.value.to_unsigned() & ~0x02  # CS# = 0
    await ClockCycles(dut.clk, 4)
    await qspi_write_byte(dut, OP_STATUS)
    await qspi_write_byte(dut, 0x00)
    await qspi_write_byte(dut, 0x00)
    await ClockCycles(dut.clk, 8)

    assert (dut.uio_oe.value.to_unsigned() & 0x0F) == 0x0F, "uio_oe must assert during readout"

    st0 = await qspi_read_byte(dut)
    st1 = await qspi_read_byte(dut)
    st2 = await qspi_read_byte(dut)
    st3 = await qspi_read_byte(dut)

    dut.ui_in.value = dut.ui_in.value.to_unsigned() | 0x02  # CS# = 1
    await ClockCycles(dut.clk, 5)

    assert (dut.uio_oe.value.to_unsigned() & 0x0F) == 0, "uio_oe must release immediately on CS# high"
    assert st1 == 0x10, f"Expected architecture version 0x10, got 0x{st1:02x}"
    dut._log.info(f"Status register read successfully: {st0:02x} {st1:02x} {st2:02x} {st3:02x}")


@cocotb.test()
async def test_x25519(dut):
    """Test 2: Executes X25519 Montgomery Ladder scalar multiplication via TT pins."""
    await reset_dut(dut)

    scalar = bytes.fromhex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
    u_coord = bytes.fromhex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
    expected = bytes.fromhex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552")

    payload = scalar + u_coord
    await qspi_send_command(dut, OP_X25519_MULT, payload)

    # Wait for completion: monitor irq_n (uo_out[0])
    timeout = 5000
    cycles = 0
    while (dut.uo_out.value.to_unsigned() & 0x01) != 0 and cycles < timeout:
        await ClockCycles(dut.clk, 10)
        cycles += 10

    assert (dut.uo_out.value.to_unsigned() & 0x01) == 0, "IRQ# did not assert after X25519 calculation"

    # Read result
    dut.ui_in.value = dut.ui_in.value.to_unsigned() & ~0x02  # CS# = 0
    await ClockCycles(dut.clk, 4)
    await qspi_write_byte(dut, OP_X25519_READ)
    await qspi_write_byte(dut, 0x00)
    await qspi_write_byte(dut, 0x00)
    await ClockCycles(dut.clk, 8)

    res = bytearray()
    for _ in range(32):
        b = await qspi_read_byte(dut)
        res.append(b)

    await ClockCycles(dut.clk, 4)
    dut.ui_in.value = dut.ui_in.value.to_unsigned() | 0x02  # CS# = 1
    await ClockCycles(dut.clk, 5)

    assert bytes(res) == expected, f"X25519 result mismatch: {res.hex()} vs {expected.hex()}"
    dut._log.info(f"X25519 ECDH point mult verified: {res.hex()}")

    # Clear IRQ
    await qspi_send_command(dut, OP_IRQ_CLEAR)
    await ClockCycles(dut.clk, 10)
    dut._log.info(f"uo_out={str(dut.uo_out.value)}, x25519_irq={dut.dut.qspi.x25519.io_irq.value}")
    assert (dut.uo_out.value.to_unsigned() & 0x01) == 1, "irq_n did not return high after OP_IRQ_CLEAR"


@cocotb.test()
async def test_token_seal_and_open(dut):
    """Test 3: Validates authenticated AES-128-CBC + HMAC-SHA256 Token Seal and Open."""
    await reset_dut(dut)

    sign_key = bytes.fromhex("0102030405060708090a0b0c0d0e0f10")
    enc_key  = bytes.fromhex("1112131415161718191a1b1c1d1e1f20")
    iv       = bytes.fromhex("2122232425262728292a2b2c2d2e2f30")
    pt       = b"Tiny Tapeout Reticulum Token Test Payload 32B!"

    seal_payload = sign_key + enc_key + iv + pt
    await qspi_send_command(dut, OP_TOKEN_SEAL, seal_payload)

    # Wait for IRQ
    cycles = 0
    while (dut.uo_out.value.to_unsigned() & 0x01) != 0 and cycles < 4000:
        await ClockCycles(dut.clk, 10)
        cycles += 10
    assert (dut.uo_out.value.to_unsigned() & 0x01) == 0, "IRQ# did not assert after Token Seal"

    # Read sealed token
    dut.ui_in.value = dut.ui_in.value.to_unsigned() & ~0x02  # CS# = 0
    await ClockCycles(dut.clk, 4)
    await qspi_write_byte(dut, OP_TOKEN_READ)
    await qspi_write_byte(dut, 0x00)
    await qspi_write_byte(dut, 0x00)
    await ClockCycles(dut.clk, 8)

    seal_status = await qspi_read_byte(dut)
    seal_msb = await qspi_read_byte(dut)
    seal_lsb = await qspi_read_byte(dut)
    seal_len = (seal_msb << 8) | seal_lsb

    assert seal_status == 0, f"Token seal returned error code {seal_status}"
    sealed_data = bytearray()
    for _ in range(seal_len):
        sealed_data.append(await qspi_read_byte(dut))

    await ClockCycles(dut.clk, 4)
    dut.ui_in.value = dut.ui_in.value.to_unsigned() | 0x02
    await ClockCycles(dut.clk, 5)

    await qspi_send_command(dut, OP_IRQ_CLEAR)
    await ClockCycles(dut.clk, 10)

    # Token Open
    open_payload = sign_key + enc_key + sealed_data
    await qspi_send_command(dut, OP_TOKEN_OPEN, open_payload)

    cycles = 0
    while (dut.uo_out.value.to_unsigned() & 0x01) != 0 and cycles < 4000:
        await ClockCycles(dut.clk, 10)
        cycles += 10
    assert (dut.uo_out.value.to_unsigned() & 0x01) == 0, "IRQ# did not assert after Token Open"

    # Read decrypted plaintext
    dut.ui_in.value = dut.ui_in.value.to_unsigned() & ~0x02
    await ClockCycles(dut.clk, 4)
    await qspi_write_byte(dut, OP_TOKEN_READ)
    await qspi_write_byte(dut, 0x00)
    await qspi_write_byte(dut, 0x00)
    await ClockCycles(dut.clk, 8)

    open_status = await qspi_read_byte(dut)
    open_msb = await qspi_read_byte(dut)
    open_lsb = await qspi_read_byte(dut)
    open_len = (open_msb << 8) | open_lsb

    assert open_status == 0, f"Token open returned error code {open_status}"
    decrypted_data = bytearray()
    for _ in range(open_len):
        decrypted_data.append(await qspi_read_byte(dut))

    await ClockCycles(dut.clk, 4)
    dut.ui_in.value = dut.ui_in.value.to_unsigned() | 0x02
    await ClockCycles(dut.clk, 5)

    assert bytes(decrypted_data) == pt, "Decrypted token plaintext does not match original!"
    dut._log.info(f"Token seal & open roundtrip verified: {bytes(decrypted_data)}")

    await qspi_send_command(dut, OP_IRQ_CLEAR)
    await ClockCycles(dut.clk, 10)
    assert (dut.uo_out.value.to_unsigned() & 0x01) == 1, "irq_n did not return high after OP_IRQ_CLEAR"
