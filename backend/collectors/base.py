from dataclasses import dataclass
from typing import Callable, Any


@dataclass(frozen=True)
class Collector:
    name: str
    run: Callable[[], Any]
