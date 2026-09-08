# Recommender model sources

`setup_recommender.py` downloads only the files below. Repository revisions and
file SHA-256 values are pinned so a changed upstream file is rejected.

| Component | Source | Revision | License | SHA-256 |
|---|---|---|---|---|
| ECAPA-TDNN | `speechbrain/spkrec-ecapa-voxceleb` / `embedding_model.ckpt` | `0f99f2d0ebe89ac095bcc5903c4dd8f72b367286` | Apache-2.0 | `0575cb64845e6b9a10db9bcb74d5ac32b326b8dc90352671d345e2ee3d0126a2` |
| Singer-BYOL weights | `BernardoTorres/singer-identity` / `byol/model.pt` | `55e09cdfcd733b1c01f2b94f316a0280995a909e` | MIT | `509f96f23a1076325f4164fa4176fa282e709da203d30b109fa05b1b6cb3c978` |
| Singer-BYOL hyperparameters | `BernardoTorres/singer-identity` / `byol/hyperparams.yaml` | `55e09cdfcd733b1c01f2b94f316a0280995a909e` | MIT | `c0a4160b9546f3d8c8245b559a0a5386fb0925feb9179ba4bc4bcea41dbbde5a` |
| Singer-BYOL inference source | `SonyCSLParis/ssl-singer-identity` | `24751d11c6c169adc53be906fd36560df447f974` | MIT | vendored minimal source; see `../third_party/ssl-singer-identity/LICENSE` |

The 24-track demo catalog stores derived embedding vectors and provenance
hashes only. It does not contain source audio.
