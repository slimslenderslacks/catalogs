# Registry to Catalog Transformer (Go)

This Go package transforms MCP community registry JSON format to Docker catalog JSON format.

## Features

- Transforms community registry `ServerDetail` format to Docker catalog format
- Handles both OCI package servers and remote servers
- Properly extracts and separates secrets from config variables
- Restores variable interpolation syntax (`{{var}}` for config, `${VAR}` for secrets)
- Supports runtime arguments, package arguments, environment variables
- Extracts volumes and user from runtime arguments
- Preserves OAuth metadata
- Handles icons, titles, and descriptions

## Using with MCP Registry API

You can stream content directly from the MCP community registry API:

```bash
# Fetch a specific server version from the registry and transform it
curl --request GET \
  --url 'https://registry.modelcontextprotocol.io/v0.1/servers/io.github.idjohnson%2Fvikunjamcp/versions/1.0.26' \
  --header 'Accept: application/json, application/problem+json' \
  | ./go/bin/registry-to-catalog

# Save to a file
curl --request GET \
  --url 'https://registry.modelcontextprotocol.io/v0.1/servers/io.github.idjohnson%2Fvikunjamcp/versions/1.0.26' \
  --header 'Accept: application/json, application/problem+json' \
  | ./go/bin/registry-to-catalog -output catalog.json

# Transform multiple servers
for server in "io.github.user/server1" "io.github.user/server2"; do
  curl --request GET \
    --url "https://registry.modelcontextprotocol.io/v0.1/servers/${server}/versions/latest" \
    --header 'Accept: application/json, application/problem+json' \
    | ./go/bin/registry-to-catalog -output "${server//\//-}.json"
done
```

## Quick Start

### Build the CLI tool

```bash
cd go
go build -o bin/registry-to-catalog ./cmd/registry-to-catalog
```

### Transform a registry file

```bash
# From file to stdout
./bin/registry-to-catalog -input servers/grounding_lite.json

# From file to file
./bin/registry-to-catalog -input servers/grounding_lite.json -output catalog.json

# From stdin to stdout
cat servers/grounding_lite.json | ./bin/registry-to-catalog

# From stdin to file
cat servers/grounding_lite.json | ./bin/registry-to-catalog -output catalog.json
```

## Usage

### As a CLI Tool

```bash
registry-to-catalog [flags]

Flags:
  -input string
        Input community registry JSON file (or - for stdin) (default: stdin)
  -output string
        Output catalog JSON file (or - for stdout) (default: stdout)
```

### As a Library

```go
import "github.com/slimslenderslacks/catalogs"

// Transform JSON string
registryJSON := `{"$schema": "...", "name": "...", ...}`
catalogJSON, err := TransformJSON(registryJSON)
if err != nil {
    log.Fatal(err)
}
fmt.Println(catalogJSON)

// Or transform ServerDetail struct directly
var serverDetail ServerDetail
json.Unmarshal([]byte(registryJSON), &serverDetail)

dockerServer, err := TransformToDocker(serverDetail)
if err != nil {
    log.Fatal(err)
}
```

## Running Tests

```bash
cd go
go test -v
```

## Test Coverage

The test suite includes examples for:

1. **OCI Package Server** (`TestTransformOCIPackage`)
   - OCI registry with docker.io
   - Runtime arguments with volumes and user
   - Package arguments for commands
   - Environment variables with interpolation
   - Config variables and secrets separation

2. **Remote Server with Headers** (`TestTransformRemote`)
   - Streamable-HTTP transport
   - Headers with variable interpolation
   - Secrets (isSecret: true) vs Config (isSecret: false)
   - Icon support

3. **Remote Server with OAuth** (`TestTransformRemoteWithOAuth`)
   - OAuth providers metadata
   - Publisher-provided metadata extraction

4. **Simple Remote Server** (`TestTransformSimpleRemote`)
   - Basic remote without headers or variables
   - Minimal transformation

## Transformation Details

### Variable Interpolation

The tool correctly handles variable interpolation:
- Config variables (non-secret): `{var}` → `{{var}}`
- Secret variables: `{var}` → `${VAR}` (uppercased)

### Server Name Transformation

Fully qualified names are transformed for catalog compatibility:
- `com.google.maps/grounding-lite` → `com-google-maps-grounding-lite`
- `io.github.user/server` → `io-github-user-server`

### OCI Package Transformation

For OCI packages:
- Image reference: `{identifier}@{version}`
- Runtime arguments are parsed for volumes (`-v`) and user (`-u`)
- Package arguments become the command array
- Environment variables preserve interpolation

### Remote Transformation

For remote servers:
- Transport type maps to `transport_type`
- Headers are converted to key-value map with interpolation
- Type is set to `"remote"`

### Secrets and Config

Variables are separated based on `isSecret` flag:
- **Secrets**: Added to `secrets` array with uppercased env names
- **Config**: Added to `config` JSON schema with proper types

### Metadata Preservation

Publisher-provided metadata is preserved:
- OAuth configuration is extracted and added to the root
- Icons are preserved (first icon's src becomes the icon URL)

## Struct Definitions

### Community Registry Format

- `ServerDetail`: Top-level server definition
- `Package`: OCI/npm/pypi package definition
- `Transport`: Remote transport (sse, streamable-http)
- `Argument`: Runtime or package arguments
- `KeyValueInput`: Environment variables and headers
- `Input`: Variable definitions with secrets flag

### Docker Catalog Format

- `DockerServer`: Top-level catalog entry
- `DockerRemote`: Remote configuration
- `ConfigSchema`: JSON schema for configuration
- `Secret`: Secret definition with env name
- `EnvVar`: Environment variable

## License

Same as parent repository.
