import { defineRailway, github, preserve, project, service } from "railway/iac";

export const partial = "elosys-backend";

export default defineRailway(() => {
  const elosys_backend = service("elosys-backend", {
    source: github("niicsz/elosys-backend", { branch: "main" }),
    builder: "DOCKERFILE",
    dockerfilePath: "Dockerfile",
    healthcheck: "/actuator/health/readiness",
    healthcheckTimeout: 600,
    restartPolicyType: "ON_FAILURE",
    restartPolicyMaxRetries: 5,
    variables: {
      PORT: "8080",
      DB_HOST: "elosys-postgres-elosys-user4.apps.cluster-qwln2.dyn.redhatworkshops.io",
      DB_PORT: "443",
      DB_NAME: "elosys",
      DB_USER: "elosys",
      DB_PASSWORD: preserve(),
      DB_POOL_SIZE: "10",
      DB_SSLMODE: "require",
      DB_SSL_NEGOTIATION: "direct",
      REDIS_HOST: "${{Redis.REDISHOST}}",
      REDIS_PORT: "${{Redis.REDISPORT}}",
      REDIS_USER: "${{Redis.REDISUSER}}",
      REDIS_PASSWORD: "${{Redis.REDISPASSWORD}}",
      ELOSYS_TMP_DIR: "dados_tmp",
      ELOSYS_REPORTS_DIR: "reports",
      ELOSYS_ADMIN_TOKEN: preserve(),
      ELOSYS_ALLOWED_ORIGINS: "https://elosys-frontend-production.up.railway.app",
      ELOSYS_CACHE_TTL: "30m",
      ELOSYS_CURL_IMPERSONATE: "",
      ANTHROPIC_API_KEY: preserve(),
      ANTHROPIC_MODEL: "claude-haiku-4-5",
      ANTHROPIC_TIMEOUT: "60s",
      APIFY_TOKEN: preserve(),
      APIFY_ACTOR: "kaitoeasyapi~twitter-x-data-tweet-scraper-pay-per-result-cheapest",
    },
  });
  return project("elosys", {
    resources: [elosys_backend],
  });
});
