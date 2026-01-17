google: ./legacy.json
	docker mcp catalog-next create vonwig/google:latest --title "New Google MCP Servers" --from-legacy-catalog ./legacy.json

push-google: google
	docker mcp catalog-next push vonwig/google:latest

check-secrets:
	docker run -i -l x-secret:com-googleapis-container-gke.project_id=/secret docker/jcat /secret; \
	docker run -i -l x-secret:com-google-cloud-compute-mcp.project_id=/secret docker/jcat /secret; \
	docker run -i -l x-secret:com-google-cloud-bigquery-mcp.project_id=/secret docker/jcat /secret; \
	docker run -i -l x-secret:com-google-maps-grounding-lite.api_key=/secret docker/jcat /secret

register-bigquery:
	DOCKER_MCP_USE_CE=true docker mcp oauth register com-google-cloud-bigquery-mcp \
	--client-id "567003538472-hdbh7ojrog97tgr6fh81eoo3o531u1jb.apps.googleusercontent.com" \
	--client-secret "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" \
	--auth-endpoint "https://accounts.google.com/o/oauth2/v2/auth" \
	--token-endpoint "https://oauth2.googleapis.com/token" \
	--scopes "https://www.googleapis.com/auth/bigquery"
	
login:
	DOCKER_MCP_USE_CE=true docker mcp oauth authorize com-google-cloud-bigquery-mcp
