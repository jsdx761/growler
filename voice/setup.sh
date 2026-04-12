#!/usr/bin/env bash
# ============================================================================
# Environment setup for the Voice Segment Generation Pipeline
# Run once before using pipeline.py
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

PYTHON_VERSION="3.12.10"
VENV_NAME="ds1-pace-voice"

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  Voice Segment Generation Pipeline — Setup"
echo "═══════════════════════════════════════════════════════════════"

# ── 1. Python (pyenv) ─────────────────────────────────────────────────────
echo ""
echo "[1/3] Python environment (pyenv)"

# Ensure pyenv is available
if ! command -v pyenv &>/dev/null; then
    echo "  pyenv not found. Please install pyenv first:"
    echo "    https://github.com/pyenv/pyenv#installation"
    exit 1
fi

# Ensure pyenv shell integration is loaded
eval "$(pyenv init -)" 2>/dev/null || true
eval "$(pyenv virtualenv-init -)" 2>/dev/null || true

# Install Python version (--skip-existing avoids errors if already present)
echo "  Ensuring Python $PYTHON_VERSION is installed ..."
pyenv install --skip-existing "$PYTHON_VERSION"

# Create virtualenv if it doesn't already exist
if ! pyenv versions --bare 2>/dev/null | grep -qx "$VENV_NAME"; then
    echo "  Creating virtualenv '$VENV_NAME' ..."
    pyenv virtualenv "$PYTHON_VERSION" "$VENV_NAME"
else
    echo "  Virtualenv '$VENV_NAME' already exists"
fi

pyenv local "$VENV_NAME"
echo "  Using pyenv virtualenv '$VENV_NAME' (Python $PYTHON_VERSION)"

# ── 2. Python dependencies ────────────────────────────────────────────────
echo ""
echo "[2/3] Python dependencies (requirements.txt)"
pip install -q -r requirements.txt

# ── 3. Reference recordings ──────────────────────────────────────────────
echo ""
echo "[3/3] Reference recordings"
REF_COUNT=$(find reference_recordings -maxdepth 1 -name "*.wav" 2>/dev/null | wc -l)
if [ "$REF_COUNT" -eq 0 ]; then
    echo "  No reference recordings found."
    echo "  Place WAV clips of the target voice in reference_recordings/"
    echo "  before running the pipeline."
else
    echo "  Found $REF_COUNT reference recording(s)"
fi

# ── Done ──────────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  Setup complete"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "  Next steps:"
echo "    1. Export your Mistral API key:"
echo "       export MISTRAL_API_KEY=\"your-api-key-here\""
echo "    2. Run the pipeline:"
echo "       python pipeline.py"
echo ""
