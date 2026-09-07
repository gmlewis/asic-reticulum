#!/usr/bin/env bash
#
# Copyright 2026 Glenn Lewis. All rights reserved.
# Use of this source code is governed by the BSD-style
# license that can be found in the LICENSE file.
#
# scripts/run-openlane.sh: Automates OpenLane RTL-to-GDSII flow for Tiny Tapeout.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

DESIGN_NAME="tt_um_gmlewis_reticulum"
PDK_ROOT="${PDK_ROOT:-$HOME/.volare}"
OPENLANE_IMAGE="${OPENLANE_IMAGE:-efabless/openlane:2023.11.23}"

echo "============================================================"
echo " Reticulum Crypto Accelerator — OpenLane GDS Synthesis Flow"
echo "============================================================"
echo "Design:     ${DESIGN_NAME}"
echo "Repo Root:  ${REPO_ROOT}"
echo "PDK Root:   ${PDK_ROOT}"
echo "Container:  ${OPENLANE_IMAGE}"
echo "============================================================"

# Step 1: Ensure generated Verilog is up-to-date and copied to src/
mkdir -p "${REPO_ROOT}/src"
if [ ! -f "${REPO_ROOT}/hw/gen/${DESIGN_NAME}.v" ]; then
    echo ">> [1/4] Generating Verilog netlist via sbt..."
    (cd "${REPO_ROOT}" && sbt "runMain reticulum.tt.TinyTapeoutVerilog")
fi

echo ">> [1/4] Syncing generated Verilog to src/${DESIGN_NAME}.v..."
cp -f "${REPO_ROOT}/hw/gen/${DESIGN_NAME}.v" "${REPO_ROOT}/src/${DESIGN_NAME}.v"

# Step 2: Check for Docker or Podman
DOCKER_CMD=""
if command -v docker &> /dev/null; then
    DOCKER_CMD="docker"
elif command -v podman &> /dev/null; then
    DOCKER_CMD="podman"
fi

if [ -z "${DOCKER_CMD}" ]; then
    echo "WARNING: Neither docker nor podman was found on PATH."
    echo "To run OpenLane locally, please install Docker Desktop or Podman."
    echo "Alternatively, submit to the Tiny Tapeout GitHub Actions CI workflow."
    exit 0
fi

# Step 3: Check PDK installation
if [ ! -d "${PDK_ROOT}/sky130A" ]; then
    echo "Notice: Sky130 PDK not found at ${PDK_ROOT}/sky130A."
    echo "If using Volare to manage PDKs, run: volare enable --pdk sky130 <version>"
    echo "Attempting to run OpenLane with container-bundled PDK..."
fi

# Step 4: Execute OpenLane
echo ">> [2/4] Launching OpenLane synthesis..."
RUN_TAG="run_$(date +%Y%m%d_%H%M%S)"

${DOCKER_CMD} run --rm \
    -v "${REPO_ROOT}:/work" \
    -v "${PDK_ROOT}:/pdk:ro" \
    -e PDK_ROOT=/pdk \
    -e PDK=sky130A \
    -w /work \
    "${OPENLANE_IMAGE}" \
    ./flow.tcl -design /work/openlane -tag "${RUN_TAG}"

echo ">> [3/4] OpenLane run completed with tag: ${RUN_TAG}"

# Step 5: Check outputs
REPORT_DIR="${REPO_ROOT}/openlane/runs/${RUN_TAG}"
if [ -d "${REPORT_DIR}" ]; then
    echo ">> [4/4] Synthesis Reports and GDS located at:"
    echo "   ${REPORT_DIR}"
    if [ -f "${REPORT_DIR}/reports/metrics.csv" ]; then
        echo "=== Synthesis Summary Metrics ==="
        cat "${REPORT_DIR}/reports/metrics.csv"
    fi
fi

echo "============================================================"
echo " Flow complete for ${DESIGN_NAME}!"
echo "============================================================"
