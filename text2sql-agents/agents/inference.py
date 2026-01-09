"""Sprint 3 - Role A (A9): Inference Abstraction Layer.

Goal: centralize all LLM inference calls behind a small API so agents can:
- share retries / timeouts / error handling,
- switch to OpenAI-compatible local/optimized models without code changes.

Configuration (env vars):
- `INFERENCE_BASE_URL` (optional): OpenAI-compatible API base URL.
  Examples:
    - OpenAI (default): unset
    - Local vLLM: `http://localhost:8000/v1`
    - Ollama OpenAI-compatible: `http://localhost:11434/v1`
- `INFERENCE_API_KEY` (optional): overrides `OPENAI_API_KEY`.
- `OPENAI_API_KEY`: used if `INFERENCE_API_KEY` not set.

Notes on model parameters:
- Some models (notably `gpt-5-*`) reject non-default `temperature` and/or `stop`.
  This module drops unsupported params for known models so call sites stay simple.
"""

from __future__ import annotations

import os
import random
import time
from typing import Any, Dict, List, Optional

from openai import OpenAI


def _get_client() -> OpenAI:
    base_url = (os.getenv("INFERENCE_BASE_URL") or "").strip() or None
    api_key = (os.getenv("INFERENCE_API_KEY") or os.getenv("OPENAI_API_KEY") or "").strip()

    # For local OpenAI-compatible servers, an API key may not be required.
    if not api_key and base_url:
        api_key = "local"

    if not api_key:
        raise ValueError("OPENAI_API_KEY environment variable is not set")

    if base_url:
        return OpenAI(api_key=api_key, base_url=base_url)

    return OpenAI(api_key=api_key)


def _sanitize_params_for_model(model: str, params: Dict[str, Any]) -> Dict[str, Any]:
    # Centralized guardrails for known model capability mismatches.
    sanitized = dict(params)

    # gpt-5-* commonly rejects non-default temperature.
    if model.startswith("gpt-5"):
        sanitized.pop("temperature", None)
        sanitized.pop("stop", None)

    return sanitized


def chat_completion_text(
    *,
    model: str,
    messages: List[Dict[str, str]],
    n: int = 1,
    max_retries: int = 3,
    request_timeout_s: Optional[float] = None,
    **kwargs: Any,
) -> List[str]:
    """Return one or more assistant message contents.

    Args:
        model: Model name (e.g., `gpt-5-mini`).
        messages: OpenAI chat messages.
        n: Number of completions to request.
        max_retries: Retries on transient API failures.
        request_timeout_s: Optional per-request timeout (best-effort).
        kwargs: Extra OpenAI parameters (e.g., `temperature`).

    Returns:
        List of assistant message contents (length n, best-effort).
    """

    if n < 1:
        raise ValueError("n must be >= 1")

    client = _get_client()

    params: Dict[str, Any] = {
        "model": model,
        "messages": messages,
    }

    # Support multi-candidate generation with OpenAI's `n=`.
    if n != 1:
        params["n"] = n

    if request_timeout_s is not None:
        # The OpenAI Python SDK supports a `timeout` kwarg via httpx.
        # We pass it through best-effort.
        params["timeout"] = request_timeout_s

    params.update(kwargs)
    params = _sanitize_params_for_model(model, params)

    last_exc: Optional[Exception] = None
    for attempt in range(1, max_retries + 1):
        try:
            resp = client.chat.completions.create(**params)
            out: List[str] = []
            for choice in resp.choices:
                out.append((choice.message.content or "").strip())

            # Ensure we always return at least one element.
            if not out:
                out = [""]

            # If API returned fewer than requested, pad deterministically.
            while len(out) < n:
                out.append(out[-1])

            return out[:n]
        except Exception as e:
            last_exc = e
            if attempt >= max_retries:
                break

            # Exponential backoff with jitter.
            sleep_s = min(8.0, 0.5 * (2 ** (attempt - 1)))
            sleep_s *= 0.8 + (0.4 * random.random())
            time.sleep(sleep_s)

    raise RuntimeError(f"Inference call failed after {max_retries} attempt(s): {str(last_exc)}")
