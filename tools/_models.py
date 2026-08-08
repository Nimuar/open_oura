"""Shared torch helpers for the tools/ model runners.

Kept separate from `_common.py` so runners that need no model (run_spo2) stay
importable without torch. The models themselves live in `notes/models/`
(gitignored — supply your own decrypted `.pt`).
"""
import sys

import torch

from _common import MODELS_DIR


def model_path(name):
    """Path of a model by family name, e.g. `cva_2_1_0` -> notes/models/cva_2_1_0.pt."""
    return MODELS_DIR / f"{name}.pt"


def load_model(name):
    """Load a TorchScript model in eval mode, or exit with a clear message."""
    path = model_path(name)
    if not path.exists():
        sys.exit(f"error: model not found: {path}")
    return torch.jit.load(str(path), map_location="cpu").eval()


def f32(seq, cols=None):
    """float32 tensor from a sequence of rows.

    With `cols`, an empty `seq` yields a rank-2 `[0, cols]` tensor: the models
    shape-mismatch on the 1-D tensor a bare `torch.tensor([])` would produce.
    """
    if not len(seq) and cols is not None:
        return torch.empty((0, cols), dtype=torch.float32)
    return torch.tensor(seq, dtype=torch.float32)


def i64(seq):
    """int64 tensor — the dtype every model's timestamp input expects."""
    return torch.tensor(seq, dtype=torch.int64)
