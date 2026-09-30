"""Static safety contract for the backend image and production Compose file."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]


class BackendContainerContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dockerfile = (ROOT / "backend/Dockerfile").read_text(encoding="utf-8")
        cls.dockerignore = (ROOT / ".dockerignore").read_text(encoding="utf-8")
        cls.entrypoint = (ROOT / "backend/docker-entrypoint.sh").read_text(encoding="utf-8")
        cls.compose = (ROOT / "infra/compose/backend.compose.yml").read_text(encoding="utf-8")
        cls.recommendation_compose = (ROOT / "infra/compose/recommendation.compose.yml").read_text(encoding="utf-8")
        cls.recommendation_env = (ROOT / "infra/compose/recommendation.env.example").read_text(encoding="utf-8")
        cls.recommendation_dockerfile = (ROOT / "ai/recommend-server/Dockerfile").read_text(encoding="utf-8")
        cls.recommendation_dockerignore = (ROOT / "ai/.dockerignore").read_text(encoding="utf-8")
        cls.recommendation_entrypoint = (ROOT / "ai/recommend-server/docker-entrypoint.sh").read_text(encoding="utf-8")
        cls.production = (ROOT / "backend/src/main/resources/application-prod.properties").read_text(encoding="utf-8")
        cls.security = (ROOT / "backend/src/main/java/com/micon/backend/domain/auth/config/SecurityConfig.java").read_text(encoding="utf-8")
        cls.host_prep = (ROOT / "infra/deploy/prepare_backend_host.sh").read_text(encoding="utf-8")
        cls.backend_deploy = (ROOT / "infra/deploy/deploy_backend_release.sh").read_text(encoding="utf-8")
        cls.runtime_env = (ROOT / "infra/compose/backend.env.example").read_text(encoding="utf-8")
        cls.voice_service = (ROOT / "backend/src/main/java/com/micon/backend/domain/voice/service/DirectVoiceAnalysisService.java").read_text(encoding="utf-8")
        cls.recommendation_service = (ROOT / "backend/src/main/java/com/micon/backend/domain/recommendation/service/RecommendationDispatchService.java").read_text(encoding="utf-8")
        cls.application_properties = (ROOT / "backend/src/main/resources/application.properties").read_text(encoding="utf-8")
        cls.artist_search_service = (ROOT / "backend/src/main/java/com/micon/backend/domain/voice/service/ArtistSearchJobService.java").read_text(encoding="utf-8")
        cls.ai_artist_contract = (ROOT / "ai/recommend-server/timbre_recommender/contracts.py").read_text(encoding="utf-8")
        cls.song_fit_service = (ROOT / "backend/src/main/java/com/micon/backend/domain/songfit/service/SongFitService.java").read_text(encoding="utf-8")
        cls.ai_gateways = [
            (ROOT / "backend/src/main/java/com/micon/backend/domain/voice/client/HttpVoiceAnalysisJobGateway.java").read_text(encoding="utf-8"),
            (ROOT / "backend/src/main/java/com/micon/backend/domain/voice/client/HttpArtistSearchJobGateway.java").read_text(encoding="utf-8"),
            (ROOT / "backend/src/main/java/com/micon/backend/domain/songfit/client/HttpSongFitGateway.java").read_text(encoding="utf-8"),
        ]

    def test_image_is_pinned_and_runs_as_non_root(self):
        self.assertIn("@sha256:", self.dockerfile)
        self.assertIn("USER 10001:10001", self.dockerfile)
        self.assertIn('ENTRYPOINT ["/app/docker-entrypoint.sh"]', self.dockerfile)

    def test_build_context_only_includes_backend(self):
        self.assertEqual(self.dockerignore.splitlines()[:2], ["**", "!backend/"])
        self.assertIn("!backend/**", self.dockerignore.splitlines())

    def test_secrets_are_files_and_not_tracked_values(self):
        for name in ("DB_PASSWORD", "JWT_SECRET", "AI_SERVICE_TOKEN"):
            self.assertIn(f"{name}_FILE", self.compose)
            self.assertNotIn(f"{name}=", self.compose)
        self.assertIn("AWS_SHARED_CREDENTIALS_FILE: /run/secrets/aws_credentials", self.compose)
        self.assertIn("load_secret DB_PASSWORD required", self.entrypoint)
        self.assertGreaterEqual(self.compose.count("/etc/micon/backend-secrets/"), 4)
        self.assertIn("-o \"$BACKEND_UID\"", self.host_prep)
        self.assertIn("-m 0400", self.host_prep)
        self.assertIn("require_secret aws_credentials", self.host_prep)

    def test_database_is_internal_but_backend_has_egress(self):
        self.assertIn("name: micon_backend", self.compose)
        self.assertIn("external: true", self.compose)
        self.assertIn("- egress", self.compose)
        self.assertIn("127.0.0.1:${MICON_BACKEND_BIND_PORT", self.compose)

    def test_migration_must_finish_before_backend_starts(self):
        self.assertIn("condition: service_completed_successfully", self.compose)
        self.assertIn('SPRING_FLYWAY_ENABLED: "false"', self.compose)
        self.assertIn("mysql_migration_password", self.compose)
        self.assertIn("../../backend/src/main/resources/db/migration:/flyway/sql:ro", self.compose)

    def test_container_is_hardened_and_has_readiness_healthcheck(self):
        self.assertGreaterEqual(self.compose.count("read_only: true"), 2)
        self.assertGreaterEqual(self.compose.count("no-new-privileges:true"), 2)
        self.assertGreaterEqual(self.compose.count("- ALL"), 2)
        self.assertIn("/actuator/health/readiness", self.compose)

    def test_probes_are_public_but_do_not_expose_details(self):
        self.assertIn("management.endpoint.health.group.readiness.include=readinessState,db", self.production)
        self.assertIn("management.endpoint.health.group.liveness.include=livenessState", self.production)
        self.assertIn("management.endpoint.health.show-details=never", self.production)
        self.assertIn('"/actuator/health/**"', self.security)

    def test_ai_runtime_flags_follow_the_production_activation_contract(self):
        self.assertIn("AI_SONG_FIT_ENABLED=false", self.runtime_env)
        self.assertIn("AI_VOICE_ANALYSIS_ENABLED=false", self.runtime_env)
        self.assertIn("AI_ARTIST_SEARCH_ENABLED=true", self.runtime_env)
        self.assertIn("AI_RECOMMENDATION_ENABLED=false", self.runtime_env)
        self.assertIn("AI_RECOMMENDATION_POLLING_ENABLED=false", self.runtime_env)
        self.assertIn("AI_RECOMMENDATION_BASE_URL=http://micon-recommendation:8788", self.runtime_env)
        self.assertIn("AI_RECOMMENDATION_RECOVERY_INTERVAL=1m", self.runtime_env)
        self.assertIn("AI_RECOMMENDATION_RECOVERY_INITIAL_DELAY=10s", self.runtime_env)
        self.assertIn("ensure_runtime_setting AI_RECOMMENDATION_RECOVERY_INTERVAL 1m", self.host_prep)
        self.assertIn("ensure_runtime_setting AI_RECOMMENDATION_RECOVERY_INITIAL_DELAY 10s", self.host_prep)
        self.assertIn("set_runtime_setting AI_ARTIST_SEARCH_ENABLED true", self.host_prep)
        scoring_version = re.search(r'^ARTIST_SEARCH_SCORING_VERSION = "([^"]+)"$', self.ai_artist_contract, re.MULTILINE).group(1)
        self.assertIn(f"AI_ARTIST_SEARCH_SCORING_VERSION={scoring_version}", self.runtime_env)
        self.assertIn(f"set_runtime_setting AI_ARTIST_SEARCH_SCORING_VERSION {scoring_version}", self.host_prep)
        self.assertIn(f"${{AI_ARTIST_SEARCH_SCORING_VERSION:{scoring_version}}}", self.application_properties)
        self.assertIn("${ai.artist-search-scoring-version}", self.artist_search_service)
        self.assertIn('sed -i "s|^${setting_name}=.*$|${setting_name}=${setting_value}|"', self.host_prep)
        self.assertIn("${AI_ARTIST_SEARCH_ENABLED:false}", self.application_properties)
        self.assertIn("ai.voice-analysis-enabled:false", self.voice_service)
        self.assertIn("ai.recommendation.enabled:false", self.recommendation_service)
        self.assertIn("if (!enabled)", self.voice_service)
        self.assertIn("if (!enabled)", self.recommendation_service)
        self.assertIn("${AI_SONG_FIT_ENABLED:false}", self.application_properties)
        self.assertIn("${AI_RECOMMENDATION_POLLING_ENABLED:false}", self.application_properties)
        self.assertIn("${AI_RECOMMENDATION_RECOVERY_INTERVAL:1m}", self.application_properties)
        self.assertIn("${AI_RECOMMENDATION_RECOVERY_INITIAL_DELAY:10s}", self.application_properties)
        self.assertIn("${ai.song-fit-enabled:false}", self.song_fit_service)

    def test_artist_search_flag_uses_the_shared_blue_green_runtime_file(self):
        self.assertIn("- ${MICON_BACKEND_ENV_FILE:-/etc/micon/runtime/backend.env}", self.compose)
        self.assertIn("export MICON_BACKEND_ENV_FILE=/etc/micon/runtime/backend.env", self.backend_deploy)
        self.assertIn("blue) candidate_slot=green", self.backend_deploy)
        self.assertIn("green|'') candidate_slot=blue", self.backend_deploy)

    def test_every_8788_gateway_uses_the_recommendation_base_url(self):
        for gateway in self.ai_gateways:
            self.assertIn("${ai.recommendation.base-url:http://localhost:8788}", gateway)
            self.assertNotIn("localhost:8000", gateway)
        self.assertNotIn("AI_BASE_URL", self.runtime_env)
        self.assertNotIn("micon-ai:8000", self.runtime_env)

    def test_recommendation_service_has_internal_bidirectional_backend_route(self):
        self.assertIn("micon-recommendation:", self.recommendation_compose)
        self.assertIn("name: micon_backend", self.recommendation_compose)
        self.assertIn("external: true", self.recommendation_compose)
        self.assertIn("MICON_BE_BASE_URL=http://backend:8080", self.recommendation_env)
        self.assertIn("MICON_BE_CALLBACK_URL=http://backend:8080/callbacks/voice-analysis-components",
                      self.recommendation_env)
        self.assertIn("127.0.0.1:${MICON_RECOMMENDATION_BIND_PORT:-8788}:8788",
                      self.recommendation_compose)

    def test_recommendation_image_includes_range_worker_and_uses_secret_file(self):
        self.assertIn("COPY vocal-scope/model-server /vocal-scope/model-server",
                      self.recommendation_dockerfile)
        self.assertNotIn("**/models", self.recommendation_dockerignore)
        self.assertTrue((ROOT / "ai/vocal-scope/model-server/be_range_worker.py").is_file())
        singer_component = (ROOT / "ai/recommend-server/third_party/ssl-singer-identity"
                            / "singer_identity/models/network_components.py")
        self.assertTrue(singer_component.is_file())
        self.assertIn("USER 10001:10001", self.recommendation_dockerfile)
        self.assertIn("NUMBA_CACHE_DIR=/tmp/numba-cache", self.recommendation_dockerfile)
        self.assertIn('ENTRYPOINT ["/app/docker-entrypoint.sh"]', self.recommendation_dockerfile)
        self.assertIn("AI_SERVICE_TOKEN_FILE: /run/secrets/ai_service_token",
                      self.recommendation_compose)
        self.assertIn("load_secret AI_SERVICE_TOKEN", self.recommendation_entrypoint)
        self.assertNotIn("AI_SERVICE_TOKEN=", self.recommendation_compose)


if __name__ == "__main__":
    unittest.main()
