"""Local ECAPA + Singer-BYOL vocal timbre recommender."""

from .catalog import CatalogIndex, build_catalog_index
from .scoring import RankerConfig, recommend

__all__ = ["CatalogIndex", "RankerConfig", "build_catalog_index", "recommend"]
__version__ = "0.1.0"
