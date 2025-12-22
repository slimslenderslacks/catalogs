google: ./legacy.json
	docker mcp catalog-next create vonwig/google:latest --title "New Google MCP Servers" --from-legacy-catalog ./legacy.json

push-google: google
	docker mcp catalog-next push vonwig/google:latest

check-secrets:
	docker run -i -l x-secret:com-googleapis-container-gke.project_id=/secret docker/jcat /secret; \
	docker run -i -l x-secret:com-google-cloud-compute-mcp.project_id=/secret docker/jcat /secret; \
	docker run -i -l x-secret:com-google-cloud-bigquery-mcp.project_id=/secret docker/jcat /secret; \
	docker run -i -l x-secret:com-google-maps-grounding-lite.api_key=/secret docker/jcat /secret
